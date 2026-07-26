package com.example.stayer.pathnet.model

/**
 * Географическая точка линии или узла.
 * Geographic point used by nodes and segment geometry.
 */
data class GeoPoint(
    val lat: Double,
    val lon: Double,
)
