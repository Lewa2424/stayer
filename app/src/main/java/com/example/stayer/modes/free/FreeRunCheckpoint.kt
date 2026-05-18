package com.example.stayer.modes.free

data class FreeRunCheckpoint(
    val kilometerMark: Int,
    val elapsedSec: Int,
    val avgPaceSecPerKm: Int
)
