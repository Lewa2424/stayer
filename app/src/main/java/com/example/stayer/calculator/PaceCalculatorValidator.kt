package com.example.stayer.calculator

/**
 * Проверяет заполненность полей калькулятора.
 * Validates calculator field completeness.
 */
object PaceCalculatorValidator {
    fun validate(input: PaceCalculatorInput): String? {
        val count = listOf(input.distanceKm, input.durationSec, input.paceSecPerKm).count { it != null }
        return when {
            count < 2 -> "Введите любые 2 значения."
            count > 2 -> "Оставьте пустым один параметр, который нужно рассчитать."
            else -> null
        }
    }
}
