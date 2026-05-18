package com.example.stayer.modes.race

import kotlin.math.min
import kotlin.math.roundToInt

data class RaceSegmentRange(
    val index: Int,
    val fromKm: Double,
    val toKm: Double,
    val distanceKm: Double,
    val targetPaceSecPerKm: Int
)

object RaceProgressEvaluator {
    /**
     * Возвращает глобальный шаг чекпоинтов для всего забега.
     * Returns the global checkpoint step for the whole race.
     */
    fun checkpointStepKm(totalDistanceKm: Double): Double {
        if (totalDistanceKm <= 0.0) return Double.MAX_VALUE
        return totalDistanceKm * 0.1
    }

    /**
     * Строит диапазоны участков забега на общей дистанции.
     * Builds segment ranges over the whole race distance.
     */
    fun segmentRanges(plan: RacePlan): List<RaceSegmentRange> {
        var cursor = 0.0
        return plan.segments.mapIndexed { index, segment ->
            val from = cursor
            val to = cursor + segment.distanceKm
            cursor = to
            RaceSegmentRange(
                index = index,
                fromKm = from,
                toKm = to,
                distanceKm = segment.distanceKm,
                targetPaceSecPerKm = segment.targetPaceSecPerKm
            )
        }
    }

    /**
     * Возвращает активный участок для текущей дистанции.
     * Returns the active segment for the current distance.
     */
    fun activeSegment(plan: RacePlan, distanceKm: Double): RaceSegmentRange? {
        val ranges = segmentRanges(plan)
        if (ranges.isEmpty()) return null
        return ranges.firstOrNull { distanceKm < it.toKm } ?: ranges.last()
    }

    /**
     * Считает плановое время на указанной дистанции по профилю темпов.
     * Computes planned elapsed time at a given distance from the pace profile.
     */
    fun targetElapsedSecAt(plan: RacePlan, distanceKm: Double): Int {
        if (distanceKm <= 0.0) return 0

        var remainingKm = min(distanceKm, plan.totalDistanceKm)
        var totalSec = 0.0
        for (segment in plan.segments) {
            if (remainingKm <= 0.0) break
            val coveredKm = min(segment.distanceKm, remainingKm)
            totalSec += coveredKm * segment.targetPaceSecPerKm
            remainingKm -= coveredKm
        }
        return totalSec.roundToInt()
    }
}
