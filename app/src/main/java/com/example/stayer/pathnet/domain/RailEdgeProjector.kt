package com.example.stayer.pathnet.domain

import com.example.stayer.pathnet.model.GeoPoint

/**
 * Проецирует GPS-точку на одно runtime-ребро сети.
 * Projects a GPS point onto a single runtime network edge.
 */
object RailEdgeProjector {
    /**
     * Находит ближайшую точку на ребре и позицию вдоль него (s).
     * Finds the nearest point on the edge and its arc-length position (s).
     */
    fun project(point: GeoPoint, edge: RailEdge): RailEdgeProjection? {
        val projection = PathGeometryProjector.nearestPointOnPolyline(point, edge.points) ?: return null
        val segmentStart = edge.cumulativeMeters[projection.segmentIndex]
        val segmentEnd = edge.cumulativeMeters[projection.segmentIndex + 1]
        val sMeters = segmentStart + (segmentEnd - segmentStart) * projection.segmentFraction
        return RailEdgeProjection(
            point = projection.point,
            sMeters = sMeters,
            distanceMeters = projection.distanceMeters,
        )
    }

    /**
     * Возвращает точку на ребре по позиции s вдоль дуги.
     * Returns the point on an edge at arc-length position s.
     */
    fun pointAtArcLength(edge: RailEdge, sMeters: Double): GeoPoint? {
        if (edge.points.isEmpty()) return null
        val length = edge.lengthMeters
        if (length <= 0.0) return edge.points.first()
        val clamped = when {
            edge.isClosed -> {
                var s = sMeters % length
                if (s < 0) s += length
                s
            }
            else -> sMeters.coerceIn(0.0, length)
        }
        val index = edge.cumulativeMeters.indexOfLast { it <= clamped + 1e-9 }
        if (index < 0 || index >= edge.points.lastIndex) {
            return edge.points.lastOrNull()
        }
        val segmentStart = edge.cumulativeMeters[index]
        val segmentEnd = edge.cumulativeMeters[index + 1]
        val span = segmentEnd - segmentStart
        val fraction = if (span <= 1e-9) 0.0 else (clamped - segmentStart) / span
        val start = edge.points[index]
        val end = edge.points[index + 1]
        return GeoPoint(
            lat = start.lat + (end.lat - start.lat) * fraction,
            lon = start.lon + (end.lon - start.lon) * fraction,
        )
    }
}

/**
 * Результат проекции точки на ребро.
 * Result of projecting a point onto an edge.
 */
data class RailEdgeProjection(
    val point: GeoPoint,
    val sMeters: Double,
    val distanceMeters: Double,
)
