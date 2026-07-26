package com.example.stayer.pathnet.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Сущность импортированного узла тропинки для Room.
 * Room entity for an imported path node.
 */
@Entity(tableName = "imported_path_nodes")
data class ImportedPathNodeEntity(
    @PrimaryKey val id: String,
    val lat: Double,
    val lon: Double,
)
