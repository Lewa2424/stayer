package com.example.stayer.pathnet.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Сущность импортированной тропинки для Room.
 * Room entity for an imported path way.
 */
@Entity(tableName = "imported_path_ways")
data class ImportedWayEntity(
    @PrimaryKey val id: String,
    val highwayType: String,
    val nodeIds: List<String>,
)
