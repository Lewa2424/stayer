package com.example.stayer.engine

/**
 * Оценщик текущего темпа для главного экрана.
 * Считает темп по скользящему окну дистанции и времени, отбрасывая слишком короткие и нереалистичные отрезки.
 *
 * Current pace estimator for the main screen.
 * Calculates pace from a rolling distance/time window and rejects too short or unrealistic samples.
 */
class CurrentPaceEstimator(
    private val windowSec: Double = 30.0,
    private val minObservedSec: Double = 12.0,
    private val minDistanceM: Double = 20.0,
    private val minPaceSecPerKm: Int = 180,
    private val maxPaceSecPerKm: Int = 1200
) {
    private data class Sample(
        var durationSec: Double,
        var distanceM: Double
    )

    private val samples = ArrayDeque<Sample>()
    private var windowDurationSec = 0.0
    private var observedDurationSec = 0.0

    /**
     * Добавляет новый отрезок движения в окно расчёта.
     * Adds a new movement segment to the calculation window.
     */
    fun feed(deltaM: Double, durationSec: Double) {
        val safeDuration = durationSec.coerceAtLeast(0.0)
        if (safeDuration <= 0.0) return

        val safeDistance = deltaM.coerceAtLeast(0.0)
        samples.add(Sample(safeDuration, safeDistance))
        windowDurationSec += safeDuration
        observedDurationSec += safeDuration
        trimToWindow()
    }

    /**
     * Возвращает текущий темп в секундах на километр или null, если данных недостаточно.
     * Returns current pace in seconds per kilometer, or null when data is insufficient.
     */
    fun currentPaceSecPerKm(): Int? {
        if (observedDurationSec < minObservedSec) return null
        if (windowDurationSec < minObservedSec) return null

        val totalMeters = samples.sumOf { it.distanceM }
        if (totalMeters < minDistanceM) return null

        val speedMps = totalMeters / windowDurationSec
        if (speedMps <= 0.1) return null

        val pace = (1000.0 / speedMps).toInt()
        if (pace !in minPaceSecPerKm..maxPaceSecPerKm) return null
        return pace
    }

    /**
     * Очищает накопленные данные темпа.
     * Clears accumulated pace data.
     */
    fun reset() {
        samples.clear()
        windowDurationSec = 0.0
        observedDurationSec = 0.0
    }

    private fun trimToWindow() {
        while (windowDurationSec > windowSec + 1e-9 && samples.isNotEmpty()) {
            val head = samples.first()
            val overflowSec = windowDurationSec - windowSec
            if (head.durationSec <= overflowSec + 1e-9) {
                samples.removeFirst()
                windowDurationSec -= head.durationSec
            } else {
                val ratio = overflowSec / head.durationSec
                head.distanceM -= head.distanceM * ratio
                head.durationSec -= overflowSec
                windowDurationSec = windowSec
            }
        }
    }
}
