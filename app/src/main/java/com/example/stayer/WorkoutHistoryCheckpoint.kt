package com.example.stayer

/**
 * Детали одного чекпоинта обычной тренировки для истории.
 * Хранит границы отрезка, время, темп и локальное отклонение от цели.
 *
 * Details of one normal-workout checkpoint for history.
 * Stores segment boundaries, duration, pace, and local deviation from target.
 */
data class WorkoutHistoryCheckpoint(
    val fromKm: Float,
    val toKm: Float,
    val durationSec: Int,
    val paceSecPerKm: Int,
    val deltaSec: Int
)
