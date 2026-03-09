package com.example.stayer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Финальный снимок состояния тренировки.
 * Формируется Service до любого reset'а.
 * Источник истины для сохранения в историю.
 */
data class WorkoutSummarySnapshot(
    val distanceKm: Float,
    val elapsedMs: Long,
    val speedKmh: Float,
    val workoutMode: String,              // "normal" | "interval" | "combined"
    val normalGoalMode: Int? = null,
    val goalLabel: String? = null,
    val targetDistanceKm: Float?,
    val targetTimeSec: Int?,
    val targetPaceSecPerKm: Int?,
    val avgPaceWorkSec: Int?,
    val avgPaceRestSec: Int?,
    val avgPaceWithoutWarmupSec: Int?,
    val avgPaceTotalSec: Int?,
    val segmentDetails: List<WorkoutHistorySegment>? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isValid(): Boolean {
        return distanceKm > 0f && elapsedMs > 0L
    }

    fun toWorkoutHistory(): WorkoutHistory {
        val seconds = (elapsedMs / 1000) % 60
        val minutes = (elapsedMs / (1000 * 60)) % 60
        val hours = (elapsedMs / (1000 * 60 * 60)) % 24
        val timeString = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        val currentDate = SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date(timestamp))

        return WorkoutHistory(
            date = currentDate,
            distance = distanceKm,
            time = timeString,
            speed = speedKmh,
            elapsedMs = elapsedMs,
            workoutMode = workoutMode,
            normalGoalMode = normalGoalMode,
            goalLabel = goalLabel,
            targetDistanceKm = targetDistanceKm,
            targetTimeSec = targetTimeSec,
            targetPaceSecPerKm = targetPaceSecPerKm,
            avgPaceWorkSec = avgPaceWorkSec,
            avgPaceRestSec = avgPaceRestSec,
            avgPaceWithoutWarmupSec = avgPaceWithoutWarmupSec,
            avgPaceTotalSec = avgPaceTotalSec,
            segmentDetails = segmentDetails
        )
    }
}
