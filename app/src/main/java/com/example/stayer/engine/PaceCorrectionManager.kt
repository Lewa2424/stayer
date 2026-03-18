package com.example.stayer.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Smart Pace Corrector: real-time pace monitoring with a 30-second rolling window.
 *
 * Principles:
 *  - Silent for the first 30 seconds after reset (buffer warming up)
 *  - Silent while inside the +/-10 sec/km corridor around the target pace
 *  - Silent during cooldown (30 s after any TTS event from any source)
 *  - Silent on windows with a sharp turn inside the last 30 seconds
 *  - Speaks only after the same correction bucket is observed consistently
 */
class PaceCorrectionManager {

    private data class Sample(
        var durationSec: Double,
        var distanceM: Double
    )

    private data class GpsPoint(
        val latDeg: Double,
        val lonDeg: Double,
        val elapsedSec: Double
    )

    companion object {
        private const val WINDOW_SEC = 30.0
        private const val CORRIDOR_SEC = 7
        const val COOLDOWN_MS = 30_000L
        private const val MIN_DISTANCE_M = 15.0

        private const val MIN_GPS_POINT_DISTANCE_M = 3.0
        private const val MIN_GPS_POINT_INTERVAL_SEC = 1.0
        private const val MIN_GPS_SEGMENTS_FOR_GEOMETRY = 3
        private const val MAX_LOCAL_TURN_ANGLE_DEG = 45.0

        private const val CONFIRM_TICKS = 6
        private const val CONFIRM_TICKS_OPPOSITE = 8

        private const val EARTH_RADIUS_M = 6_371_000.0
    }

    private val buffer: ArrayDeque<Sample> = ArrayDeque()
    private val gpsWindow: ArrayDeque<GpsPoint> = ArrayDeque()

    private var bufferDurationSec: Double = 0.0
    private var observedDurationSec: Double = 0.0
    private var cooldownUntilMs: Long = 0L

    private var pendingBucket = 0
    private var pendingBucketTicks = 0
    private var lastSpokenDirection = 0

    fun feedSample(deltaM: Double, durationSec: Double) {
        val safeDurationSec = durationSec.coerceAtLeast(0.0)
        if (safeDurationSec <= 0.0) return

        val safeDistanceM = deltaM.coerceAtLeast(0.0)
        buffer.addLast(Sample(safeDurationSec, safeDistanceM))
        bufferDurationSec += safeDurationSec
        observedDurationSec += safeDurationSec

        trimToWindow()
    }

    fun feedGpsPoint(latDeg: Double, lonDeg: Double, elapsedSec: Double) {
        val safeElapsedSec = elapsedSec.coerceAtLeast(0.0)
        val nextPoint = GpsPoint(latDeg, lonDeg, safeElapsedSec)
        val lastPoint = gpsWindow.lastOrNull()

        if (lastPoint != null) {
            val dtSec = safeElapsedSec - lastPoint.elapsedSec
            val distM = distanceMeters(lastPoint, nextPoint)
            if (dtSec < MIN_GPS_POINT_INTERVAL_SEC && distM < MIN_GPS_POINT_DISTANCE_M) {
                gpsWindow.removeLast()
                gpsWindow.addLast(nextPoint)
                pruneGpsWindow(safeElapsedSec)
                return
            }
        }

        gpsWindow.addLast(nextPoint)
        pruneGpsWindow(safeElapsedSec)
    }

    fun maybeSuggest(
        targetPaceSecPerKm: Int,
        currentTimeMs: Long,
        currentElapsedSec: Double? = null
    ): String? {
        if (observedDurationSec < WINDOW_SEC) return null
        if (bufferDurationSec + 1e-6 < WINDOW_SEC) return null
        if (currentTimeMs < cooldownUntilMs) return null

        currentElapsedSec?.let { pruneGpsWindow(it) }
        if (!isGeometryWindowReliable()) {
            resetPending()
            return null
        }

        val totalMeters = buffer.sumOf { it.distanceM }
        if (totalMeters < MIN_DISTANCE_M) return null
        val speed = totalMeters / bufferDurationSec
        if (speed <= 0.1) return null
        val currentPace = (1000.0 / speed).toInt()

        val diff = currentPace - targetPaceSecPerKm
        val bucket = bucketForDiff(diff)
        if (bucket == 0) {
            resetPending()
            return null
        }

        if (bucket != pendingBucket) {
            pendingBucket = bucket
            pendingBucketTicks = 1
            return null
        }

        pendingBucketTicks += 1
        val requiredTicks = requiredTicksFor(bucket)
        if (pendingBucketTicks < requiredTicks) return null

        val suggestion = phraseForBucket(bucket)
        lastSpokenDirection = bucket.sign()
        resetPending()
        return suggestion
    }

    fun triggerCooldown(currentTimeMs: Long, durationMs: Long = COOLDOWN_MS) {
        cooldownUntilMs = currentTimeMs + durationMs
    }

    fun reset() {
        buffer.clear()
        gpsWindow.clear()
        bufferDurationSec = 0.0
        observedDurationSec = 0.0
        resetPending()
        lastSpokenDirection = 0
    }

    private fun trimToWindow() {
        while (bufferDurationSec > WINDOW_SEC + 1e-9 && buffer.isNotEmpty()) {
            val head = buffer.first()
            val overflowSec = bufferDurationSec - WINDOW_SEC

            if (head.durationSec <= overflowSec + 1e-9) {
                buffer.removeFirst()
                bufferDurationSec -= head.durationSec
            } else {
                val ratio = overflowSec / head.durationSec
                head.distanceM -= head.distanceM * ratio
                head.durationSec -= overflowSec
                bufferDurationSec = WINDOW_SEC
            }
        }
    }

    private fun pruneGpsWindow(currentElapsedSec: Double) {
        while (gpsWindow.isNotEmpty() && currentElapsedSec - gpsWindow.first().elapsedSec > WINDOW_SEC + 1e-9) {
            gpsWindow.removeFirst()
        }
    }

    private fun isGeometryWindowReliable(): Boolean {
        if (gpsWindow.size < MIN_GPS_SEGMENTS_FOR_GEOMETRY) return true

        var maxTurnAngle = 0.0
        for (i in 1 until gpsWindow.lastIndex) {
            val angle = turnAngleDeg(gpsWindow[i - 1], gpsWindow[i], gpsWindow[i + 1]) ?: continue
            maxTurnAngle = max(maxTurnAngle, angle)
            if (maxTurnAngle > MAX_LOCAL_TURN_ANGLE_DEG) return false
        }
        return true
    }

    private fun turnAngleDeg(a: GpsPoint, b: GpsPoint, c: GpsPoint): Double? {
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = metersPerDegLat * cos(Math.toRadians((a.latDeg + b.latDeg + c.latDeg) / 3.0))

        val v1x = (b.lonDeg - a.lonDeg) * metersPerDegLon
        val v1y = (b.latDeg - a.latDeg) * metersPerDegLat
        val v2x = (c.lonDeg - b.lonDeg) * metersPerDegLon
        val v2y = (c.latDeg - b.latDeg) * metersPerDegLat

        val len1 = sqrt(v1x * v1x + v1y * v1y)
        val len2 = sqrt(v2x * v2x + v2y * v2y)
        if (len1 < MIN_GPS_POINT_DISTANCE_M || len2 < MIN_GPS_POINT_DISTANCE_M) return null

        val dot = (v1x * v2x + v1y * v2y) / (len1 * len2)
        return Math.toDegrees(acos(min(1.0, max(-1.0, dot))))
    }

    private fun distanceMeters(a: GpsPoint, b: GpsPoint): Double {
        val lat1 = Math.toRadians(a.latDeg)
        val lat2 = Math.toRadians(b.latDeg)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lonDeg - a.lonDeg)
        val sinDLat = kotlin.math.sin(dLat / 2.0)
        val sinDLon = kotlin.math.sin(dLon / 2.0)
        val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
        return 2.0 * EARTH_RADIUS_M * kotlin.math.asin(kotlin.math.sqrt(h))
    }

    private fun bucketForDiff(diffSecPerKm: Int): Int {
        val absDiff = abs(diffSecPerKm)
        if (absDiff <= CORRIDOR_SEC) return 0

        val magnitude = when {
            absDiff <= 15 -> 1
            absDiff <= 25 -> 2
            else -> 3
        }
        return if (diffSecPerKm > 0) magnitude else -magnitude
    }

    private fun requiredTicksFor(bucket: Int): Int {
        return if (lastSpokenDirection != 0 && bucket.sign() != lastSpokenDirection) {
            CONFIRM_TICKS_OPPOSITE
        } else {
            CONFIRM_TICKS
        }
    }

    private fun phraseForBucket(bucket: Int): String {
        return when (bucket) {
            1 -> "Чуть быстрее"
            2 -> "Немного быстрее"
            3 -> "Быстрее"
            -1 -> "Чуть медленнее"
            -2 -> "Немного медленнее"
            else -> "Медленнее"
        }
    }

    private fun resetPending() {
        pendingBucket = 0
        pendingBucketTicks = 0
    }

    private fun Int.sign(): Int = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }
}
