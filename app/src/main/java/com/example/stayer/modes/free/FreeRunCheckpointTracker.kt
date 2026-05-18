package com.example.stayer.modes.free

import kotlin.math.roundToInt

class FreeRunCheckpointTracker {
    private var lastAnnouncedKilometer = 0

    /**
     * Сбрасывает внутреннее состояние чекпоинтов свободного бега.
     * Resets the internal free run checkpoint state.
     */
    fun reset() {
        lastAnnouncedKilometer = 0
    }

    /**
     * Возвращает новый чекпоинт, когда бегун пересёк очередной полный километр.
     * Returns a new checkpoint once the runner crosses the next full kilometer.
     */
    fun nextCheckpoint(distanceKm: Float, elapsedSec: Int): FreeRunCheckpoint? {
        if (distanceKm < 1f || elapsedSec <= 0) return null

        val kilometerMark = distanceKm.toInt()
        if (kilometerMark <= lastAnnouncedKilometer) return null

        val avgPaceSecPerKm = (elapsedSec / kilometerMark.toFloat()).roundToInt()
        if (avgPaceSecPerKm <= 0) return null

        lastAnnouncedKilometer = kilometerMark
        return FreeRunCheckpoint(
            kilometerMark = kilometerMark,
            elapsedSec = elapsedSec,
            avgPaceSecPerKm = avgPaceSecPerKm
        )
    }
}
