package com.example.stayer.analytics

/**
 * Период выборки для аналитики.
 * Analytics time period selector.
 */
enum class AnalyticsPeriod(
    val title: String,
    val days: Int?
) {
    DAYS_7("7 дней", 7),
    DAYS_30("30 дней", 30),
    DAYS_90("90 дней", 90),
    ALL_TIME("Всё время", null)
}
