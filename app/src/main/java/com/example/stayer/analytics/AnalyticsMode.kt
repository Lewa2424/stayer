package com.example.stayer.analytics

/**
 * Режим тренировки для аналитики.
 * Workout mode selector for analytics.
 */
enum class AnalyticsMode(
    val title: String
) {
    NORMAL("Обычная"),
    INTERVAL("Интервальная"),
    COMBINED("Комбо"),
    RACE("Забег")
}
