package com.example.stayer.debug

enum class PacerTestStatus {
    STOPPED, RUNNING, PAUSED
}

data class SimulationState(
    val status: PacerTestStatus = PacerTestStatus.STOPPED,
    val elapsedSec: Int = 0,
    val distanceKm: Double = 0.0,
    val currentPaceSecPerKm: Int = 0,
    val currentSpeedKmh: Double = 0.0,
    val nextCheckpointKm: Double = 0.0,
    val lastPrompt: String = "",
    val lastPromptDeviation: String = "",
    val segmentPaceSecPerKm: Int = 0
)

data class IntervalSimState(
    val status: PacerTestStatus = PacerTestStatus.STOPPED,
    val elapsedSec: Int = 0,
    val distanceKm: Double = 0.0,
    val currentPaceSecPerKm: Int = 0,
    val currentSpeedKmh: Double = 0.0,
    val segmentIndex: Int = 0,
    val segmentTotal: Int = 0,
    val segmentType: String = "",
    val segmentRemainingSec: Int = 0,
    val segmentTargetPace: Int? = null,
    val lastPrompt: String = "",
    val promptLog: List<String> = emptyList()  // last N prompts for scroll log
)

