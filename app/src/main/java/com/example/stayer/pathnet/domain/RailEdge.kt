package com.example.stayer.pathnet.domain

import com.example.stayer.pathnet.model.GeoPoint

/**
 * Runtime-сегмент сети для сопоставления GPS: плотная геометрия и накопленная длина.
 * Runtime network edge for GPS matching: dense geometry with cumulative arc length.
 *
 * @property edgeId идентификатор сегмента из графа. Edge id from the stored graph.
 * @property startNodeId узел начала сегмента. Start node id.
 * @property endNodeId узел конца сегмента. End node id.
 * @property points плотные точки геометрии по порядку. Dense geometry points in order.
 * @property cumulativeMeters накопленная длина для каждой точки. Cumulative length per point.
 */
data class RailEdge(
    val edgeId: String,
    val startNodeId: String,
    val endNodeId: String,
    val points: List<GeoPoint>,
    val cumulativeMeters: List<Double>,
) {
    /** Полная длина сегмента в метрах. Total edge length in meters. */
    val lengthMeters: Double
        get() = cumulativeMeters.lastOrNull() ?: 0.0

    /** true, если сегмент замкнут сам на себя (кольцо). True when the edge is a closed ring. */
    val isClosed: Boolean
        get() = startNodeId == endNodeId
}
