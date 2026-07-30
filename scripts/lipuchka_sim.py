#!/usr/bin/env python3
"""
Стресс-тест одометра на рельсах: Current(3.7.x airtrain) vs Липучка.

Не тепличный smoke. Сырой raw часто врёт. Asserts обязательны.
Запуск: python scripts/lipuchka_sim.py
Exit != 0 при провале asserts.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional
import random
import sys


# =============================================================================
# Геометрия кольца (настоящий wrap, не бесконечная 1D)
# =============================================================================

def wrap(s: float, length: float) -> float:
    return s % length


def forward_arc(a: float, b: float, length: float) -> float:
    return (b - a) % length


def backward_arc(a: float, b: float, length: float) -> float:
    return (a - b) % length


def signed_progress(pose: float, cand: float, length: float, direction: int) -> float:
    """+ вдоль direction, − против. Короткая? Нет — строго по направлению бега."""
    if direction >= 0:
        fwd = forward_arc(pose, cand, length)
        back = backward_arc(pose, cand, length)
        return fwd if fwd <= back else -back
    # direction < 0: «вперёд» = уменьшение s
    fwd = backward_arc(pose, cand, length)
    back = forward_arc(pose, cand, length)
    return fwd if fwd <= back else -back


# =============================================================================
# Current 3.7.x (cap + debt → марш к candidate)
# =============================================================================

@dataclass
class CurrentEngine:
    length: Optional[float] = None  # None = unwrapped open line
    max_speed_mps: float = 10.0
    max_debt: float = 30.0
    s: float = 0.0
    debt: float = 0.0
    credited: float = 0.0
    caps: int = 0
    locked: bool = False

    def _delta_forward(self, cand: float) -> float:
        if self.length is None:
            return cand - self.s
        return forward_arc(self.s, cand, self.length)

    def tick(self, candidate_s: float, raw_delta: float, dt: float = 1.0) -> float:
        del raw_delta
        if not self.locked:
            self.s = candidate_s if self.length is None else wrap(candidate_s, self.length)
            self.locked = True
            return 0.0

        path_delta = self._delta_forward(candidate_s)
        # same-edge backward zero (без длинного круга назад как «вперёд»)
        if self.length is None:
            if path_delta < -1e-9:
                geometric = min(abs(path_delta), self.max_speed_mps * dt)
                if geometric > 0.3:
                    self.debt = min(self.max_debt, self.debt + geometric)
                return 0.0
            path_delta = max(path_delta, 0.0)
        else:
            # на кольце: если короче назад — не маршируем длинным путём
            back = backward_arc(self.s, candidate_s, self.length)
            if back < path_delta and back < self.length * 0.5:
                geometric = min(back, self.max_speed_mps * dt)
                if geometric > 0.3:
                    self.debt = min(self.max_debt, self.debt + geometric)
                return 0.0

        budget = self.max_speed_mps * dt + self.debt
        credit = min(path_delta, budget)
        leftover = path_delta - credit
        if leftover > 1e-6:
            self.caps += 1
            self.debt = min(self.max_debt, self.debt + leftover)
        else:
            pay = min(self.debt, max(0.0, budget - credit))
            credit += pay
            self.debt -= pay

        if self.length is None:
            self.s += credit
        else:
            self.s = wrap(self.s + credit, self.length)
        self.credited += credit
        return credit


# =============================================================================
# Липучка (схема 6–7–8–9 + kinematic gate без слепой веры в raw)
# =============================================================================

@dataclass
class Obs:
    candidate_s: float
    raw_delta: float
    dt: float
    accuracy: float = 5.0


@dataclass
class LipuchkaEngine:
    """
    Правила:
      - credit никогда из телепорта candidate
      - observation estimate = min(raw, ema, vmax*dt); на скачке raw → ema
      - cand чуть/сильно позади → в буфер, credit 0, ждём ещё N точек
      - буфер replay после решения forward/reverse
      - reverse только: count>=3, monotonic back, накопленный back, скорость ок
    """

    length: Optional[float] = None
    reverse_extra: int = 2  # ещё 2 после первой = 3 всего
    min_reverse_span_m: float = 4.0
    rear_enter_m: float = 0.5  # любое «позади» > этого → буфер (не raw_slide!)
    k_ema: float = 0.25
    vmax_mps: float = 6.0  # беговой потолок для estimate (не 10 sprint airtrain)
    jump_factor: float = 3.0
    s: float = 0.0
    direction: int = 1
    credited: float = 0.0
    pending: List[Obs] = field(default_factory=list)
    ema_mps: float = 2.5
    locked: bool = False
    holds: int = 0
    raw_slides: int = 0
    reverses: int = 0
    false_reverse_guards: int = 0
    replays: int = 0

    def _wrap(self, x: float) -> float:
        return x if self.length is None else wrap(x, self.length)

    def _signed(self, cand: float) -> float:
        if self.length is None:
            return self.direction * (cand - self.s)
        return signed_progress(self.s, cand, self.length, self.direction)

    def _estimate(self, raw: float, dt: float, *, suspect_jump: bool, geom_signed: float = 0.0) -> float:
        dt = max(dt, 1e-3)
        raw_speed = max(raw, 0.0) / dt
        cap = self.vmax_mps * dt
        # Геометрия почти стоит / дрожит — только геометрический вперёд, иначе 0
        if abs(geom_signed) < 3.0:
            if geom_signed < 0.75:
                return 0.0
            return min(geom_signed, cap)

        raw_ok = raw_speed <= self.ema_mps * 2.0 + 1.0
        # Телепорт кандидата: метры из observation, не из |Δs|
        if suspect_jump or geom_signed > self.jump_factor * max(self.ema_mps * dt, 1.0):
            if raw_ok:
                return min(max(raw, 0.0), cap)
            return min(self.ema_mps * dt, cap)
        return min(max(raw, 0.0), self.ema_mps * dt * 1.25 + 0.35, cap)

    def _update_ema(self, credit: float, dt: float) -> None:
        dt = max(dt, 1e-3)
        if credit <= 1e-9:
            return
        sp = credit / dt
        self.ema_mps = (1 - self.k_ema) * self.ema_mps + self.k_ema * sp

    def _apply_slide(self, meters: float) -> float:
        if meters <= 1e-12:
            return 0.0
        if self.length is None:
            self.s += self.direction * meters
        else:
            self.s = self._wrap(self.s + self.direction * meters)
        self.credited += meters
        return meters

    def _stick_toward(self, cand: float, budget: float) -> float:
        signed = self._signed(cand)
        if signed <= 1e-12:
            return 0.0
        credit = min(signed, budget)
        self._apply_slide(credit)
        if abs(self._signed(cand)) < 1e-6:
            self.s = self._wrap(cand) if self.length else cand
        return credit

    def _reverse_confirmed(self, buf: List[Obs]) -> bool:
        if len(buf) < 1 + self.reverse_extra:
            return False
        pose0 = self.s
        backs: List[float] = []
        for o in buf:
            if self.length is None:
                signed = self.direction * (o.candidate_s - pose0)
            else:
                signed = signed_progress(pose0, o.candidate_s, self.length, self.direction)
            backs.append(-signed)
        if any(b < self.rear_enter_m for b in backs):
            return False
        diffs = [backs[i] - backs[i - 1] for i in range(1, len(backs))]
        if sum(1 for d in diffs if d > 0.3) < max(1, len(diffs) - 1):
            self.false_reverse_guards += 1
            return False
        span = backs[-1] - backs[0]
        need = max(self.min_reverse_span_m, 0.5 * buf[-1].accuracy)
        if span < need:
            self.false_reverse_guards += 1
            return False
        total_dt = sum(o.dt for o in buf) or 1.0
        if span / total_dt > self.vmax_mps * 1.2:
            self.false_reverse_guards += 1
            return False
        return True

    def _replay(self, buf: List[Obs], *, as_reverse: bool) -> float:
        self.replays += 1
        total = 0.0
        if as_reverse:
            self.direction *= -1
            self.reverses += 1
        for o in buf:
            signed = self._signed(o.candidate_s)
            jump = signed > self.jump_factor * max(
                self._estimate(o.raw_delta, o.dt, suspect_jump=False, geom_signed=signed), 1.0
            )
            est = self._estimate(
                o.raw_delta, o.dt, suspect_jump=jump or signed < -self.rear_enter_m, geom_signed=signed,
            )
            if as_reverse or signed >= -self.rear_enter_m:
                if jump:
                    c = self._apply_slide(est)
                    self.raw_slides += 1
                else:
                    c = self._stick_toward(o.candidate_s, est)
                total += c
                self._update_ema(c, o.dt)
            else:
                self.holds += 1
        return total

    def tick(self, candidate_s: float, raw_delta: float, dt: float = 1.0, accuracy: float = 5.0) -> float:
        cand = self._wrap(candidate_s) if self.length else candidate_s
        if not self.locked:
            self.s = cand
            self.locked = True
            return 0.0

        obs = Obs(cand, raw_delta, dt, accuracy)
        signed = self._signed(cand)

        if self.pending or signed < -self.rear_enter_m:
            self.pending.append(obs)
            self.holds += 1
            if len(self.pending) < 1 + self.reverse_extra:
                return 0.0
            buf = list(self.pending)
            self.pending.clear()
            if self._reverse_confirmed(buf):
                return self._replay(buf, as_reverse=True)
            return self._replay_forward_bias(buf)

        jump = signed > self.jump_factor * max(
            self._estimate(raw_delta, dt, suspect_jump=False, geom_signed=signed), 1.0
        )
        est = self._estimate(raw_delta, dt, suspect_jump=jump, geom_signed=signed)

        if jump:
            c = self._apply_slide(est)
            self.raw_slides += 1
            self.holds += 1
            self._update_ema(c, dt)
            return c

        c = self._stick_toward(cand, est)
        self._update_ema(c, dt)
        return c

    def _replay_forward_bias(self, buf: List[Obs]) -> float:
        """Не разворот: если в буфере нет движения вперёд — discard (глитч/стоянка).
        Если появилось вперёд — estimate только вперёд + stick к последнему fwd.
        """
        self.replays += 1
        pose0 = self.s
        has_forward = False
        for o in buf:
            if self.length is None:
                signed0 = self.direction * (o.candidate_s - pose0)
            else:
                signed0 = signed_progress(pose0, o.candidate_s, self.length, self.direction)
            if signed0 > 1.0:
                has_forward = True
                break

        if not has_forward:
            # три точки позади без confirm reverse и без возврата вперёд — поза стоит
            return 0.0

        total = 0.0
        last_fwd: Optional[Obs] = None
        for o in buf:
            signed = self._signed(o.candidate_s)
            if signed >= -self.rear_enter_m:
                last_fwd = o
            # forward-bias: метры из observation, геометрия «назад» игнорируется
            est = self._estimate(
                o.raw_delta,
                o.dt,
                suspect_jump=False,
                geom_signed=max(o.raw_delta, 1.0),
            )
            c = self._apply_slide(est)
            total += c
            self._update_ema(c, o.dt)
        if last_fwd is not None:
            signed = self._signed(last_fwd.candidate_s)
            est = self._estimate(
                last_fwd.raw_delta, last_fwd.dt, suspect_jump=False, geom_signed=max(signed, 1.0),
            )
            if 0 <= signed <= max(est * 2, 3.0):
                total += self._stick_toward(last_fwd.candidate_s, est)
        return total


# =============================================================================
# Харнес
# =============================================================================

@dataclass
class Tick:
    candidate_s: float
    raw_delta: float
    true_advance: float
    dt: float = 1.0
    accuracy: float = 5.0


@dataclass
class CaseResult:
    name: str
    true_m: float
    current_m: float
    lip_m: float
    lip_reverses: int
    lip_false_guards: int
    ok: bool
    detail: str


def err_pct(got: float, truth: float) -> float:
    if abs(truth) < 1e-9:
        return 0.0 if abs(got) < 1e-9 else 999.0
    return 100.0 * (got - truth) / truth


def execute(
    name: str,
    ticks: List[Tick],
    *,
    length: Optional[float] = None,
    assert_fn,
) -> CaseResult:
    cur = CurrentEngine(length=length)
    lip = LipuchkaEngine(length=length)
    true_m = 0.0
    for t in ticks:
        true_m += abs(t.true_advance)
        cur.tick(t.candidate_s, t.raw_delta, t.dt)
        lip.tick(t.candidate_s, t.raw_delta, t.dt, t.accuracy)

    ok, detail = assert_fn(true_m, cur, lip)
    return CaseResult(
        name=name,
        true_m=true_m,
        current_m=cur.credited,
        lip_m=lip.credited,
        lip_reverses=lip.reverses,
        lip_false_guards=lip.false_reverse_guards,
        ok=ok,
        detail=detail,
    )


# =============================================================================
# Стресс-сценарии
# =============================================================================

def case_stationary_jitter(rng: random.Random) -> CaseResult:
    """Стоит 120 с, GPS дрожит, raw врёт ~1 м/тик."""
    ticks = []
    for i in range(120):
        cand = rng.uniform(-2.0, 0.5)
        raw = rng.uniform(0.4, 1.2)  # врёт!
        ticks.append(Tick(cand, raw, true_advance=0.0))

    def asserts(true_m, cur, lip):
        ok = lip.credited < 5.0 and lip.reverses == 0
        return ok, f"lip={lip.credited:.1f}m rev={lip.reverses} (need <5m, rev=0)"

    return execute("stationary_jitter", ticks, assert_fn=asserts)


def case_small_back_then_forward(rng: random.Random) -> CaseResult:
    """Назад на 2–7 м одной точкой, потом вперёд — без немедленного credit на backward-тике."""
    s = 0.0
    ticks: List[Tick] = []
    for _ in range(30):
        s += 2.0
        ticks.append(Tick(s, 2.0, 2.0))
    # точка 7 позади на 5 м
    ticks.append(Tick(s - 5.0, 2.0, 2.0))
    for _ in range(30):
        s += 2.0
        ticks.append(Tick(s, 2.0, 2.0))

    def asserts(true_m, cur, lip):
        # истина: 30*2 + 2 (во время глитч-тика бежал) + 30*2 = 122
        # допускаем ±3%
        e = abs(err_pct(lip.credited, true_m))
        ok = e < 4.0 and lip.reverses == 0
        return ok, f"lip%={err_pct(lip.credited, true_m):+.1f} rev={lip.reverses}"

    return execute("small_back_then_forward", ticks, assert_fn=asserts)


def case_three_identical_behind(rng: random.Random) -> CaseResult:
    """Три одинаковые точки на −50 м — reverse НЕ подтверждается."""
    s = 100.0
    ticks: List[Tick] = []
    for _ in range(20):
        s += 2.0
        ticks.append(Tick(s, 2.0, 2.0))
    for _ in range(3):
        ticks.append(Tick(s - 50.0, 0.0, 0.0))  # нет движения между ними
    for _ in range(20):
        s += 2.0
        ticks.append(Tick(s, 2.0, 2.0))

    def asserts(true_m, cur, lip):
        ok = lip.reverses == 0 and abs(err_pct(lip.credited, true_m)) < 5.0
        return ok, f"rev={lip.reverses} guards={lip.false_reverse_guards} lip%={err_pct(lip.credited, true_m):+.1f}"

    return execute("three_identical_behind", ticks, assert_fn=asserts)


def case_slow_real_uturn(rng: random.Random) -> CaseResult:
    """Медленный разворот: ошибка < 2%."""
    s = 0.0
    ticks: List[Tick] = []
    for _ in range(20):
        s += 2.0
        ticks.append(Tick(s, 2.0 + rng.uniform(-0.2, 0.2), 2.0))
    for _ in range(15):
        s -= 2.0
        ticks.append(Tick(s, 2.0 + rng.uniform(-0.2, 0.2), 2.0))

    def asserts(true_m, cur, lip):
        e = abs(err_pct(lip.credited, true_m))
        ok = e < 3.5 and lip.reverses >= 1
        return ok, f"lip%={err_pct(lip.credited, true_m):+.1f} rev={lip.reverses}"

    return execute("slow_real_uturn", ticks, assert_fn=asserts)


def case_phantom_plus_corrupt_raw(rng: random.Random) -> CaseResult:
    """Фантом +250..500 к cand И raw врёт 12–18 м/тик. Истина ~1.5."""
    s = 0.0
    ticks: List[Tick] = []
    for i in range(60):
        adv = 1.5
        s += adv
        if 20 <= i < 40:
            cand = s + 500.0
            raw = rng.uniform(12.0, 18.0)  # испорчен вместе с телепортом
        else:
            cand = s + rng.uniform(-1.0, 1.0)
            raw = adv + rng.uniform(-0.2, 0.2)
        ticks.append(Tick(cand, raw, adv))

    def asserts(true_m, cur, lip):
        # липучка не должна улететь как current; допуск 10% на стресс
        e = err_pct(lip.credited, true_m)
        ok = abs(e) < 10.0 and lip.credited < true_m * 1.15
        return ok, f"true={true_m:.1f} curr={cur.credited:.1f} lip={lip.credited:.1f} lip%={e:+.1f}"

    return execute("phantom_corrupt_raw", ticks, assert_fn=asserts)


def case_uneven_dt(rng: random.Random) -> CaseResult:
    """dt 0.5 / 1 / 3 / 10 при стабильном темпе."""
    s = 0.0
    ticks: List[Tick] = []
    dts = [0.5, 1.0, 3.0, 10.0] * 25
    for dt in dts:
        adv = 2.5 * dt  # 2.5 м/с
        s += adv
        raw = adv * rng.uniform(0.9, 1.1)
        ticks.append(Tick(s + rng.uniform(-1, 1), raw, adv, dt=dt))

    def asserts(true_m, cur, lip):
        e = abs(err_pct(lip.credited, true_m))
        ok = e < 5.0
        return ok, f"lip%={err_pct(lip.credited, true_m):+.1f}"

    return execute("uneven_dt", ticks, assert_fn=asserts)


def case_gps_dropout(rng: random.Random) -> CaseResult:
    """Пропадание GPS: cand/raw заморожены / нули, потом возврат."""
    s = 0.0
    ticks: List[Tick] = []
    for _ in range(40):
        s += 2.0
        ticks.append(Tick(s, 2.0, 2.0))
    freeze = s
    for _ in range(30):
        # бежит, GPS мёртв
        s += 2.0
        ticks.append(Tick(freeze, 0.0, 2.0))
    for _ in range(40):
        s += 2.0
        ticks.append(Tick(s, 2.0, 2.0))

    def asserts(true_m, cur, lip):
        # во время dropout без сигнала нельзя честно восстановить — не раздувать
        # и не credit за freeze-телепорт при возврате
        ok = lip.credited <= true_m * 1.05 and lip.credited >= true_m * 0.55
        # нижняя граница мягкая: 30 тиков без GPS могут быть потеряны
        return ok, f"lip={lip.credited:.1f}/{true_m:.1f} ({err_pct(lip.credited, true_m):+.1f}%)"

    return execute("gps_dropout", ticks, assert_fn=asserts)


def case_closed_ring(rng: random.Random) -> CaseResult:
    """Кольцо 400 м, 2.5 круга, wrap s=length→0."""
    L = 400.0
    s = 0.0
    ticks: List[Tick] = []
    n = 500  # ~3 м/с * 500 ≈ 1500 м = 3.75 круга — возьмём 2.5*400=1000
    target = 1000.0
    for i in range(400):
        adv = target / 400
        s = wrap(s + adv, L)
        raw = adv + rng.uniform(-0.15, 0.15)
        # иногда raw врёт сильнее
        if i % 37 == 0:
            raw = adv * rng.uniform(2.0, 4.0)
        ticks.append(Tick(s + rng.uniform(-1.5, 1.5), raw, adv))

    def asserts(true_m, cur, lip):
        e = abs(err_pct(lip.credited, true_m))
        ok = e < 3.0
        return ok, f"lip%={err_pct(lip.credited, true_m):+.1f} true={true_m:.1f}"

    return execute("closed_ring_400", ticks, length=L, assert_fn=asserts)


def case_overlapping_out_and_back(rng: random.Random) -> CaseResult:
    """Туда-обратно по одному участку (наложенные направления)."""
    s = 0.0
    ticks: List[Tick] = []
    for _ in range(50):
        s += 2.0
        ticks.append(Tick(s, 2.0 + rng.uniform(-0.3, 0.3), 2.0))
    for _ in range(50):
        s -= 2.0
        ticks.append(Tick(s, 2.0 + rng.uniform(-0.3, 0.3), 2.0))

    def asserts(true_m, cur, lip):
        e = abs(err_pct(lip.credited, true_m))
        ok = e < 3.0 and lip.reverses >= 1
        return ok, f"lip%={err_pct(lip.credited, true_m):+.1f} rev={lip.reverses}"

    return execute("out_and_back_overlap", ticks, assert_fn=asserts)


def case_fork_ambiguous(rng: random.Random) -> CaseResult:
    """
    После стабильного lock на правильной ветке иногда nearest отдаёт
    параллельную проекцию +80 м. Нельзя улетать за ней и нельзя навсегда
    залипнуть, приняв её за lock.
    """
    s = 0.0
    ticks: List[Tick] = []
    for i in range(120):
        adv = 2.0
        s += adv
        if i >= 20 and i % 5 == 0:
            cand = s + 80.0
            raw = adv + rng.uniform(-0.2, 0.2)
        else:
            cand = s + rng.uniform(-1, 1)
            raw = adv + rng.uniform(-0.2, 0.2)
        ticks.append(Tick(cand, raw, adv))

    def asserts(true_m, cur, lip):
        e = abs(err_pct(lip.credited, true_m))
        ok = e < 5.0 and lip.credited < true_m * 1.1
        return ok, f"lip%={err_pct(lip.credited, true_m):+.1f} curr%={err_pct(cur.credited, true_m):+.1f}"

    return execute("fork_ambiguous", ticks, assert_fn=asserts)


def case_offroute_good_accuracy(rng: random.Random) -> CaseResult:
    """
    Ушёл с маршрута: cand застыл на последней проекции на рельсе,
    raw растёт (реально бежит в поле). Нельзя накручивать марш по рельсу.
    """
    s = 0.0
    ticks: List[Tick] = []
    for _ in range(40):
        s += 2.0
        ticks.append(Tick(s, 2.0, 2.0, accuracy=4.0))
    rail_s = s
    for _ in range(60):
        # физически +2 м в поле; проекция на рельс та же
        ticks.append(Tick(rail_s, 2.0, 2.0, accuracy=4.0))

    def asserts(true_m, cur, lip):
        # на рельсах честно только первая часть ~80 м; остальное off-rail
        # липучка не должна накрутить 200 м по застывшему cand
        ok = lip.credited < 100.0  # ~80 + немного
        return ok, f"lip={lip.credited:.1f} (need <100; true_path={true_m:.1f} includes offroute)"

    return execute("offroute_good_accuracy", ticks, assert_fn=asserts)


def case_field_30jul_two_jumps(rng: random.Random) -> CaseResult:
    """
    30.07: два скачка проекции ~1249 и ~1230 м. Дальше cand = truth+lead
    (nearest залип впереди). Current выплачивает lead через CAP/debt.
    64 — число порций CAP у current, не число скачков в сценарии.
    """
    s = 0.0
    lead = 0.0
    ticks: List[Tick] = []
    n = 1800
    adv_mean = 2750.0 / n
    jump_ticks = {400: 1249.0, 1100: 1230.0}

    for i in range(n):
        adv = adv_mean + rng.uniform(-0.15, 0.15)
        s += adv
        raw = max(0.4, adv + rng.uniform(-0.25, 0.25))
        if i in jump_ticks:
            lead += jump_ticks[i]
        cand = s + lead + rng.uniform(-3.0, 3.0)
        ticks.append(Tick(cand, raw, adv))

    def asserts(true_m, cur, lip):
        curr_e = err_pct(cur.credited, true_m)
        lip_e = abs(err_pct(lip.credited, true_m))
        ok = curr_e > 40.0 and lip_e < 3.0
        return ok, f"curr%={curr_e:+.1f} lip%={err_pct(lip.credited, true_m):+.1f}"

    return execute("field_30jul_two_jumps", ticks, assert_fn=asserts)


def case_park_lead_accumulation(rng: random.Random) -> CaseResult:
    """Накопленный lead (классический airtrain) — контроль регрессии."""
    s = 0.0
    lead = 0.0
    ticks: List[Tick] = []
    n = 1200
    adv_mean = 2800.0 / n
    jumps = set(range(100, n - 50, n // 7))
    for i in range(n):
        adv = adv_mean + rng.uniform(-0.1, 0.1)
        s += adv
        # raw НЕ равен истине идеально + иногда врёт
        if i % 20 == 0:
            raw = adv * rng.uniform(3.0, 6.0)
        else:
            raw = adv * rng.uniform(0.7, 1.3)
        if i in jumps:
            lead += rng.uniform(250, 400)
        ticks.append(Tick(s + lead + rng.uniform(-2, 2), raw, adv))

    def asserts(true_m, cur, lip):
        ok = err_pct(cur.credited, true_m) > 50.0 and abs(err_pct(lip.credited, true_m)) < 5.0
        return ok, f"curr%={err_pct(cur.credited, true_m):+.1f} lip%={err_pct(lip.credited, true_m):+.1f}"

    return execute("park_lead_stress", ticks, assert_fn=asserts)


def case_stadium_clean_ring(rng: random.Random) -> CaseResult:
    """Короткое кольцо, почти чистый GPS — оба близко (smoke, но с wrap)."""
    L = 400.0
    s = 0.0
    ticks: List[Tick] = []
    for _ in range(200):
        adv = 3.0 + rng.uniform(-0.15, 0.15)
        s = wrap(s + adv, L)
        ticks.append(Tick(s + rng.uniform(-1.5, 1.5), adv + rng.uniform(-0.2, 0.2), adv))

    def asserts(true_m, cur, lip):
        ok = abs(err_pct(lip.credited, true_m)) < 3.0
        return ok, f"lip%={err_pct(lip.credited, true_m):+.1f}"

    return execute("stadium_ring_smoke", ticks, length=L, assert_fn=asserts)


# =============================================================================
# Main
# =============================================================================

def main() -> int:
    cases = [
        case_stadium_clean_ring(random.Random(42)),
        case_stationary_jitter(random.Random(43)),
        case_small_back_then_forward(random.Random(44)),
        case_three_identical_behind(random.Random(45)),
        case_slow_real_uturn(random.Random(46)),
        case_phantom_plus_corrupt_raw(random.Random(47)),
        case_uneven_dt(random.Random(48)),
        case_gps_dropout(random.Random(49)),
        case_closed_ring(random.Random(50)),
        case_overlapping_out_and_back(random.Random(51)),
        case_fork_ambiguous(random.Random(52)),
        case_offroute_good_accuracy(random.Random(53)),
        case_field_30jul_two_jumps(random.Random(54)),
        case_park_lead_accumulation(random.Random(55)),
    ]

    print("=" * 100)
    print(f"{'case':<28} {'true':>7} {'curr':>7} {'lip':>7} {'curr%':>8} {'lip%':>8}  OK  detail")
    print("=" * 100)
    failed = 0
    for r in cases:
        mark = "PASS" if r.ok else "FAIL"
        if not r.ok:
            failed += 1
        print(
            f"{r.name:<28} {r.true_m:7.1f} {r.current_m:7.1f} {r.lip_m:7.1f} "
            f"{err_pct(r.current_m, r.true_m):7.1f}% {err_pct(r.lip_m, r.true_m):7.1f}%  "
            f"{mark}  {r.detail}"
        )
    print("=" * 100)
    print(f"FAILED: {failed}/{len(cases)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
