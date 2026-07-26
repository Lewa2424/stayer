package com.example.stayer.engine

import java.util.ArrayDeque
import kotlin.math.roundToInt

/**
 * Движок каденс-fallback: state machine + расчёт каденса.
 * Cadence fallback engine: state machine and cadence calculation.
 *
 * Дистанцию считает WorkoutDistanceArbiter; этот класс отвечает за состояние,
 * каденс и сбор данных для pace-профиля.
 * Distance is counted by WorkoutDistanceArbiter; this class handles state,
 * cadence, and pace-profile training data.
 */
class CadenceFallbackEngine(
    private val profileStore: PaceCadenceProfileStore,
) {
    enum class State { STABLE, BLIND, QUARANTINE }

    var currentState: State = State.STABLE
        private set

    var currentCadenceSpm: Int = 0
        private set

    private var stableTicks = 0
    private var quarantineTicks = 0
    private var ticksSinceLastGps = 0
    private var blindStepDistanceM = 0.0

    private val stepHistory = ArrayDeque<Int>()
    private val historyWindowSize = 10

    private var currentPhaseStepCount = 0
    private var currentPhaseDistanceM = 0.0

    private val requiredStableTicksForCalibration = 60
    private val maxTicksWithoutGps = 3
    private val quarantineDurationTicks = 3

    /**
     * Сбрасывает состояние при старте/остановке тренировки.
     * Resets state at workout start/stop.
     */
    fun reset() {
        currentState = State.STABLE
        stableTicks = 0
        quarantineTicks = 0
        ticksSinceLastGps = 0
        blindStepDistanceM = 0.0
        stepHistory.clear()
        currentPhaseStepCount = 0
        currentPhaseDistanceM = 0.0
        currentCadenceSpm = 0
    }

    /**
     * Обновляет каденс и state machine на каждом секундном тике.
     * Updates cadence and the state machine on each one-second tick.
     *
     * @param emitDistance если true и состояние BLIND — вернуть шаговую дистанцию.
     * @return метры для добавления (только cadence fallback).
     */
    fun processTick(stepDelta: Int, emitDistance: Boolean, suppressBlindTransition: Boolean = false): Double {
        currentCadenceSpm = updateAndGetCadence(stepDelta)
        if (!suppressBlindTransition) {
            ticksSinceLastGps++
            if (currentState == State.STABLE && ticksSinceLastGps > maxTicksWithoutGps) {
                enterBlind()
            }
        }

        return when (currentState) {
            State.STABLE -> {
                stableTicks++
                currentPhaseStepCount += stepDelta
                tryCommitCalibration()
                0.0
            }
            State.BLIND -> {
                if (!emitDistance || stepDelta <= 0) return 0.0
                val stride = profileStore.strideForCadence(currentCadenceSpm)
                val dist = stepDelta * stride
                blindStepDistanceM += dist
                dist
            }
            State.QUARANTINE -> {
                quarantineTicks++
                if (quarantineTicks >= quarantineDurationTicks) {
                    currentState = State.STABLE
                    stableTicks = 0
                    quarantineTicks = 0
                    currentPhaseStepCount = 0
                    currentPhaseDistanceM = 0.0
                    blindStepDistanceM = 0.0
                }
                0.0
            }
        }
    }

    /**
     * Регистрирует авторитетную дистанцию (GPS или рельсы) для калибровки.
     * Records authoritative distance (GPS or rails) for calibration.
     */
    fun recordAuthoritativeDistance(deltaMeters: Double) {
        if (currentState != State.STABLE || deltaMeters <= 0.0) return
        currentPhaseDistanceM += deltaMeters
    }

    /**
     * Обрабатывает принятую GPS-точку (без рельс): возвращает дистанцию для добавления.
     * Handles an accepted GPS point (no rails): returns distance to add.
     */
    fun processGpsAccepted(deltaMeters: Double): Double {
        ticksSinceLastGps = 0
        return when (currentState) {
            State.STABLE -> {
                recordAuthoritativeDistance(deltaMeters)
                deltaMeters
            }
            State.BLIND -> {
                currentState = State.QUARANTINE
                quarantineTicks = 0
                (deltaMeters - blindStepDistanceM).coerceAtLeast(0.0)
            }
            State.QUARANTINE -> deltaMeters
        }
    }

    /**
     * Обрабатывает отклонённую GPS-точку.
     * Handles a rejected GPS point.
     *
     * @param suppressBlind true, если рельсы locked — не уходить в BLIND.
     */
    fun processGpsRejected(suppressBlind: Boolean = false) {
        if (suppressBlind) return
        when (currentState) {
            State.STABLE -> enterBlind()
            State.QUARANTINE -> {
                currentState = State.BLIND
                quarantineTicks = 0
            }
            State.BLIND -> Unit
        }
    }

    /**
     * Сообщает, что GPS-сигнал получен (сбрасывает таймер молчания).
     * Reports that a GPS signal was received (resets silence timer).
     */
    fun onGpsSignalReceived() {
        ticksSinceLastGps = 0
    }

    private fun enterBlind() {
        currentState = State.BLIND
        blindStepDistanceM = 0.0
        stableTicks = 0
        currentPhaseStepCount = 0
        currentPhaseDistanceM = 0.0
    }

    private fun tryCommitCalibration() {
        if (stableTicks < requiredStableTicksForCalibration) return
        if (currentPhaseStepCount <= 0 || currentPhaseDistanceM <= 0.0) {
            resetCalibrationWindow()
            return
        }
        val observedStride = currentPhaseDistanceM / currentPhaseStepCount
        if (observedStride !in 0.3..1.5) {
            resetCalibrationWindow()
            return
        }
        val minutes = stableTicks / 60.0
        val avgCadence = if (minutes > 0) {
            (currentPhaseStepCount / minutes).roundToInt()
        } else {
            currentCadenceSpm
        }
        val paceSecPerKm = ((stableTicks.toDouble() / currentPhaseDistanceM) * 1000.0).roundToInt()
        profileStore.observeSample(
            observedPaceSecPerKm = paceSecPerKm,
            cadenceSpm = avgCadence.coerceAtLeast(1),
            strideMeters = observedStride,
        )
        resetCalibrationWindow()
    }

    private fun resetCalibrationWindow() {
        stableTicks = 0
        currentPhaseStepCount = 0
        currentPhaseDistanceM = 0.0
    }

    private fun updateAndGetCadence(stepDelta: Int): Int {
        if (stepHistory.size >= historyWindowSize) {
            stepHistory.removeFirst()
        }
        stepHistory.addLast(stepDelta)
        val totalSteps = stepHistory.sum()
        return if (stepHistory.isNotEmpty()) {
            (totalSteps.toDouble() * (60.0 / stepHistory.size)).roundToInt()
        } else {
            0
        }
    }
}
