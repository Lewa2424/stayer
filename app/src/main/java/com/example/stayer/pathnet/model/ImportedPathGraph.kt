package com.example.stayer.pathnet.model

/**
 * Временный граф тропинок, полученный из карты.
 * Temporary imported graph fetched from map data.
 */
data class ImportedPathGraph(
    val nodes: Map<String, GeoPoint> = emptyMap(),
    val ways: List<ImportedWay> = emptyList(),
)

/**
 * Импортированный путь из внешних картографических данных.
 * Imported routable path built from external map data.
 */
data class ImportedWay(
    val id: String,
    val highwayType: String,
    val nodeIds: List<String>,
)
