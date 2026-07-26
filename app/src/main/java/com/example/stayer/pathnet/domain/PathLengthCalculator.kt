package com.example.stayer.pathnet.domain

import android.location.Location
import com.example.stayer.pathnet.model.GeoPoint
import com.example.stayer.pathnet.model.PathGraph

/**
 * Считает длину сегментов и всей сети.
 * Calculates edge and graph lengths.
 */
object PathLengthCalculator {
    /**
     * Считает длину линии в метрах.
     * Calculates the length of a polyline in meters.
     */
    fun calculatePolylineLength(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        val result = FloatArray(1)
        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            Location.distanceBetween(start.lat, start.lon, end.lat, end.lon, result)
            total += result[0].toDouble()
        }
        return total
    }

    /**
     * Считает суммарную длину графа.
     * Calculates total graph length.
     */
    fun calculateGraphLength(graph: PathGraph): Double {
        return graph.edges.values.sumOf { it.lengthMeters }
    }
}
