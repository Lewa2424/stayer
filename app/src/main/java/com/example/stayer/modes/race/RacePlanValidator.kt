package com.example.stayer.modes.race

import kotlin.math.abs

object RacePlanValidator {
    /**
     * Проверяет корректность плана забега перед сохранением.
     * Validates a race plan before saving.
     */
    fun validate(totalDistanceKm: Double, segments: List<RaceSegmentPlan>): String? {
        if (totalDistanceKm <= 0.0) return "Введите общую дистанцию забега."
        if (segments.isEmpty()) return "Добавьте хотя бы один участок."
        if (segments.any { it.distanceKm <= 0.0 }) return "У каждого участка должна быть положительная дистанция."
        if (segments.any { it.targetPaceSecPerKm !in 180..1200 }) return "У каждого участка должен быть корректный темп."

        val segmentsDistanceKm = segments.sumOf { it.distanceKm }
        if (abs(segmentsDistanceKm - totalDistanceKm) > 0.05) {
            return "Сумма участков должна совпадать с общей дистанцией."
        }
        return null
    }
}
