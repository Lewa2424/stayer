package com.example.stayer.pathnet.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Сущность области карты для Room.
 * Room entity that tracks a loaded viewport.
 */
@Entity(tableName = "loaded_areas")
data class LoadedAreaEntity(
    @PrimaryKey val id: String,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val loadedAt: Long,
)
