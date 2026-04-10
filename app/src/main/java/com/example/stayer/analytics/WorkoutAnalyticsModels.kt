package com.example.stayer.analytics

/**
 * Отдельная метрика для отчёта аналитики.
 * Single metric row for analytics report.
 */
data class AnalyticsMetric(
    val label: String,
    val value: String
)

/**
 * Полный отчёт аналитики по выбранному режиму и периоду.
 * Full analytics report for selected mode and period.
 */
data class WorkoutAnalyticsReport(
    val mode: AnalyticsMode,
    val period: AnalyticsPeriod,
    val workoutsCount: Int,
    val testWorkoutsCount: Int,
    val totalDistanceKm: Float,
    val totalTimeSec: Int,
    val metrics: List<AnalyticsMetric>,
    val improvements: List<String>,
    val regressions: List<String>,
    val focusPoints: List<String>,
    val insufficientDataMessage: String? = null
)
