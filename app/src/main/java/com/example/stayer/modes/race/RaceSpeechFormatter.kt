package com.example.stayer.modes.race

import kotlin.math.abs

object RaceSpeechFormatter {
    /**
     * Формирует стартовую озвучку первого участка забега.
     * Builds the spoken start phrase for the first race segment.
     */
    fun buildStartSpeech(segment: RaceSegmentRange): String {
        return "Забег. Первый участок ${formatDistance(segment.distanceKm)}. Темп ${formatPace(segment.targetPaceSecPerKm)}."
    }

    /**
     * Формирует озвучку перехода на следующий участок забега.
     * Builds the spoken transition phrase for the next race segment.
     */
    fun buildTransitionSpeech(segment: RaceSegmentRange): String {
        return "Внимание. Следующий участок ${formatDistance(segment.distanceKm)}. Темп ${formatPace(segment.targetPaceSecPerKm)}."
    }

    /**
     * Формирует озвучку глобального чекпоинта забега.
     * Builds the spoken phrase for a global race checkpoint.
     */
    fun buildCheckpointSpeech(
        distanceKm: Double,
        elapsedSec: Int,
        avgPaceSecPerKm: Int,
        deltaSec: Int
    ): String {
        return buildString {
            append("Дистанция ${formatDistance(distanceKm)}. ")
            append("Общее время ${formatDuration(elapsedSec)}. ")
            append("Средний темп ${formatPace(avgPaceSecPerKm)}. ")
            append(formatDelta(deltaSec))
        }
    }

    /**
     * Формирует озвучку ориентировочного статуса перед финишем.
     * Builds the spoken near-finish guidance phrase.
     */
    fun buildFinishAlertSpeech(remainingKm: Int, deltaSec: Int): String {
        return "До финиша ${formatDistance(remainingKm.toDouble())}. ${formatDelta(deltaSec)}"
    }

    private fun formatDelta(deltaSec: Int): String {
        val absDelta = abs(deltaSec)
        return when {
            absDelta <= 5 -> "Идёте близко к плану."
            deltaSec > 0 -> "Ориентировочное отставание ${formatDuration(absDelta)}."
            else -> "Ориентировочное опережение ${formatDuration(absDelta)}."
        }
    }

    private fun formatDistance(distanceKm: Double): String {
        return if (distanceKm % 1.0 == 0.0) {
            val wholeKm = distanceKm.toInt()
            unitText(wholeKm, "километр", "километра", "километров")
        } else {
            "${String.format("%.1f", distanceKm)} километра"
        }
    }

    private fun formatDuration(totalSec: Int): String {
        if (totalSec <= 0) return "0 секунд"

        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        val parts = mutableListOf<String>()

        if (hours > 0) parts += unitText(hours, "час", "часа", "часов")
        if (minutes > 0) parts += unitText(minutes, "минута", "минуты", "минут")
        if (seconds > 0 || parts.isEmpty()) parts += unitText(seconds, "секунда", "секунды", "секунд")

        return parts.joinToString(" ")
    }

    private fun formatPace(secPerKm: Int): String {
        val minutes = secPerKm / 60
        val seconds = secPerKm % 60
        val parts = mutableListOf<String>()
        if (minutes > 0) parts += unitText(minutes, "минута", "минуты", "минут")
        if (seconds > 0 || parts.isEmpty()) parts += unitText(seconds, "секунда", "секунды", "секунд")
        return parts.joinToString(" ")
    }

    private fun unitText(value: Int, one: String, few: String, many: String): String {
        val mod100 = value % 100
        val mod10 = value % 10
        val unit = when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
        return "$value $unit"
    }
}
