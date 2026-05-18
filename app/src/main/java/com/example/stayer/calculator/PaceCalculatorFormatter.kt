package com.example.stayer.calculator

import java.util.Locale

/**
 * Форматирует дистанцию в километрах.
 * Formats distance in kilometers.
 */
fun formatDistanceKm(distanceKm: Double): String {
    return String.format(Locale.getDefault(), "%.2f", distanceKm)
}

/**
 * Форматирует длительность в HH:MM:SS.
 * Formats duration as HH:MM:SS.
 */
fun formatDurationSec(totalSec: Int): String {
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}

/**
 * Форматирует темп в MM:SS на километр.
 * Formats pace in MM:SS per kilometer.
 */
fun formatPaceSecPerKm(secPerKm: Int): String {
    val minutes = secPerKm / 60
    val seconds = secPerKm % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
