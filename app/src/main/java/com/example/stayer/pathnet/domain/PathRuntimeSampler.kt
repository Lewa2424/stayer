package com.example.stayer.pathnet.domain

import android.location.Location
import com.example.stayer.pathnet.model.GeoPoint
import com.example.stayer.pathnet.model.PathEdge

/**
 * Подготавливает плотное представление линии для будущего GPS snap.
 * Builds a dense runtime representation for future GPS snapping.
 */
object PathRuntimeSampler {
    /**
     * Семплирует линию сегмента с заданным шагом.
     * Samples an edge geometry using a fixed step.
     */
    fun sampleEdge(edge: PathEdge, stepMeters: Double = 2.0): List<GeoPoint> {
        if (edge.geometry.size < 2) return edge.geometry
        val sampled = mutableListOf(edge.geometry.first())
        for (index in 0 until edge.geometry.lastIndex) {
            val start = edge.geometry[index]
            val end = edge.geometry[index + 1]
            val result = FloatArray(1)
            Location.distanceBetween(start.lat, start.lon, end.lat, end.lon, result)
            val distance = result[0].toDouble()
            val steps = kotlin.math.max(1, kotlin.math.floor(distance / stepMeters).toInt())
            for (step in 1 until steps) {
                val ratio = step.toDouble() / steps.toDouble()
                sampled += GeoPoint(
                    lat = start.lat + (end.lat - start.lat) * ratio,
                    lon = start.lon + (end.lon - start.lon) * ratio,
                )
            }
            sampled += end
        }
        return sampled
    }
}
