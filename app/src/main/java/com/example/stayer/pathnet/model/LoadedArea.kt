package com.example.stayer.pathnet.model

/**
 * Метаданные области карты, подгруженной в редакторе.
 * Metadata for an area that has been requested in the editor.
 */
data class LoadedArea(
    val id: String,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val loadedAt: Long,
)
