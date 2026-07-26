package com.example.stayer.pathnet.model

/**
 * Узел локального графа дорожек.
 * Graph node for the local editable network.
 */
data class PathNode(
    val id: String,
    val point: GeoPoint,
)
