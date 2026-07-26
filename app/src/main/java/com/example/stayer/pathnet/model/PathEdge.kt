package com.example.stayer.pathnet.model

/**
 * Сегмент пользовательской сети.
 * Editable user-facing segment in the local graph.
 */
data class PathEdge(
    val id: String,
    val startNodeId: String,
    val endNodeId: String,
    val geometry: List<GeoPoint>,
    val lengthMeters: Double,
    val source: PathEdgeSource,
)
