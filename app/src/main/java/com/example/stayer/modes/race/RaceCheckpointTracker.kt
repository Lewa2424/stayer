package com.example.stayer.modes.race

data class RaceGlobalCheckpoint(
    val index: Int,
    val distanceKm: Double
)

class RaceCheckpointTracker {
    private var lastCheckpointIndex = 0
    private val announcedFinishAlerts = mutableSetOf<Int>()

    /**
     * Сбрасывает внутреннее состояние чекпоинтов забега.
     * Resets the internal race checkpoint state.
     */
    fun reset() {
        lastCheckpointIndex = 0
        announcedFinishAlerts.clear()
    }

    /**
     * Возвращает следующий закрытый глобальный чекпоинт забега.
     * Returns the next completed global race checkpoint.
     */
    fun nextCheckpoint(distanceKm: Double, totalDistanceKm: Double): RaceGlobalCheckpoint? {
        val stepKm = RaceProgressEvaluator.checkpointStepKm(totalDistanceKm)
        if (stepKm == Double.MAX_VALUE || distanceKm < stepKm) return null

        val currentIndex = (distanceKm / stepKm).toInt().coerceAtMost(10)
        if (currentIndex <= lastCheckpointIndex) return null

        lastCheckpointIndex = currentIndex
        return RaceGlobalCheckpoint(
            index = currentIndex,
            distanceKm = stepKm * currentIndex
        )
    }

    /**
     * Возвращает предупреждение за 2 или 1 км до финиша один раз.
     * Returns a one-time finish warning for 2 km or 1 km remaining.
     */
    fun nextFinishAlert(distanceKm: Double, totalDistanceKm: Double): Int? {
        val remainingKm = totalDistanceKm - distanceKm
        return when {
            remainingKm <= 1.0 && announcedFinishAlerts.add(1) -> 1
            remainingKm <= 2.0 && announcedFinishAlerts.add(2) -> 2
            else -> null
        }
    }
}
