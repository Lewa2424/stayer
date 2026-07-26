package com.example.stayer.pathnet.model

/**
 * Источник и состояние сегмента в локальной сети.
 * Describes how a segment was created or adjusted.
 */
enum class PathEdgeSource {
    AUTO_SNAPPED,
    MANUAL_ADJUSTED,
    MANUAL_FREEFORM,
}
