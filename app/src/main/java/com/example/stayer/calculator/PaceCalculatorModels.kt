package com.example.stayer.calculator

/**
 * Значения калькулятора темпа.
 * Pace calculator input values.
 */
data class PaceCalculatorInput(
    val distanceKm: Double? = null,
    val durationSec: Int? = null,
    val paceSecPerKm: Int? = null
)

/**
 * Результат вычисления калькулятора темпа.
 * Pace calculator computed result.
 */
data class PaceCalculatorResult(
    val distanceKm: Double? = null,
    val durationSec: Int? = null,
    val paceSecPerKm: Int? = null,
    val computedField: PaceField
)

/**
 * Поля калькулятора темпа.
 * Pace calculator fields.
 */
enum class PaceField {
    DISTANCE,
    DURATION,
    PACE
}
