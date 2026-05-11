package com.example.stayer.history

/**
 * Точка графика темпа для отчёта тренировки.
 * Хранит короткую подпись по оси X и темп участка в секундах на километр.
 *
 * Pace chart point for the workout report.
 * Stores a short X-axis label and the segment pace in seconds per kilometer.
 */
data class WorkoutPaceChartPoint(
    val label: String,
    val paceSecPerKm: Int
)
