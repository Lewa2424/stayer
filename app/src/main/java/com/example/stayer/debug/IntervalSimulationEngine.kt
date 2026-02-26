package com.example.stayer.debug

import com.example.stayer.IntervalScenario
import com.example.stayer.Segment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class IntervalSimulationEngine(
    private val scenario: IntervalScenario,
    private val ttsHelper: PacerTestTtsHelper,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(IntervalSimState(segmentTotal = scenario.segments.size))
    val state = _state.asStateFlow()

    private var job: Job? = null

    // Simulation controls
    var simulationSpeedKmh: Double = 8.0
    var timeMultiplier: Int = 5

    // Internal state mirroring handleIntervalTick
    private var segmentIndex = 0
    private var segmentStartElapsedSec = 0
    private var lastAnnouncedSegmentIndex = -1

    // Stable phase tracking (ignore first 3s of WORK for pace estimation)
    private val workIgnoreSec = 3
    private var stableStarted = false
    private var stableStartElapsedSec = 0
    private var stableStartDistanceM = 0.0

    // Rolling speed window (8-second window, stores delta meters per tick)
    private val speedWindowSec = 8
    private val stableDeltasM = ArrayDeque<Double>()
    private var lastTickDistanceM = 0.0

    // Adaptive hint state
    private var lastIntervalHintInSegSec = -1
    private var intervalHintAlternate = false
    private var midHintDone = false

    // TTS announcement flags (per segment)
    private var warned10sIndex = -1
    private var endReportIndex = -1

    // Prompt log
    private val promptLog = mutableListOf<String>()
    private val maxLogSize = 10

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
        segmentIndex = 0
        segmentStartElapsedSec = 0
        lastAnnouncedSegmentIndex = -1
        stableStarted = false
        stableStartDistanceM = 0.0
        stableDeltasM.clear()
        lastTickDistanceM = 0.0
        lastIntervalHintInSegSec = -1
        intervalHintAlternate = false
        midHintDone = false
        warned10sIndex = -1
        endReportIndex = -1
        promptLog.clear()
    }

    private fun addPrompt(text: String) {
        promptLog.add(text)
        if (promptLog.size > maxLogSize) promptLog.removeFirst()
        ttsHelper.speak(text)
    }

    private fun estimatePaceFromWindow(): Int? {
        // In simulation, speed comes from slider — no GPS noise to filter.
        // Direct conversion is perfectly accurate and avoids timeMultiplier bugs.
        if (simulationSpeedKmh <= 0.5) return null
        return (3600.0 / simulationSpeedKmh).toInt()
    }

    private fun tick() {
        if (scenario.segments.isEmpty()) return
        if (segmentIndex !in scenario.segments.indices) return

        val st = _state.value
        val newSec = st.elapsedSec + timeMultiplier
        val distDeltaKm = simulationSpeedKmh * (timeMultiplier / 3600.0)
        val newDistKm = st.distanceKm + distDeltaKm
        val newDistM = newDistKm * 1000.0

        val seg = scenario.segments[segmentIndex]
        val inSegSec = newSec - segmentStartElapsedSec
        val remainingSec = seg.durationSec - inSegSec

        // Distance delta for rolling window (in meters)
        val deltaM = (newDistM - lastTickDistanceM).coerceAtLeast(0.0)
        lastTickDistanceM = newDistM

        var lastPrompt = st.lastPrompt

        // 1) Announce segment start (once per segment)
        if (segmentIndex != lastAnnouncedSegmentIndex) {
            lastAnnouncedSegmentIndex = segmentIndex
            stableStarted = false
            stableDeltasM.clear()
            lastIntervalHintInSegSec = -1
            midHintDone = false

            val label = when (seg.type) {
                "WARMUP" -> "Разминка"
                "WORK" -> "Работа"
                "REST" -> "Отдых"
                "COOLDOWN" -> "Заминка"
                else -> "Сегмент"
            }
            val dur = seg.durationSec
            val durText = if (dur >= 60) {
                val m = dur / 60; val s = dur % 60
                if (s == 0) "$m минут" else "$m минут $s секунд"
            } else "$dur секунд"
            val pacePart = seg.targetPaceSecPerKm?.let {
                " Темп ${PacerLogicHelper.formatPaceForSpeech(it)}."
            } ?: ""
            val msg = "$label. $durText.$pacePart"
            lastPrompt = msg
            addPrompt(msg)
        }

        // 2) Warning 10 seconds before segment ends
        if (remainingSec == 10 && warned10sIndex != segmentIndex) {
            warned10sIndex = segmentIndex
            val msg = "Смена через 10 секунд"
            lastPrompt = msg
            addPrompt(msg)
        }

        // 3) Start "stable" tracking for WORK after ignoring first 3 seconds
        if (seg.type == "WORK" && !stableStarted && inSegSec >= workIgnoreSec) {
            stableStarted = true
            stableStartElapsedSec = newSec
            stableStartDistanceM = newDistM
            stableDeltasM.clear()
        }

        // 4) Collect rolling speed data during stable phase
        if (stableStarted && (seg.type == "WORK" || seg.type == "REST")) {
            stableDeltasM.addLast(deltaM)
            while (stableDeltasM.size > speedWindowSec) stableDeltasM.removeFirst()
        }

        // 5) Adaptive pace hints for WORK segments with target pace
        if (seg.type == "WORK" && seg.targetPaceSecPerKm != null && stableStarted && stableDeltasM.size >= 5) {
            val stableInSegSec = inSegSec - workIgnoreSec
            val timing = PacerLogicHelper.intervalHintTiming(seg.durationSec)

            val shouldHint = if (timing != null) {
                val (firstAt, repeatEvery) = timing
                if (lastIntervalHintInSegSec < 0) {
                    stableInSegSec >= firstAt
                } else {
                    stableInSegSec - lastIntervalHintInSegSec >= repeatEvery
                }
            } else if (seg.durationSec in 30..119) {
                val midPoint = seg.durationSec / 2
                inSegSec >= midPoint && !midHintDone
            } else {
                false
            }

            if (shouldHint && remainingSec > 15) {
                val curPace = estimatePaceFromWindow()
                if (curPace != null) {
                    // Layer 2: average pace over the stable phase
                    val stableDistM = (newDistM - stableStartDistanceM).coerceAtLeast(1.0)
                    val stableTimeSec = (newSec - stableStartElapsedSec).coerceAtLeast(1)
                    val avgPace = if (stableDistM > 25.0) (stableTimeSec / (stableDistM / 1000.0)).toInt() else null

                    val (hint, nextAlt) = PacerLogicHelper.buildIntervalHint(
                        currentPaceSecPerKm = curPace,
                        targetPaceSecPerKm = seg.targetPaceSecPerKm,
                        alternate = intervalHintAlternate,
                        avgPaceSecPerKm = avgPace
                    )
                    intervalHintAlternate = nextAlt
                    lastPrompt = hint
                    addPrompt(hint)
                    lastIntervalHintInSegSec = stableInSegSec
                    if (timing == null) midHintDone = true
                }
            }
        }

        // 6) Segment transition
        if (remainingSec <= 0) {
            // End report for WORK
            if (seg.type == "WORK" && seg.targetPaceSecPerKm != null && endReportIndex != segmentIndex) {
                endReportIndex = segmentIndex
                val curPace = estimatePaceFromWindow()
                if (curPace != null) {
                    val report = PacerLogicHelper.buildIntervalEndReport(curPace, seg.targetPaceSecPerKm)
                    lastPrompt = report
                    addPrompt(report)
                }
            }

            segmentIndex += 1
            if (segmentIndex >= scenario.segments.size) {
                val msg = "Интервальная тренировка завершена"
                lastPrompt = msg
                addPrompt(msg)
                _state.value = st.copy(
                    status = PacerTestStatus.PAUSED,
                    elapsedSec = newSec,
                    distanceKm = newDistKm,
                    currentPaceSecPerKm = if (simulationSpeedKmh > 0) (3600.0 / simulationSpeedKmh).toInt() else 0,
                    currentSpeedKmh = simulationSpeedKmh,
                    segmentIndex = segmentIndex,
                    lastPrompt = lastPrompt,
                    promptLog = promptLog.toList()
                )
                pause()
                return
            }

            segmentStartElapsedSec = newSec
            lastIntervalHintInSegSec = -1
        }

        val curSeg = if (segmentIndex in scenario.segments.indices) scenario.segments[segmentIndex] else null
        val curInSeg = newSec - segmentStartElapsedSec
        val curRemaining = (curSeg?.durationSec ?: 0) - curInSeg

        _state.value = st.copy(
            status = PacerTestStatus.RUNNING,
            elapsedSec = newSec,
            distanceKm = newDistKm,
            currentPaceSecPerKm = if (simulationSpeedKmh > 0) (3600.0 / simulationSpeedKmh).toInt() else 0,
            currentSpeedKmh = simulationSpeedKmh,
            segmentIndex = segmentIndex + 1,
            segmentTotal = scenario.segments.size,
            segmentType = curSeg?.type ?: "",
            segmentRemainingSec = curRemaining.coerceAtLeast(0),
            segmentTargetPace = curSeg?.targetPaceSecPerKm,
            lastPrompt = lastPrompt,
            promptLog = promptLog.toList()
        )
    }
}
