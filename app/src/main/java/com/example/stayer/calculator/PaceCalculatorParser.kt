package com.example.stayer.calculator

/**
 * Парсит дистанцию в километрах.
 * Parses distance in kilometers.
 */
fun parseDistanceKm(raw: String): Double? {
    val value = raw.trim().replace(',', '.')
    if (value.isEmpty()) return null
    return value.toDoubleOrNull()?.takeIf { it > 0.0 }
}

/**
 * Парсит длительность формата MM:SS или HH:MM:SS.
 * Parses duration in MM:SS or HH:MM:SS format.
 */
fun parseDurationSec(raw: String): Int? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    val parts = value.split(":")
    return try {
        when (parts.size) {
            2 -> {
                val minutes = parts[0].toInt()
                val seconds = parts[1].toInt()
                if (minutes < 0 || seconds !in 0..59) return null
                minutes * 60 + seconds
            }
            3 -> {
                val hours = parts[0].toInt()
                val minutes = parts[1].toInt()
                val seconds = parts[2].toInt()
                if (hours < 0 || minutes !in 0..59 || seconds !in 0..59) return null
                hours * 3600 + minutes * 60 + seconds
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Парсит темп формата MM:SS на километр.
 * Parses pace in MM:SS per kilometer format.
 */
fun parsePaceSecPerKm(raw: String): Int? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    val parts = value.split(":")
    return try {
        if (parts.size != 2) return null
        val minutes = parts[0].toInt()
        val seconds = parts[1].toInt()
        if (minutes < 0 || seconds !in 0..59) return null
        minutes * 60 + seconds
    } catch (_: Exception) {
        null
    }
}
