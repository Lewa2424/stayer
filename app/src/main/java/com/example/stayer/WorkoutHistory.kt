package com.example.stayer

data class WorkoutHistorySegment(
    val title: String,
    val type: String,
    val distanceKm: Float,
    val durationSec: Int,
    val actualPaceSecPerKm: Int? = null,
    val targetPaceSecPerKm: Int? = null
)

data class WorkoutHistory(
    val date: String,
    val distance: Float,
    val time: String,
    val speed: Float,
    val elapsedMs: Long = 0L,
    val workoutMode: String = "normal",        // "normal" | "interval" | "combined"
    val normalGoalMode: Int? = null,
    val goalLabel: String? = null,
    val targetDistanceKm: Float? = null,
    val targetTimeSec: Int? = null,
    val targetPaceSecPerKm: Int? = null,
    val avgPaceWorkSec: Int? = null,
    val avgPaceRestSec: Int? = null,
    val avgPaceWithoutWarmupSec: Int? = null,
    val avgPaceTotalSec: Int? = null,
    val segmentDetails: List<WorkoutHistorySegment>? = null
)
