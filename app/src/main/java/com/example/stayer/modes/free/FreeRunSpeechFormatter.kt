package com.example.stayer.modes.free

object FreeRunSpeechFormatter {
    /**
     * Формирует голосовую фразу для очередного километра свободного бега.
     * Builds the spoken phrase for the next free run kilometer checkpoint.
     */
    fun buildCheckpointSpeech(checkpoint: FreeRunCheckpoint): String {
        val distanceText = formatKilometers(checkpoint.kilometerMark)
        val elapsedText = formatDuration(checkpoint.elapsedSec)
        val paceText = formatPace(checkpoint.avgPaceSecPerKm)
        return "Дистанция $distanceText. Общее время $elapsedText. Средний темп $paceText."
    }

    /**
     * Форматирует количество километров для голосовой фразы.
     * Formats a kilometer count for spoken output.
     */
    fun formatKilometers(kilometers: Int): String {
        return unitText(kilometers, "километр", "километра", "километров")
    }

    /**
     * Форматирует длительность в удобную для озвучки форму.
     * Formats a duration into speech-friendly wording.
     */
    fun formatDuration(totalSec: Int): String {
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

    /**
     * Форматирует средний темп для голосовой фразы.
     * Formats average pace for spoken output.
     */
    fun formatPace(secPerKm: Int): String {
        if (secPerKm <= 0) return "0 секунд на километр"

        val minutes = secPerKm / 60
        val seconds = secPerKm % 60
        val parts = mutableListOf<String>()

        if (minutes > 0) parts += unitText(minutes, "минута", "минуты", "минут")
        if (seconds > 0 || parts.isEmpty()) parts += unitText(seconds, "секунда", "секунды", "секунд")

        return "${parts.joinToString(" ")} на километр"
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
