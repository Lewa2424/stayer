package com.example.stayer.pathnet.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Сущность узла для Room.
 * Room entity for a path node.
 */
@Entity(tableName = "path_nodes")
data class PathNodeEntity(
    @PrimaryKey val id: String,
    val lat: Double,
    val lon: Double,
)
