package com.example.stayer.debug

import com.example.stayer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Simulation engine for combo (mixed) workouts.
 * Handles both time-based segments (WARMUP/WORK/REST/COOLDOWN)
 * and distance-based segments (PACE).
 */
class ComboSimulationEngine(
    private val scenario: IntervalScenario,  // already flattened
    private val ttsHelper: PacerTestTtsHelper,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(IntervalSimState(segmentTotal = scenario.segments.size))
    val state = _state.asStateFlow()

    var simulationSpeedKmh = 8.0
    var timeMultiplier = 5
    private var job: Job? = null

    private var segmentIndex = 0
    private var segmentStartElapsedSec = 0
    private var segmentStartDistanceM = 0.0
    private var lastAnnouncedSegmentIndex = -1

    // Stable phase tracking
    private val workIgnoreSec = 3
    private var stableStarted = false
    private var stableStartElapsedSec = 0
    private var stableStartDistanceM = 0.0

    // Adaptive hint state
    private var lastIntervalHintInSegSec = -1
    private var intervalHintAlternate = false
    private var midHintDone = false
    private var warned10sIndex = -1
    private var endReportIndex = -1

    // PACE checkpoint tracking
    private var lastPaceCheckpointN = 0

    // Prompt log
    private val promptLog = mutableListOf<String>()
    private val maxLogSize = 15

    fun startOrResume() {
        if (_state.value.status == PacerTestStatus.RUNNING) return
        _state.value = _state.value.copy(status = PacerTestStatus.RUNNING)
        job = scope.launch(Dispatchers.Default) {
            while (isActive && _state.value.status == PacerTestStatus.RUNNING) {
                delay(1000L)
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
        _state.value = IntervalSimState(segmentTotal = scenario.segments.size)
        segmentIndex = 0; segmentStartElapsedSec = 0; segmentStartDistanceM = 0.0
        lastAnnouncedSegmentIndex = -1; stableStarted = false
        lastIntervalHintInSegSec = -1; intervalHintAlternate = false
        midHintDone = false; warned10sIndex = -1; endReportIndex = -1
        lastPaceCheckpointN = 0
        promptLog.clear()
    }

    private fun addPrompt(text: String) {
        promptLog.add(text)
        if (promptLog.size > maxLogSize) promptLog.removeFirst()
        ttsHelper.speak(text)
    }

    private fun estimatePace(): Int? {
        if (simulationSpeedKmh <= 0.5) return null
        return (3600.0 / simulationSpeedKmh).toInt()
    }

    private fun tick() {
        if (scenario.segments.isEmpty()) return
        if (segmentIndex !in scenario.segments.indices) return

        val st = _state.value
        val seg = scenario.segments[segmentIndex]
        val newSec = st.elapsedSec + timeMultiplier
        val distDeltaKm = simulationSpeedKmh * (timeMultiplier / 3600.0)
        val newDistKm = st.distanceKm + distDeltaKm
        val newDistM = newDistKm * 1000.0

        val inSegSec = newSec - segmentStartElapsedSec
        val segDistCoveredKm = (newDistM - segmentStartDistanceM) / 1000.0
        val isPaceSegment = seg.type == "PACE" && seg.distanceKm != null && seg.distanceKm > 0
        val remainingSec = if (isPaceSegment) Int.MAX_VALUE else (seg.durationSec - inSegSec)

        // Announce segment start
        if (segmentIndex != lastAnnouncedSegmentIndex) {
            lastAnnouncedSegmentIndex = segmentIndex
            stableStarted = false
            lastPaceCheckpointN = 0

            val label = when (seg.type) {
                "WARMUP" -> "Разминка"; "WORK" -> "Работа"; "REST" -> "Отдых"
                "COOLDOWN" -> "Заминка"; "PACE" -> "Свободный бег"; else -> "Сегмент"
            }
            val detail = if (isPaceSegment) {
                val d = String.format("%.1f км", seg.distanceKm)
                val p = seg.targetPaceSecPerKm?.let { ", темп ${PacerLogicHelper.formatPaceForSpeech(it)}" } ?: ""
                "$d$p"
            } else {
                val dur = seg.durationSec
                val durText = if (dur >= 60) "${dur / 60}м" else "${dur}с"
                val p = seg.targetPaceSecPerKm?.let { ", темп ${PacerLogicHelper.formatPaceForSpeech(it)}" } ?: ""
                "$durText$p"
            }
            addPrompt("$label ($detail)")
        }

        // Warning 10s before time-based segment ends
        if (!isPaceSegment && remainingSec in 0..10 && warned10sIndex != segmentIndex) {
            warned10sIndex = segmentIndex
            addPrompt("Смена через 10 секунд")
        }

        // Stable tracking for WORK
        if (seg.type == "WORK" && !stableStarted && inSegSec >= workIgnoreSec) {
            stableStarted = true
            stableStartElapsedSec = newSec
            stableStartDistanceM = newDistM
        }

        // Adaptive hints for WORK segments
        if (seg.type == "WORK" && seg.targetPaceSecPerKm != null && stableStarted) {
            val stableInSegSec = inSegSec - workIgnoreSec
            val timing = PacerLogicHelper.intervalHintTiming(seg.durationSec)

            val shouldHint = if (timing != null) {
                val (firstAt, repeatEvery) = timing
                if (lastIntervalHintInSegSec < 0) stableInSegSec >= firstAt
                else stableInSegSec - lastIntervalHintInSegSec >= repeatEvery
            } else if (seg.durationSec in 30..119) {
                inSegSec >= seg.durationSec / 2 && !midHintDone
            } else false

            if (shouldHint && remainingSec > 15) {
                val curPace = estimatePace()
                if (curPace != null) {
                    val stableDistM = (newDistM - stableStartDistanceM).coerceAtLeast(1.0)
                    val stableTimeSec = (newSec - stableStartElapsedSec).coerceAtLeast(1)
                    val avgPace = if (stableDistM > 25.0) (stableTimeSec / (stableDistM / 1000.0)).toInt() else null

                    val (hint, nextAlt) = PacerLogicHelper.buildIntervalHint(
                        curPace, seg.targetPaceSecPerKm, intervalHintAlternate, avgPace
                    )
                    intervalHintAlternate = nextAlt
                    addPrompt(hint)
                    lastIntervalHintInSegSec = stableInSegSec
                    if (timing == null) midHintDone = true
                }
            }
        }

        // PACE segment: checkpoint hints every 500m
        if (isPaceSegment && seg.targetPaceSecPerKm != null) {
            val segDistM = newDistM - segmentStartDistanceM
            val targetDistM = seg.distanceKm!! * 1000.0
            val step = minOf(500.0, targetDistM * 0.1).coerceAtLeast(100.0)
            val curN = (segDistM / step).toInt()
            if (curN > lastPaceCheckpointN && segDistM < targetDistM - 50) {
                lastPaceCheckpointN = curN
                val curPace = estimatePace()
                if (curPace != null) {
                    val diff = curPace - seg.targetPaceSecPerKm
                    val remaining = (targetDistM - segDistM) / 1000.0
                    val prompt = when {
                        kotlin.math.abs(diff) <= 15 -> "Темп хороший. Осталось ${String.format("%.1f", remaining)} км."
                        diff > 15 -> "Темп ${PacerLogicHelper.formatPaceForSpeech(curPace)}. Нужен ${PacerLogicHelper.formatPaceForSpeech(seg.targetPaceSecPerKm)}. Ускорьтесь."
                        else -> "Не гони. Темп ${PacerLogicHelper.formatPaceForSpeech(curPace)}."
                    }
                    addPrompt(prompt)
                }
            }
        }

        // Transition
        val shouldTransition = if (isPaceSegment) {
            segDistCoveredKm >= seg.distanceKm!!
        } else {
            remainingSec <= 0
        }

        var lastPrompt = ""
        if (shouldTransition) {
            // End report for WORK segments
            if (seg.type == "WORK" && seg.targetPaceSecPerKm != null && endReportIndex != segmentIndex) {
                endReportIndex = segmentIndex
                val factPace = estimatePace()
                if (factPace != null) {
                    val report = PacerLogicHelper.buildIntervalEndReport(factPace, seg.targetPaceSecPerKm)
                    addPrompt(report)
                    lastPrompt = report
                }
            }
            if (seg.type == "PACE") {
                addPrompt("Дистанция пройдена. ${String.format("%.1f", seg.distanceKm)} км.")
                lastPrompt = "Дистанция пройдена."
            }

            segmentIndex++
            if (segmentIndex >= scenario.segments.size) {
                addPrompt("Тренировка завершена")
                _state.value = st.copy(
                    elapsedSec = newSec, distanceKm = newDistKm,
                    status = PacerTestStatus.STOPPED,
                    promptLog = promptLog.toList()
                )
                job?.cancel()
                return
            }

            segmentStartElapsedSec = newSec
            segmentStartDistanceM = newDistM
            lastIntervalHintInSegSec = -1
            midHintDone = false
            stableStarted = false
            lastPaceCheckpointN = 0
        }

        val curPace = estimatePace() ?: 0
        val nextSeg = scenario.segments.getOrNull(segmentIndex)

        _state.value = st.copy(
            elapsedSec = newSec,
            distanceKm = newDistKm,
            segmentIndex = segmentIndex + 1,
            segmentType = nextSeg?.type ?: seg.type,
            segmentRemainingSec = if (nextSeg?.type == "PACE" && nextSeg.distanceKm != null) {
                val remaining = (nextSeg.distanceKm * 1000.0 - (newDistM - segmentStartDistanceM)) / 1000.0
                (remaining * 60).toInt()  // approximate seconds at current speed
            } else {
                nextSeg?.let { it.durationSec - (newSec - segmentStartElapsedSec) } ?: 0
            },
            segmentTargetPace = nextSeg?.targetPaceSecPerKm,
            currentPaceSecPerKm = curPace,
            currentSpeedKmh = simulationSpeedKmh,
            promptLog = promptLog.toList()
        )
    }
}
