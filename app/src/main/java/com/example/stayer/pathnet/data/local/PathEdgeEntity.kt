package com.example.stayer.pathnet.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Сущность сегмента для Room.
 * Room entity for a path edge.
 */
@Entity(tableName = "path_edges")
data class PathEdgeEntity(
    @PrimaryKey val id: String,
    val startNodeId: String,
    val endNodeId: String,
    val geometry: List<StoredGeoPoint>,
    val lengthMeters: Double,
    val source: String,
)

/**
 * Точка геометрии, хранимая в базе.
 * Stored geometry point used by Room converters.
 */
data class StoredGeoPoint(
    val lat: Double,
    val lon: Double,
)
