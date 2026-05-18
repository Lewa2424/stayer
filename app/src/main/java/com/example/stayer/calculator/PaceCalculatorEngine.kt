package com.example.stayer.calculator

import kotlin.math.roundToInt

/**
 * Вычисляет недостающее поле по двум заполненным.
 * Computes the missing field from two populated inputs.
 */
object PaceCalculatorEngine {
    fun calculate(input: PaceCalculatorInput): PaceCalculatorResult {
        val fields = listOfNotNull(
            input.distanceKm?.let { PaceField.DISTANCE },
            input.durationSec?.let { PaceField.DURATION },
            input.paceSecPerKm?.let { PaceField.PACE }
        )

        require(fields.size == 2) { "Нужно заполнить ровно 2 поля." }

        return when {
            input.distanceKm == null -> {
                val distanceKm = input.durationSec!!.toDouble() / input.paceSecPerKm!!
                PaceCalculatorResult(
                    distanceKm = distanceKm,
                    durationSec = input.durationSec,
                    paceSecPerKm = input.paceSecPerKm,
                    computedField = PaceField.DISTANCE
                )
            }
            input.durationSec == null -> {
                val durationSec = (input.distanceKm * input.paceSecPerKm!!).roundToInt()
                PaceCalculatorResult(
                    distanceKm = input.distanceKm,
                    durationSec = durationSec,
                    paceSecPerKm = input.paceSecPerKm,
                    computedField = PaceField.DURATION
                )
            }
            else -> {
                val paceSecPerKm = (input.durationSec.toDouble() / input.distanceKm).roundToInt()
                PaceCalculatorResult(
                    distanceKm = input.distanceKm,
                    durationSec = input.durationSec,
                    paceSecPerKm = paceSecPerKm,
                    computedField = PaceField.PACE
                )
            }
        }
    }
}
