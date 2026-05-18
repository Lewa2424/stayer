package com.example.stayer.modes.race

import java.util.Locale

object RacePlanText {
    /**
     * Строит краткую подпись режима забега.
     * Builds a compact summary line for the race mode.
     */
    fun buildSummary(json: String?): String? {
        val plan = parse(json) ?: return null
        val firstPace = plan.segments.firstOrNull()?.targetPaceSecPerKm
        val paceText = firstPace?.let { " • старт ${formatPace(it)}" }.orEmpty()
        return String.format(Locale.getDefault(), "%.1f км • %d уч.%s", plan.totalDistanceKm, plan.segments.size, paceText)
    }

    /**
     * Строит превью плана забега для главного экрана.
     * Builds a race plan preview for the main screen.
     */
    fun buildPreview(json: String?): String {
        val plan = parse(json) ?: return ""
        val lines = RaceProgressEvaluator.segmentRanges(plan).map { range ->
            "${String.format(Locale.getDefault(), "%.1f", range.fromKm)}-${String.format(Locale.getDefault(), "%.1f", range.toKm)} км  ${formatPace(range.targetPaceSecPerKm)}"
        }.toMutableList()
        lines.add("────────")
        lines.add("Итого: ${String.format(Locale.getDefault(), "%.1f", plan.totalDistanceKm)} км")
        return lines.joinToString("\n")
    }

    /**
     * Строит подпись цели для истории забега.
     * Builds the compact history goal label for the race mode.
     */
    fun buildGoalLabel(json: String?): String {
        val plan = parse(json) ?: return raceLabel()
        return "${raceLabel()} • ${String.format(Locale.getDefault(), "%.1f", plan.totalDistanceKm)} км"
    }

    private fun parse(json: String?): RacePlan? {
        if (json.isNullOrBlank()) return null
        return runCatching { com.google.gson.Gson().fromJson(json, RacePlan::class.java) }.getOrNull()
    }

    private fun formatPace(secPerKm: Int): String {
        val minutes = secPerKm / 60
        val seconds = secPerKm % 60
        return String.format(Locale.getDefault(), "%d:%02d/км", minutes, seconds)
    }
}
