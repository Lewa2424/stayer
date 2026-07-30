#!/usr/bin/env python3
"""
Независимый 1D стресс-стенд для одометра «Липучка».

Цель:
  * GPS-проекция является кандидатом, а не принятой позой.
  * Credit равен только принятому перемещению pose.
  * Неправдоподобный candidate не создаёт debt и не телепортирует pose.
  * Reverse подтверждается растущим буфером, а не ровно тремя точками.
  * EMA ограничивает оценку, но сама не доказывает движение.

Это НЕ полный RailMatcher: здесь нет lat/lon, реального перпендикулярного
проектора, нескольких рёбер и развилок. Для них нужен следующий, 2D-этап.

Запуск:
    python lipuchka_sim_gpt.py
    python lipuchka_sim_gpt.py --seeds 50
    python lipuchka_sim_gpt.py --rail-ticks stayer_rail_ticks_20260730_052727.jsonl

Exit code:
    0 — все обязательные проверки пройдены;
    1 — хотя бы один сценарий или инвариант провален.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import statistics
import sys
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Iterable, Optional


# =============================================================================
# Общая геометрия одномерного маршрута
# =============================================================================


def wrap(value: float, length: float) -> float:
    return value % length


def forward_arc(start: float, end: float, length: float) -> float:
    return (end - start) % length


def backward_arc(start: float, end: float, length: float) -> float:
    return (start - end) % length


def signed_progress(pose: float, candidate: float, length: float, direction: int) -> float:
    """Короткое локальное смещение: плюс по принятому направлению, минус против."""
    if direction >= 0:
        forward = forward_arc(pose, candidate, length)
        backward = backward_arc(pose, candidate, length)
    else:
        forward = backward_arc(pose, candidate, length)
        backward = forward_arc(pose, candidate, length)
    return forward if forward <= backward else -backward


def median(values: Iterable[float]) -> float:
    materialized = list(values)
    return statistics.median(materialized) if materialized else 0.0


def close_enough(left: float, right: float) -> bool:
    tolerance = max(0.8, 0.55 * max(abs(left), abs(right), 1.0))
    return abs(left - right) <= tolerance


# =============================================================================
# Наблюдения и результаты
# =============================================================================


@dataclass(frozen=True)
class Observation:
    candidate_s: float
    raw_delta: float
    dt: float = 1.0
    accuracy: float = 5.0
    location_speed_mps: Optional[float] = None
    cadence_delta: Optional[float] = None
    lateral_distance_m: float = 0.0


@dataclass(frozen=True)
class Tick:
    observation: Observation
    # Перемещение от предыдущего observation до этого. Для первого tick оно
    # намеренно не входит в observable truth: до первого fix нет исходной позы.
    true_advance: float


@dataclass(frozen=True)
class MotionEstimate:
    distance_m: float
    confidence: int
    stationary: bool
    sources: tuple[str, ...]


class PendingDecision(Enum):
    AMBIGUOUS = "ambiguous"
    FORWARD = "forward"
    REVERSE = "reverse"
    DISCARD = "discard"


class CaseKind(Enum):
    ACCURACY = "accuracy"
    SAFETY = "safety"
    STATE = "state"


@dataclass
class CaseResult:
    name: str
    kind: CaseKind
    true_m: float
    approx_current_m: float
    credited_m: float
    error_pct: float
    reverse_count: int
    max_tick_credit: float
    passed: bool
    detail: str


# =============================================================================
# Приближённая старая модель — только для сравнения класса airtrain
# =============================================================================


@dataclass
class ApproxCurrentEngine:
    """Не побитовый Stayer 3.7.1. Это иллюстрация cap+debt march-to-candidate."""

    length: Optional[float] = None
    max_speed_mps: float = 10.0
    max_debt_m: float = 30.0
    s: float = 0.0
    debt_m: float = 0.0
    credited_m: float = 0.0
    locked: bool = False

    def tick(self, observation: Observation) -> float:
        candidate = (
            observation.candidate_s
            if self.length is None
            else wrap(observation.candidate_s, self.length)
        )
        if not self.locked:
            self.s = candidate
            self.locked = True
            return 0.0

        if self.length is None:
            path_delta = candidate - self.s
            if path_delta < 0:
                return 0.0
        else:
            path_delta = forward_arc(self.s, candidate, self.length)
            backward = backward_arc(self.s, candidate, self.length)
            if backward < path_delta:
                return 0.0

        budget = self.max_speed_mps * max(observation.dt, 1e-3) + self.debt_m
        credit = min(max(path_delta, 0.0), budget)
        leftover = max(path_delta - credit, 0.0)
        if leftover > 0:
            self.debt_m = min(self.max_debt_m, max(self.debt_m, leftover))
        elif self.debt_m > 0 and credit < budget:
            payment = min(self.debt_m, budget - credit)
            credit += payment
            self.debt_m -= payment

        self.s = self.s + credit if self.length is None else wrap(self.s + credit, self.length)
        self.credited_m += credit
        return credit


# =============================================================================
# Эталонная одномерная «Липучка»
# =============================================================================


@dataclass
class StickyPoseEngine:
    length: Optional[float] = None
    vmax_mps: float = 7.0
    max_lateral_m: float = 60.0
    reverse_min_samples: int = 3
    reverse_min_span_m: float = 2.0
    reverse_accuracy_factor: float = 0.20
    max_pending_samples: int = 16
    max_pending_seconds: float = 12.0
    jump_factor: float = 3.0
    ema_alpha: float = 0.22

    s: float = 0.0
    direction: int = 1
    credited_m: float = 0.0
    locked: bool = False
    pending: list[Observation] = field(default_factory=list)
    pending_pose_s: float = 0.0
    ema_speed_mps: float = 0.0
    ema_confidence: int = 0
    reverse_count: int = 0
    ambiguous_count: int = 0
    rejected_jump_count: int = 0
    raw_slide_count: int = 0
    invariant_failures: list[str] = field(default_factory=list)
    max_tick_credit_m: float = 0.0

    def _normalize(self, value: float) -> float:
        return value if self.length is None else wrap(value, self.length)

    def _signed_from(self, pose: float, candidate: float, direction: Optional[int] = None) -> float:
        effective_direction = self.direction if direction is None else direction
        if self.length is None:
            return effective_direction * (candidate - pose)
        return signed_progress(pose, candidate, self.length, effective_direction)

    def _signed(self, candidate: float) -> float:
        return self._signed_from(self.s, candidate)

    def _rear_deadband(self, accuracy: float) -> float:
        return max(0.6, min(3.0, 0.12 * max(accuracy, 0.0)))

    def _forward_confirmation(self, accuracy: float) -> float:
        return max(0.8, min(3.5, 0.15 * max(accuracy, 0.0)))

    def _hard_cap(self, observation: Observation) -> float:
        return self.vmax_mps * max(observation.dt, 1e-3)

    def _stationary_votes(self, observation: Observation) -> int:
        votes = 0
        if observation.cadence_delta is not None and observation.cadence_delta <= 0.12:
            votes += 1
        if (
            observation.location_speed_mps is not None
            and observation.location_speed_mps <= 0.18
        ):
            votes += 1
        return votes

    def _motion_estimate(self, observation: Observation) -> MotionEstimate:
        """Робастная оценка перемещения без использования candidate_s."""
        dt = max(observation.dt, 1e-3)
        hard_cap = self._hard_cap(observation)

        cadence: Optional[float] = None
        if observation.cadence_delta is not None:
            cadence = min(max(observation.cadence_delta, 0.0), hard_cap)

        speed_distance: Optional[float] = None
        if observation.location_speed_mps is not None:
            speed = max(observation.location_speed_mps, 0.0)
            if speed <= self.vmax_mps * 1.25:
                speed_distance = min(speed * dt, hard_cap)

        raw: Optional[float] = None
        raw_speed = max(observation.raw_delta, 0.0) / dt
        if raw_speed <= self.vmax_mps * 1.35:
            raw = min(max(observation.raw_delta, 0.0), hard_cap)

        if self._stationary_votes(observation) >= 2:
            return MotionEstimate(0.0, 3, True, ("cadence=0", "speed=0"))

        trusted: list[tuple[str, float]] = []

        # Cadence — независимый от GPS источник.
        if cadence is not None and cadence > 0.12:
            trusted.append(("cadence", cadence))
            if speed_distance is not None and close_enough(cadence, speed_distance):
                trusted.append(("locationSpeed", speed_distance))
            if raw is not None and close_enough(cadence, raw):
                trusted.append(("rawDelta", raw))

        # Два согласованных GPS-признака допустимы без cadence.
        elif (
            speed_distance is not None
            and raw is not None
            and speed_distance > 0.12
            and raw > 0.12
            and close_enough(speed_distance, raw)
        ):
            # LocationSpeed подтверждает, что rawDelta соответствует реальному
            # движению, но не усредняет его вниз. Сам rawDelta — перемещение
            # между fix; speed здесь второй голос, а не второй одометр.
            return MotionEstimate(
                distance_m=raw,
                confidence=2,
                stationary=False,
                sources=("rawDelta", "locationSpeed"),
            )

        # Один источник можно принять лишь после устойчивого EMA и только если он согласован с ним.
        else:
            expected = self.ema_speed_mps * dt
            if self.ema_confidence >= 5 and expected > 0.15:
                if (
                    speed_distance is not None
                    and speed_distance > 0.12
                    and close_enough(speed_distance, expected)
                ):
                    trusted.append(("locationSpeed+EMA", speed_distance))
                elif raw is not None and raw > 0.12 and close_enough(raw, expected):
                    trusted.append(("rawDelta+EMA", raw))

        if not trusted:
            return MotionEstimate(0.0, 0, False, ())

        estimate = min(median(value for _, value in trusted), hard_cap)
        confidence = min(3, len(trusted) + (1 if cadence is not None else 0))
        return MotionEstimate(
            distance_m=estimate,
            confidence=confidence,
            stationary=False,
            sources=tuple(name for name, _ in trusted),
        )

    def _decay_ema(self, observation: Observation) -> None:
        dt = max(observation.dt, 0.0)
        factor = math.exp(-dt / 2.5)
        self.ema_speed_mps *= factor
        self.ema_confidence = max(0, self.ema_confidence - 1)
        if self.ema_speed_mps < 0.08:
            self.ema_speed_mps = 0.0

    def _update_ema(self, credit: float, observation: Observation, supported: bool) -> None:
        if credit <= 0 or not supported:
            if self._stationary_votes(observation) >= 2:
                self._decay_ema(observation)
            return
        speed = credit / max(observation.dt, 1e-3)
        if self.ema_confidence == 0:
            self.ema_speed_mps = speed
        else:
            self.ema_speed_mps = (
                (1.0 - self.ema_alpha) * self.ema_speed_mps + self.ema_alpha * speed
            )
        self.ema_confidence = min(20, self.ema_confidence + 1)

    def _apply_credit(self, meters: float, observation: Observation) -> float:
        credit = min(max(meters, 0.0), self._hard_cap(observation))
        before = self.s
        if self.length is None:
            self.s += self.direction * credit
            pose_delta = abs(self.s - before)
        else:
            self.s = self._normalize(self.s + self.direction * credit)
            pose_delta = (
                forward_arc(before, self.s, self.length)
                if self.direction > 0
                else backward_arc(before, self.s, self.length)
            )
        self.credited_m += credit
        self.max_tick_credit_m = max(self.max_tick_credit_m, credit)

        if credit < -1e-9:
            self.invariant_failures.append("negative credit")
        if credit > self._hard_cap(observation) + 1e-9:
            self.invariant_failures.append("credit exceeds hard kinematic cap")
        if abs(pose_delta - credit) > 1e-6:
            self.invariant_failures.append("accepted pose delta differs from credit")
        return credit

    def _process_forward_observation(
        self,
        observation: Observation,
        *,
        allow_dead_reckoning: bool,
    ) -> float:
        candidate = self._normalize(observation.candidate_s)
        signed = self._signed(candidate)
        motion = self._motion_estimate(observation)

        if observation.lateral_distance_m > self.max_lateral_m:
            self._update_ema(0.0, observation, supported=False)
            return 0.0

        if motion.stationary:
            self._update_ema(0.0, observation, supported=False)
            return 0.0

        expected = motion.distance_m
        if expected <= 0 and self.ema_confidence >= 5:
            expected = min(self.ema_speed_mps * observation.dt, self._hard_cap(observation))

        jump_threshold = max(
            6.0,
            self.jump_factor * max(expected, 1.0),
            self._hard_cap(observation) + min(observation.accuracy * 0.25, 5.0),
        )
        is_jump = signed > jump_threshold

        if is_jump:
            self.rejected_jump_count += 1
            # Никакой EMA-only дистанции: нужен независимый motion estimate.
            if motion.confidence >= 2:
                self.raw_slide_count += 1
                credit = self._apply_credit(motion.distance_m, observation)
                self._update_ema(credit, observation, supported=True)
                return credit
            self._update_ema(0.0, observation, supported=False)
            return 0.0

        deadband = max(0.25, min(1.5, observation.accuracy * 0.05))
        if signed <= deadband:
            cadence_supported = "cadence" in motion.sources
            if (
                motion.confidence >= 2
                and (allow_dead_reckoning or cadence_supported)
            ):
                credit = self._apply_credit(motion.distance_m, observation)
                self._update_ema(credit, observation, supported=True)
                return credit
            self._update_ema(0.0, observation, supported=False)
            return 0.0

        # Нормальная локальная геометрия. Candidate определяет цель, motion — допустимый бюджет.
        if motion.confidence >= 1:
            budget = min(
                self._hard_cap(observation),
                max(motion.distance_m * 1.45 + 0.25, deadband),
            )
        else:
            budget = min(self._hard_cap(observation), signed)
        credit = self._apply_credit(min(signed, budget), observation)
        self._update_ema(
            credit,
            observation,
            supported=(motion.confidence > 0 or signed <= self._hard_cap(observation)),
        )
        return credit

    def _reverse_metrics(self, buffer: list[Observation]) -> tuple[list[float], float, float]:
        backs = [
            -self._signed_from(self.pending_pose_s, self._normalize(obs.candidate_s))
            for obs in buffer
        ]
        span = max(backs[-1] - backs[0], 0.0) if backs else 0.0
        duration = sum(max(obs.dt, 0.0) for obs in buffer)
        return backs, span, duration

    def _evaluate_pending(self) -> PendingDecision:
        buffer = self.pending
        if not buffer:
            return PendingDecision.DISCARD

        forward_threshold = self._forward_confirmation(buffer[-1].accuracy)
        signed_from_start = [
            self._signed_from(self.pending_pose_s, self._normalize(obs.candidate_s))
            for obs in buffer
        ]

        # Две последние точки уверенно вернулись вперёд — backward-гипотеза опровергнута.
        if (
            len(buffer) >= 2
            and signed_from_start[-1] > forward_threshold
            and signed_from_start[-2] > forward_threshold * 0.5
        ):
            return PendingDecision.FORWARD

        if len(buffer) >= self.reverse_min_samples:
            backs, span, duration = self._reverse_metrics(buffer)
            rear_threshold = self._rear_deadband(buffer[-1].accuracy)
            enough_behind = all(value > rear_threshold for value in backs[-3:])
            diffs = [right - left for left, right in zip(backs, backs[1:])]
            monotonic_share = (
                sum(delta > 0.12 for delta in diffs) / len(diffs) if diffs else 0.0
            )
            required_span = max(
                self.reverse_min_span_m,
                self.reverse_accuracy_factor
                * median(obs.accuracy for obs in buffer[-5:]),
            )
            speed_ok = duration > 0 and span / duration <= self.vmax_mps * 1.2
            estimates = [self._motion_estimate(obs) for obs in buffer]
            motion_supported = sum(
                estimate.confidence >= 2
                and not estimate.stationary
                and estimate.distance_m > 0.12
                for estimate in estimates
            ) >= max(2, len(buffer) // 2)

            if (
                enough_behind
                and monotonic_share >= 0.70
                and span >= required_span
                and speed_ok
                and motion_supported
            ):
                return PendingDecision.REVERSE

        total_seconds = sum(max(obs.dt, 0.0) for obs in buffer)
        if (
            len(buffer) >= self.max_pending_samples
            or total_seconds >= self.max_pending_seconds
        ):
            # Timeout не доказывает reverse. Forward replay допустим только при движении,
            # подтверждённом независимыми источниками хотя бы в половине буфера.
            estimates = [self._motion_estimate(obs) for obs in buffer]
            trusted_motion = sum(
                estimate.confidence >= 2
                and not estimate.stationary
                and estimate.distance_m > 0.12
                for estimate in estimates
            )
            if trusted_motion >= max(2, len(buffer) // 2):
                return PendingDecision.FORWARD
            return PendingDecision.DISCARD

        return PendingDecision.AMBIGUOUS

    def _replay_pending(self, decision: PendingDecision) -> float:
        buffer = list(self.pending)
        self.pending.clear()
        total = 0.0
        if decision is PendingDecision.REVERSE:
            self.direction *= -1
            self.reverse_count += 1

        if decision is PendingDecision.DISCARD:
            for observation in buffer:
                self._update_ema(0.0, observation, supported=False)
            return 0.0

        for observation in buffer:
            total += self._process_forward_observation(
                observation,
                allow_dead_reckoning=True,
            )
        return total

    def tick(self, observation: Observation) -> float:
        candidate = self._normalize(observation.candidate_s)
        normalized = Observation(
            candidate_s=candidate,
            raw_delta=observation.raw_delta,
            dt=observation.dt,
            accuracy=observation.accuracy,
            location_speed_mps=observation.location_speed_mps,
            cadence_delta=observation.cadence_delta,
            lateral_distance_m=observation.lateral_distance_m,
        )

        if not self.locked:
            self.s = candidate
            self.locked = True
            return 0.0

        signed = self._signed(candidate)

        # Даже небольшой содержательный кандидат назад сначала неоднозначен.
        # Если сразу сделать cadence/raw-slide вперёд, а через два тика подтвердить
        # reverse, один и тот же участок будет начислен в обе стороны.
        if not self.pending and signed < -0.12:
            self.pending_pose_s = self.s
            self.pending.append(normalized)
            self.ambiguous_count += 1
            return 0.0

        if self.pending:
            self.pending.append(normalized)
            decision = self._evaluate_pending()
            if decision is PendingDecision.AMBIGUOUS:
                self.ambiguous_count += 1
                return 0.0
            return self._replay_pending(decision)

        return self._process_forward_observation(
            normalized,
            allow_dead_reckoning=False,
        )

    def finish(self) -> float:
        """Без новых наблюдений неоднозначный хвост не начисляется."""
        if self.pending:
            self._replay_pending(PendingDecision.DISCARD)
        if self.direction not in (-1, 1):
            self.invariant_failures.append("direction must be ±1")
        return self.credited_m


# =============================================================================
# Харнес
# =============================================================================


def percent_error(actual: float, expected: float) -> float:
    if expected <= 1e-9:
        return 0.0 if actual <= 1e-9 else math.inf
    return 100.0 * (actual - expected) / expected


def execute_case(
    name: str,
    kind: CaseKind,
    ticks: list[Tick],
    predicate: Callable[[float, StickyPoseEngine], tuple[bool, str]],
    *,
    length: Optional[float] = None,
) -> CaseResult:
    engine = StickyPoseEngine(length=length)
    approx_current = ApproxCurrentEngine(length=length)
    truth = 0.0
    for index, tick in enumerate(ticks):
        if index > 0:
            truth += abs(tick.true_advance)
        engine.tick(tick.observation)
        approx_current.tick(tick.observation)
    engine.finish()
    passed, detail = predicate(truth, engine)
    if engine.invariant_failures:
        passed = False
        detail += f"; invariants={engine.invariant_failures}"
    return CaseResult(
        name=name,
        kind=kind,
        true_m=truth,
        approx_current_m=approx_current.credited_m,
        credited_m=engine.credited_m,
        error_pct=percent_error(engine.credited_m, truth),
        reverse_count=engine.reverse_count,
        max_tick_credit=engine.max_tick_credit_m,
        passed=passed,
        detail=detail,
    )


def moving_observation(
    rng: random.Random,
    candidate_s: float,
    advance: float,
    *,
    dt: float = 1.0,
    accuracy: float = 5.0,
    candidate_noise: float = 0.35,
    raw_factor: tuple[float, float] = (0.92, 1.08),
    cadence_factor: tuple[float, float] = (0.95, 1.05),
    speed_factor: tuple[float, float] = (0.95, 1.05),
    lateral_distance_m: float = 0.0,
) -> Observation:
    speed = advance / max(dt, 1e-3)
    return Observation(
        candidate_s=candidate_s + rng.uniform(-candidate_noise, candidate_noise),
        raw_delta=max(0.0, advance * rng.uniform(*raw_factor)),
        dt=dt,
        accuracy=accuracy,
        location_speed_mps=max(0.0, speed * rng.uniform(*speed_factor)),
        cadence_delta=max(0.0, advance * rng.uniform(*cadence_factor)),
        lateral_distance_m=lateral_distance_m,
    )


def stationary_observation(
    rng: random.Random,
    candidate_s: float,
    *,
    candidate_noise: float = 2.5,
    raw_range: tuple[float, float] = (0.3, 1.2),
    accuracy: float = 5.0,
) -> Observation:
    return Observation(
        candidate_s=candidate_s + rng.uniform(-candidate_noise, candidate_noise),
        raw_delta=rng.uniform(*raw_range),
        dt=1.0,
        accuracy=accuracy,
        location_speed_mps=rng.uniform(0.0, 0.12),
        cadence_delta=0.0,
    )


def accuracy_predicate(
    max_abs_error_pct: float,
    *,
    reverse_count: Optional[int] = None,
) -> Callable[[float, StickyPoseEngine], tuple[bool, str]]:
    def check(truth: float, engine: StickyPoseEngine) -> tuple[bool, str]:
        error = percent_error(engine.credited_m, truth)
        reverse_ok = reverse_count is None or engine.reverse_count == reverse_count
        ok = abs(error) <= max_abs_error_pct and reverse_ok
        return (
            ok,
            f"err={error:+.2f}% reverse={engine.reverse_count} "
            f"jumps={engine.rejected_jump_count}",
        )

    return check


# =============================================================================
# Сценарии
# =============================================================================


def case_clean_line(rng: random.Random) -> CaseResult:
    s = 0.0
    ticks: list[Tick] = []
    for _ in range(300):
        advance = rng.uniform(1.6, 2.4)
        s += advance
        ticks.append(Tick(moving_observation(rng, s, advance), advance))
    return execute_case(
        "clean_line",
        CaseKind.ACCURACY,
        ticks,
        accuracy_predicate(2.0, reverse_count=0),
    )


def case_stationary_symmetric(rng: random.Random) -> CaseResult:
    ticks = [
        Tick(stationary_observation(rng, 100.0, candidate_noise=3.0), 0.0)
        for _ in range(300)
    ]

    def check(_: float, engine: StickyPoseEngine) -> tuple[bool, str]:
        ok = engine.credited_m <= 3.0 and engine.reverse_count == 0
        return ok, f"credited={engine.credited_m:.2f} reverse={engine.reverse_count}"

    return execute_case("stationary_symmetric", CaseKind.SAFETY, ticks, check)


def case_stationary_back_back_forward(rng: random.Random) -> CaseResult:
    ticks: list[Tick] = []
    for _ in range(60):
        for offset in (-2.0, -2.5, 1.5):
            observation = stationary_observation(rng, 100.0, candidate_noise=0.0)
            observation = Observation(
                candidate_s=100.0 + offset,
                raw_delta=observation.raw_delta,
                dt=1.0,
                accuracy=5.0,
                location_speed_mps=0.05,
                cadence_delta=0.0,
            )
            ticks.append(Tick(observation, 0.0))

    def check(_: float, engine: StickyPoseEngine) -> tuple[bool, str]:
        ok = engine.credited_m <= 2.0 and engine.reverse_count == 0
        return ok, f"credited={engine.credited_m:.2f} reverse={engine.reverse_count}"

    return execute_case("stationary_back_back_forward", CaseKind.SAFETY, ticks, check)


def case_small_back_then_forward(rng: random.Random) -> CaseResult:
    s = 0.0
    ticks: list[Tick] = []
    for _ in range(30):
        s += 2.0
        ticks.append(Tick(moving_observation(rng, s, 2.0), 2.0))
    # Бег продолжается, но одна проекция ошибочно оказывается на 5 м позади.
    s += 2.0
    bad = moving_observation(rng, s - 7.0, 2.0, candidate_noise=0.0)
    ticks.append(Tick(bad, 2.0))
    for _ in range(30):
        s += 2.0
        ticks.append(Tick(moving_observation(rng, s, 2.0), 2.0))
    return execute_case(
        "small_back_then_forward",
        CaseKind.STATE,
        ticks,
        accuracy_predicate(3.0, reverse_count=0),
    )


def case_identical_points_behind(rng: random.Random) -> CaseResult:
    s = 0.0
    ticks: list[Tick] = []
    for _ in range(20):
        s += 2.0
        ticks.append(Tick(moving_observation(rng, s, 2.0), 2.0))
    for _ in range(6):
        ticks.append(
            Tick(
                Observation(
                    candidate_s=s - 50.0,
                    raw_delta=0.6,
                    accuracy=5.0,
                    location_speed_mps=0.05,
                    cadence_delta=0.0,
                ),
                0.0,
            )
        )
    for _ in range(20):
        s += 2.0
        ticks.append(Tick(moving_observation(rng, s, 2.0), 2.0))
    return execute_case(
        "identical_points_behind",
        CaseKind.STATE,
        ticks,
        accuracy_predicate(3.0, reverse_count=0),
    )


def make_uturn_case(
    name: str,
    rng: random.Random,
    reverse_advance: float,
    accuracy: float,
) -> CaseResult:
    s = 0.0
    ticks: list[Tick] = []
    for _ in range(30):
        s += 2.0
        ticks.append(Tick(moving_observation(rng, s, 2.0), 2.0))
    reverse_ticks = max(20, math.ceil(40.0 / reverse_advance))
    for _ in range(reverse_ticks):
        s -= reverse_advance
        ticks.append(
            Tick(
                moving_observation(
                    rng,
                    s,
                    reverse_advance,
                    accuracy=accuracy,
                    candidate_noise=min(0.3, reverse_advance * 0.2),
                ),
                reverse_advance,
            )
        )
    return execute_case(
        name,
        CaseKind.STATE,
        ticks,
        accuracy_predicate(4.0, reverse_count=1),
    )


def case_uturn_normal(rng: random.Random) -> CaseResult:
    return make_uturn_case("uturn_normal", rng, 2.0, 5.0)


def case_uturn_slow(rng: random.Random) -> CaseResult:
    return make_uturn_case("uturn_slow_0_5mps", rng, 0.5, 5.0)


def case_uturn_poor_accuracy(rng: random.Random) -> CaseResult:
    return make_uturn_case("uturn_accuracy_20m", rng, 2.0, 20.0)


def case_false_reverse_stationary(rng: random.Random) -> CaseResult:
    ticks: list[Tick] = []
    for i in range(12):
        # Candidate монотонно идёт назад, но cadence и speed подтверждают стоянку.
        ticks.append(
            Tick(
                Observation(
                    candidate_s=100.0 - (i + 1) * 2.0,
                    raw_delta=rng.uniform(2.0, 5.0),
                    accuracy=8.0,
                    location_speed_mps=0.05,
                    cadence_delta=0.0,
                ),
                0.0,
            )
        )

    def check(_: float, engine: StickyPoseEngine) -> tuple[bool, str]:
        ok = engine.credited_m == 0.0 and engine.reverse_count == 0
        return ok, f"credited={engine.credited_m:.2f} reverse={engine.reverse_count}"

    return execute_case("false_reverse_stationary", CaseKind.SAFETY, ticks, check)


def case_stationary_phantom_bad_raw(rng: random.Random) -> CaseResult:
    ticks: list[Tick] = []
    for _ in range(15):
        ticks.append(Tick(stationary_observation(rng, 0.0), 0.0))
    for _ in range(30):
        ticks.append(
            Tick(
                Observation(
                    candidate_s=500.0,
                    raw_delta=15.0,
                    accuracy=8.0,
                    location_speed_mps=15.0,
                    cadence_delta=0.0,
                ),
                0.0,
            )
        )

    def check(_: float, engine: StickyPoseEngine) -> tuple[bool, str]:
        ok = engine.credited_m <= 1.0 and engine.reverse_count == 0
        return ok, f"credited={engine.credited_m:.2f}"

    return execute_case("stationary_phantom_bad_raw", CaseKind.SAFETY, ticks, check)


def case_moving_phantom_raw_zero(rng: random.Random) -> CaseResult:
    s = 0.0
    ticks: list[Tick] = []
    for _ in range(20):
        s += 1.5
        ticks.append(Tick(moving_observation(rng, s, 1.5), 1.5))
    for _ in range(60):
        s += 1.5
        ticks.append(
            Tick(
                Observation(
                    candidate_s=s + 500.0,
                    raw_delta=0.0,
                    accuracy=8.0,
                    location_speed_mps=0.0,
                    cadence_delta=1.5,
                ),
                1.5,
            )
        )
    return execute_case(
        "moving_phantom_raw_zero",
        CaseKind.ACCURACY,
        ticks,
        accuracy_predicate(3.0, reverse_count=0),
    )


def case_moving_phantom_corrupt_raw(rng: random.Random) -> CaseResult:
    s = 0.0
    ticks: list[Tick] = []
    for _ in range(20):
        s += 1.5
        ticks.append(Tick(moving_observation(rng, s, 1.5), 1.5))
    for _ in range(60):
        s += 1.5
        ticks.append(
            Tick(
                Observation(
                    candidate_s=s + 500.0,
                    raw_delta=15.0,
                    accuracy=8.0,
                    location_speed_mps=15.0,
                    cadence_delta=1.5,
                ),
                1.5,
            )
        )
    return execute_case(
        "moving_phantom_corrupt_raw",
        CaseKind.ACCURACY,
        ticks,
        accuracy_predicate(3.0, reverse_count=0),
    )


def case_short_slow_forward(rng: random.Random) -> CaseResult:
    s = 0.0
    ticks: list[Tick] = []
    for _ in range(30):
        s += 0.5
        ticks.append(
            Tick(
                moving_observation(
                    rng,
                    s,
                    0.5,
                    candidate_noise=0.08,
                    accuracy=4.0,
                ),
                0.5,
            )
        )
    return execute_case(
        "short_forward_0_5mps",
        CaseKind.ACCURACY,
        ticks,
        accuracy_predicate(5.0, reverse_count=0),
    )


def case_uneven_dt(rng: random.Random) -> CaseResult:
    s = 0.0
    ticks: list[Tick] = []
    for dt in [0.25, 0.5, 1.0, 3.0, 10.0] * 30:
        advance = 2.3 * dt
        s += advance
        ticks.append(
            Tick(
                moving_observation(
                    rng,
                    s,
                    advance,
                    dt=dt,
                    candidate_noise=min(0.5, advance * 0.2),
                ),
                advance,
            )
        )
    return execute_case(
        "uneven_dt",
        CaseKind.ACCURACY,
        ticks,
        accuracy_predicate(3.0, reverse_count=0),
    )


def case_stop_go_intervals(rng: random.Random) -> CaseResult:
    s = 0.0
    ticks: list[Tick] = []
    for _ in range(5):
        for _ in range(30):
            s += 3.2
            ticks.append(Tick(moving_observation(rng, s, 3.2), 3.2))
        for _ in range(20):
            ticks.append(Tick(stationary_observation(rng, s), 0.0))
    return execute_case(
        "stop_go_intervals",
        CaseKind.ACCURACY,
        ticks,
        accuracy_predicate(3.0, reverse_count=0),
    )


def case_closed_ring(rng: random.Random) -> CaseResult:
    length = 400.0
    s = 0.0
    ticks: list[Tick] = []
    for _ in range(500):
        advance = 2.5
        s = wrap(s + advance, length)
        ticks.append(
            Tick(
                moving_observation(rng, s, advance, candidate_noise=0.4),
                advance,
            )
        )
    return execute_case(
        "closed_ring_400",
        CaseKind.ACCURACY,
        ticks,
        accuracy_predicate(2.0, reverse_count=0),
        length=length,
    )


def case_ring_reverse_across_wrap(rng: random.Random) -> CaseResult:
    length = 100.0
    s = 95.0
    ticks: list[Tick] = []
    for _ in range(12):
        s = wrap(s + 2.0, length)
        ticks.append(Tick(moving_observation(rng, s, 2.0), 2.0))
    for _ in range(20):
        s = wrap(s - 2.0, length)
        ticks.append(Tick(moving_observation(rng, s, 2.0), 2.0))
    return execute_case(
        "ring_reverse_across_wrap",
        CaseKind.STATE,
        ticks,
        accuracy_predicate(5.0, reverse_count=1),
        length=length,
    )


def case_fork_ambiguous(rng: random.Random) -> CaseResult:
    s = 0.0
    ticks: list[Tick] = []
    for index in range(180):
        advance = 2.0
        s += advance
        candidate = s + 80.0 if index > 20 and index % 6 == 0 else s
        ticks.append(
            Tick(
                moving_observation(rng, candidate, advance, candidate_noise=0.25),
                advance,
            )
        )
    return execute_case(
        "fork_ambiguous_1d",
        CaseKind.ACCURACY,
        ticks,
        accuracy_predicate(3.0, reverse_count=0),
    )


def case_offroute(rng: random.Random) -> CaseResult:
    s = 0.0
    ticks: list[Tick] = []
    for _ in range(40):
        s += 2.0
        ticks.append(Tick(moving_observation(rng, s, 2.0), 2.0))
    for _ in range(80):
        ticks.append(
            Tick(
                Observation(
                    candidate_s=s,
                    raw_delta=2.0,
                    accuracy=4.0,
                    location_speed_mps=2.0,
                    cadence_delta=2.0,
                    lateral_distance_m=100.0,
                ),
                2.0,
            )
        )

    def check(_: float, engine: StickyPoseEngine) -> tuple[bool, str]:
        ok = 75.0 <= engine.credited_m <= 82.0
        return ok, f"rail_credit={engine.credited_m:.2f} expected~78"

    return execute_case("offroute_good_accuracy", CaseKind.SAFETY, ticks, check)


def case_gps_dropout(rng: random.Random) -> CaseResult:
    s = 0.0
    ticks: list[Tick] = []
    for _ in range(40):
        s += 2.0
        ticks.append(Tick(moving_observation(rng, s, 2.0), 2.0))
    frozen = s
    for _ in range(30):
        s += 2.0
        ticks.append(
            Tick(
                Observation(
                    candidate_s=frozen,
                    raw_delta=0.0,
                    accuracy=50.0,
                    location_speed_mps=None,
                    cadence_delta=2.0,
                ),
                2.0,
            )
        )
    for _ in range(40):
        s += 2.0
        ticks.append(Tick(moving_observation(rng, s, 2.0), 2.0))

    def check(truth: float, engine: StickyPoseEngine) -> tuple[bool, str]:
        # Safety: не перелететь truth; accuracy: cadence должна удержать ошибку в 5%.
        error = percent_error(engine.credited_m, truth)
        ok = -5.0 <= error <= 2.0
        return ok, f"err={error:+.2f}%"

    return execute_case("gps_dropout_with_cadence", CaseKind.ACCURACY, ticks, check)


def make_lead_stress(
    name: str,
    rng: random.Random,
    jumps: dict[int, float],
    n: int,
) -> CaseResult:
    s = 0.0
    lead = 0.0
    ticks: list[Tick] = []
    advance = 1.55
    for index in range(n):
        s += advance
        lead += jumps.get(index, 0.0)
        raw = advance * rng.uniform(0.85, 1.15)
        if index % 37 == 0:
            raw *= rng.uniform(3.0, 6.0)
        ticks.append(
            Tick(
                Observation(
                    candidate_s=s + lead + rng.uniform(-1.0, 1.0),
                    raw_delta=raw,
                    accuracy=8.0,
                    location_speed_mps=advance * rng.uniform(0.9, 1.1),
                    cadence_delta=advance * rng.uniform(0.95, 1.05),
                ),
                advance,
            )
        )
    return execute_case(
        name,
        CaseKind.ACCURACY,
        ticks,
        accuracy_predicate(3.0, reverse_count=0),
    )


def case_field_30jul(rng: random.Random) -> CaseResult:
    return make_lead_stress(
        "field_30jul_two_jumps",
        rng,
        {400: 1249.0, 1100: 1230.0},
        1800,
    )


def case_park_lead(rng: random.Random) -> CaseResult:
    return make_lead_stress(
        "park_lead_stress",
        rng,
        {100: 300.0, 350: 280.0, 700: 350.0, 1000: 270.0},
        1400,
    )


CASE_FACTORIES: tuple[Callable[[random.Random], CaseResult], ...] = (
    case_clean_line,
    case_stationary_symmetric,
    case_stationary_back_back_forward,
    case_small_back_then_forward,
    case_identical_points_behind,
    case_uturn_normal,
    case_uturn_slow,
    case_uturn_poor_accuracy,
    case_false_reverse_stationary,
    case_stationary_phantom_bad_raw,
    case_moving_phantom_raw_zero,
    case_moving_phantom_corrupt_raw,
    case_short_slow_forward,
    case_uneven_dt,
    case_stop_go_intervals,
    case_closed_ring,
    case_ring_reverse_across_wrap,
    case_fork_ambiguous,
    case_offroute,
    case_gps_dropout,
    case_field_30jul,
    case_park_lead,
)


# =============================================================================
# Monte Carlo и CLI
# =============================================================================


def run_suite(seed: int) -> list[CaseResult]:
    return [factory(random.Random(seed * 1009 + index * 7919)) for index, factory in enumerate(CASE_FACTORIES)]


def print_results(results: list[CaseResult]) -> None:
    print("=" * 128)
    print(
        f"{'case':<34} {'kind':<9} {'true':>8} {'approx':>9} {'sticky':>8} "
        f"{'error':>9} {'rev':>4} {'maxTick':>8}  result"
    )
    print("=" * 128)
    for result in results:
        error_text = (
            "n/a" if not math.isfinite(result.error_pct) else f"{result.error_pct:+.2f}%"
        )
        mark = "PASS" if result.passed else "FAIL"
        print(
            f"{result.name:<34} {result.kind.value:<9} "
            f"{result.true_m:8.1f} {result.approx_current_m:9.1f} "
            f"{result.credited_m:8.1f} "
            f"{error_text:>9} {result.reverse_count:4d} "
            f"{result.max_tick_credit:8.2f}  {mark}  {result.detail}"
        )
    print("=" * 128)


def monte_carlo(seed_count: int) -> tuple[int, list[str]]:
    failures: list[str] = []
    total = 0
    for seed in range(seed_count):
        for result in run_suite(seed):
            total += 1
            if not result.passed:
                failures.append(
                    f"seed={seed} case={result.name}: {result.detail}"
                )
    return total, failures


def replay_recorded_rail_ticks(path: str) -> tuple[bool, str]:
    """
    Replay диагностического JSONL Stayer.

    В старом формате нет отдельного candidateS. Для ненулевого
    pathDeltaBeforeCap он восстанавливается как sBefore ± pathDelta по
    directionAfter. Для SAME_EDGE_BACKWARD_ZERO точная проекция потеряна,
    поэтому используется sAfter. Это делает replay консервативным и не
    притворяется полным воспроизведением RailMatcher.
    """
    engine = StickyPoseEngine()
    raw_total = 0.0
    recorded_total = 0.0
    rows = 0

    try:
        source = open(path, "r", encoding="utf-8")
    except OSError as error:
        return False, f"cannot open rail ticks: {error}"

    with source:
        for line_number, line in enumerate(source, start=1):
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                return False, f"invalid JSON at line {line_number}: {error}"
            if record.get("type") == "session":
                continue

            rows += 1
            path_delta = float(record.get("pathDeltaBeforeCap") or 0.0)
            s_before = float(record.get("sBefore") or 0.0)
            direction = record.get("directionAfter")
            if path_delta > 0 and direction == "towardEnd":
                candidate = s_before + path_delta
            elif path_delta > 0 and direction == "towardStart":
                candidate = s_before - path_delta
            else:
                candidate = float(record.get("sAfter") or s_before)

            raw_delta = float(record.get("rawDelta") or 0.0)
            location_speed = record.get("locationSpeed")
            observation = Observation(
                candidate_s=candidate,
                raw_delta=raw_delta,
                dt=max(float(record.get("dtMs") or 0.0) / 1000.0, 1e-3),
                accuracy=float(record.get("accuracy") or 5.0),
                location_speed_mps=(
                    float(location_speed) if location_speed is not None else None
                ),
                cadence_delta=None,
            )
            engine.tick(observation)
            raw_total += raw_delta
            recorded_total += float(record.get("creditedDelta") or 0.0)

    engine.finish()
    if rows == 0:
        return False, "rail ticks contain no observations"
    if engine.invariant_failures:
        return False, f"replay invariants failed: {engine.invariant_failures}"

    raw_error = percent_error(engine.credited_m, raw_total)
    recorded_error = percent_error(recorded_total, raw_total)
    detail = (
        f"Recorded trace: rows={rows}, rawGPS={raw_total:.1f} m, "
        f"recorded={recorded_total:.1f} m ({recorded_error:+.1f}% vs raw), "
        f"sticky={engine.credited_m:.1f} m ({raw_error:+.1f}% vs raw), "
        f"reverse={engine.reverse_count}, rejectedJumps={engine.rejected_jump_count}"
    )
    # rawGPS — лишь reference, не абсолютная истина. Широкий коридор нужен,
    # чтобы диагностический replay не выдавал точность симуляции за геодезию.
    return abs(raw_error) <= 8.0, detail


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--seeds",
        type=int,
        default=100,
        help="Количество детерминированных Monte Carlo seed (default: 100)",
    )
    parser.add_argument(
        "--rail-ticks",
        metavar="PATH",
        help="Дополнительно replay реального stayer_rail_ticks_*.jsonl",
    )
    return parser.parse_args()


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    args = parse_args()
    representative = run_suite(0)
    print_results(representative)

    total, failures = monte_carlo(max(args.seeds, 1))
    print(f"Monte Carlo: {total} case-runs across {max(args.seeds, 1)} seeds")
    if failures:
        print(f"FAILED: {len(failures)}")
        for failure in failures[:30]:
            print(f"  - {failure}")
        if len(failures) > 30:
            print(f"  ... and {len(failures) - 30} more")
        return 1

    print("ALL REQUIRED CHECKS PASSED")
    if args.rail_ticks:
        replay_ok, replay_detail = replay_recorded_rail_ticks(args.rail_ticks)
        print(replay_detail)
        if not replay_ok:
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
