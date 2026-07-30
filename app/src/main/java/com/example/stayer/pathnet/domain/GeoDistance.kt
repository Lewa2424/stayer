package com.example.stayer.pathnet.domain

import com.example.stayer.pathnet.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Чистая гео-дистанция без Android Location (JVM-тесты / domain).
 * Pure geo distance without Android Location (JVM tests / domain).
 */
object GeoDistance {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Haversine-расстояние в метрах.
     * Haversine distance in meters.
     */
    fun meters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }
}
