package com.example.stayer.debug

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class PacerSimulationEngine(
    private val targetDistanceKm: Double,
    private val targetPaceSecPerKm: Float,
    private val ttsHelper: PacerTestTtsHelper,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(SimulationState())
    val state = _state.asStateFlow()

    private var job: Job? = null

    // Configs
    var simulationSpeedKmh: Double = 0.0
    var timeMultiplier: Int = 5 // User selected x1, x5, x10

    private var nextCheckpointKm = calcStep(0.0)
    private var lastCheckDistM = 0.0
    private var lastCheckSec = 0
    private var pacerPraiseAlternate = false

    init {
        // Evaluate initial checkpoint correctly based on zero distance
        nextCheckpointKm = calcStep(0.0)
    }

    private fun calcStep(currentKm: Double): Double {
        if (targetDistanceKm <= 0.0) return Double.MAX_VALUE
        val pct = (currentKm / targetDistanceKm) * 100.0
        return when {
            targetDistanceKm < 10.0 -> targetDistanceKm * 0.1
            pct < 80.0 -> targetDistanceKm * 0.1
            pct < 90.0 -> 1.0
            else -> 0.5
        }
    }

    fun startOrResume() {
        if (_state.value.status == PacerTestStatus.RUNNING) return
        _state.value = _state.value.copy(status = PacerTestStatus.RUNNING)
        job = scope.launch(Dispatchers.Default) {
            while (isActive && _state.value.status == PacerTestStatus.RUNNING) {
                delay(1000L) // 1 second real time tick
                tick()
            }
        }
    }

    fun pause() {
        _state.value = _state.value.copy(status = PacerTestStatus.PAUSED)
        job?.cancel()
    }

    fun stop() {
        job?.cancel()
        _state.value = SimulationState()
        lastCheckDistM = 0.0
        lastCheckSec = 0
        pacerPraiseAlternate = false
        nextCheckpointKm = calcStep(0.0)
        lastSegmentPace = 0
        lastEmergencyCheckKm = 0.0
        lastCheckpointProgress = GlobalProgress.ON_TRACK
        emergencyCooldownUntilKm = 0.0
    }

    private var lastSegmentPace = 0

    // Emergency monitor state (250m check)
    private var lastEmergencyCheckKm = 0.0
    private var lastCheckpointProgress = GlobalProgress.ON_TRACK
    private var emergencyCooldownUntilKm = 0.0

    private fun tick() {
        val st = _state.value
        val newSec = st.elapsedSec + timeMultiplier
        // distance = speed * time: km = (km/h) * (h) = km/h * (sec / 3600)
        val distDeltaKm = simulationSpeedKmh * (timeMultiplier / 3600.0)
        var newDistKm = st.distanceKm + distDeltaKm

        if (newDistKm >= targetDistanceKm) {
            pause()
            newDistKm = targetDistanceKm
            ttsHelper.speak("Тренировка завершена. Симуляция остановлена.")
            _state.value = st.copy(
                elapsedSec = newSec,
                distanceKm = newDistKm,
                currentPaceSecPerKm = if (simulationSpeedKmh > 0) (3600.0 / simulationSpeedKmh).toInt() else 0,
                currentSpeedKmh = simulationSpeedKmh,
                lastPrompt = "Тренировка завершена. Симуляция остановлена.",
                lastPromptDeviation = "Завершено"
            )
            return
        }

        var prompt = st.lastPrompt
        var promptDev = st.lastPromptDeviation

        // ─── Emergency 250m check ─────────────────────────────────────────────────
        if (newDistKm - lastEmergencyCheckKm >= 0.25) {
            lastEmergencyCheckKm = newDistKm
            val distToNextCheckpoint = nextCheckpointKm - newDistKm
            val cooldownActive = newDistKm < emergencyCooldownUntilKm
            if (!cooldownActive && distToNextCheckpoint > 0.1) {
                val alert = PacerLogicHelper.buildEmergencyAlert(
                    prevGlobalProgress = lastCheckpointProgress,
                    currentDistKm = newDistKm,
                    currentElapsedSec = newSec,
                    targetPaceSecPerKm = targetPaceSecPerKm.roundToInt(),
                    targetDistKm = targetDistanceKm,
                    targetTotalSec = (targetPaceSecPerKm * targetDistanceKm).roundToInt()
                )
                if (alert != null) {
                    prompt = alert
                    promptDev = "⚠️ Аварийное уведомление"
                    ttsHelper.speak(alert)
                    // Cooldown until the next planned checkpoint
                    emergencyCooldownUntilKm = nextCheckpointKm
                }
            }
        }

        // ─── Planned 10% checkpoint ───────────────────────────────────────────────
        if (newDistKm >= nextCheckpointKm) {
            val result = evaluatePace(newDistKm * 1000.0, newSec)
            if (result != null) {
                prompt = result.first
                promptDev = result.second
                ttsHelper.speak(prompt)
            }
            // Update the last-known global progress for the emergency monitor
            val currentDistKm = newDistKm
            val avgPace = if (currentDistKm > 0.01) newSec / currentDistKm else 0.0
            val targetFinishSec = (targetPaceSecPerKm * targetDistanceKm).roundToInt()
            val predictedFinishSec = (avgPace * targetDistanceKm).roundToInt()
            val delta = predictedFinishSec - targetFinishSec
            lastCheckpointProgress = when {
                kotlin.math.abs(delta) <= 30 -> GlobalProgress.ON_TRACK
                delta > 30 -> GlobalProgress.BEHIND
                else -> GlobalProgress.AHEAD
            }
            emergencyCooldownUntilKm = 0.0  // reset cooldown after each planned checkpoint
            nextCheckpointKm += calcStep(newDistKm)
        }

        _state.value = st.copy(
            elapsedSec = newSec,
            distanceKm = newDistKm,
            currentPaceSecPerKm = if (simulationSpeedKmh > 0) (3600.0 / simulationSpeedKmh).toInt() else 0,
            currentSpeedKmh = simulationSpeedKmh,
            nextCheckpointKm = nextCheckpointKm,
            lastPrompt = prompt,
            lastPromptDeviation = promptDev,
            segmentPaceSecPerKm = lastSegmentPace
        )
    }

    private fun evaluatePace(currentDistM: Double, currentElapsedSec: Int): Pair<String, String>? {
        val deltaDistM = currentDistM - lastCheckDistM
        val deltaTimeSec = currentElapsedSec - lastCheckSec

        lastCheckDistM = currentDistM
        lastCheckSec = currentElapsedSec

        if (deltaDistM < 10.0 || deltaTimeSec <= 0) return null

        val currentPaceSecPerKm = (deltaTimeSec / (deltaDistM / 1000.0)).roundToInt()
        lastSegmentPace = currentPaceSecPerKm
        if (currentPaceSecPerKm < 180 || currentPaceSecPerKm > 1200) return null

        val diffSecPerKm = currentPaceSecPerKm - targetPaceSecPerKm.roundToInt()

        // Global prediction
        val currentDistKm = currentDistM / 1000.0
        val remainingDistKm = (targetDistanceKm - currentDistKm).coerceAtLeast(0.0)
        val avgPaceTotalSecPerKm = currentElapsedSec / (currentDistM / 1000.0)
        val predictedFinishSec = (avgPaceTotalSecPerKm * targetDistanceKm).roundToInt()
        val targetFinishSec = (targetPaceSecPerKm * targetDistanceKm).roundToInt()
        val finishDeltaSec = predictedFinishSec - targetFinishSec
        val timeLeftSec = targetFinishSec - currentElapsedSec

        val globalProgress = when {
            abs(finishDeltaSec) <= 30 -> GlobalProgress.ON_TRACK
            finishDeltaSec > 30 -> GlobalProgress.BEHIND
            else -> GlobalProgress.AHEAD
        }

        val (msg, nextPraise) = PacerLogicHelper.buildNormalPacerPrompt(
            globalProgress = globalProgress,
            globalDeltaSec = finishDeltaSec,
            localDiffSecPerKm = diffSecPerKm,
            currentPaceSecPerKm = currentPaceSecPerKm,
            targetPaceSecPerKm = targetPaceSecPerKm.roundToInt(),
            remainingDistKm = remainingDistKm,
            timeLeftSec = timeLeftSec,
            currentDistKm = currentDistKm,
            pacerPraiseAlternate = pacerPraiseAlternate
        )
        pacerPraiseAlternate = nextPraise

        val globalDevStr = if (finishDeltaSec > 0) "+$finishDeltaSec сек от графика"
                           else if (finishDeltaSec < 0) "$finishDeltaSec сек (запас)"
                           else "В графике"
        val dev = "${if (diffSecPerKm > 0) "+" else ""}$diffSecPerKm сек/км (Глобально: $globalDevStr)"

        return Pair(msg, dev)
    }
}
