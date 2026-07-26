package com.example.stayer.pathnet.domain

import android.location.Location
import com.example.stayer.pathnet.model.GeoPoint

/**
 * Утилиты работы с геометрией сегментов.
 * Geometry helpers for path editing and snapping.
 */
object PathGeometryProjector {
    /**
     * Возвращает ближайшую точку на линии и расстояние до нее.
     * Finds the nearest point on a polyline and the distance to it.
     */
    fun nearestPointOnPolyline(
        point: GeoPoint,
        geometry: List<GeoPoint>,
    ): ProjectionResult? {
        if (geometry.size < 2) return null
        var best: ProjectionResult? = null
        for (index in 0 until geometry.lastIndex) {
            val candidate = projectToSegment(
                point = point,
                start = geometry[index],
                end = geometry[index + 1],
                segmentIndex = index,
            )
            if (best == null || candidate.distanceMeters < best.distanceMeters) {
                best = candidate
            }
        }
        return best
    }

    /**
     * Проецирует точку на один отрезок.
     * Projects a point onto a single segment.
     */
    private fun projectToSegment(
        point: GeoPoint,
        start: GeoPoint,
        end: GeoPoint,
        segmentIndex: Int,
    ): ProjectionResult {
        val latScale = 111_320.0
        val lonScale = 111_320.0 * kotlin.math.cos(Math.toRadians((start.lat + end.lat) / 2.0))
        val px = (point.lon - start.lon) * lonScale
        val py = (point.lat - start.lat) * latScale
        val ex = (end.lon - start.lon) * lonScale
        val ey = (end.lat - start.lat) * latScale
        val lenSquared = ex * ex + ey * ey
        val t = if (lenSquared == 0.0) 0.0 else ((px * ex) + (py * ey)) / lenSquared
        val clamped = t.coerceIn(0.0, 1.0)
        val projected = GeoPoint(
            lat = start.lat + (end.lat - start.lat) * clamped,
            lon = start.lon + (end.lon - start.lon) * clamped,
        )
        val result = FloatArray(1)
        Location.distanceBetween(point.lat, point.lon, projected.lat, projected.lon, result)
        return ProjectionResult(
            point = projected,
            distanceMeters = result[0].toDouble(),
            segmentIndex = segmentIndex,
            segmentFraction = clamped,
        )
    }
}

/**
 * Результат геометрической проекции на линию.
 * Result of projecting a point onto a polyline.
 */
data class ProjectionResult(
    val point: GeoPoint,
    val distanceMeters: Double,
    val segmentIndex: Int,
    val segmentFraction: Double,
)
