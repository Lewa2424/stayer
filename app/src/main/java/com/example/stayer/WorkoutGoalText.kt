package com.example.stayer

import com.google.gson.Gson
import java.util.Locale

data class GoalDisplayText(
    val value: String,
    val supporting: String?
)

object WorkoutGoalText {
    fun buildDisplay(goal: ActiveWorkoutGoal): GoalDisplayText {
        return when (goal.workoutMode) {
            1 -> GoalDisplayText(
                value = "Интервалы",
                supporting = buildIntervalGoalSummary(goal.intervalScenarioJson)
            )
            2 -> GoalDisplayText(
                value = "Комбо",
                supporting = buildComboGoalSummary(goal.comboScenarioJson)
            )
            else -> GoalDisplayText(
                value = goal.targetDistanceKm
                    ?.takeIf { it > 0f }
                    ?.let { String.format(Locale.getDefault(), "%.2f км", it) }
                    ?: "—",
                supporting = when {
                    goal.targetTimeSec != null && goal.targetTimeSec > 0 -> fmtHms(goal.targetTimeSec)
                    goal.targetPaceSecPerKm != null && goal.targetPaceSecPerKm > 0 -> fmtPace(goal.targetPaceSecPerKm)
                    else -> null
                }
            )
        }
    }

    fun buildScenarioPreview(goal: ActiveWorkoutGoal): String {
        return when (goal.workoutMode) {
            1 -> buildIntervalPreview(goal.intervalScenarioJson)
            2 -> buildComboPreview(goal.comboScenarioJson)
            else -> ""
        }
    }

    fun buildHistoryGoalLabel(goal: ActiveWorkoutGoal): String? {
        return when (goal.workoutMode) {
            1 -> buildIntervalGoalLabel(goal.intervalScenarioJson)
            2 -> buildComboGoalLabel(goal.comboScenarioJson)
            else -> buildNormalGoalLabel(goal)
        }
    }

    fun buildIntervalGoalSummary(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val scenario = Gson().fromJson(json, IntervalScenario::class.java)
            val totalSec = scenario.segments.sumOf { it.durationSec }
            val workCount = scenario.segments.count { it.type == "WORK" }
            "≈${fmtHms(totalSec)} • $workCount сер."
        }.getOrNull()
    }

    fun buildComboGoalSummary(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val scenario = comboGson().fromJson(json, ComboScenario::class.java)
            val totalDist = scenario.estimateTotalDistanceKm()
            val totalSec = scenario.estimateTotalTimeSec()
            "≈${String.format(Locale.getDefault(), "%.1f км", totalDist)} • ${fmtHms(totalSec)}"
        }.getOrNull()
    }

    private fun buildIntervalPreview(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return runCatching {
            val scenario = Gson().fromJson(json, IntervalScenario::class.java)
            val lines = mutableListOf<String>()
            var workCount = 0
            var workSec = 0
            var restSec = 0
            var workPace: Int? = null
            for (seg in scenario.segments) {
                when (seg.type) {
                    "WARMUP" -> {
                        val pace = seg.targetPaceSecPerKm?.let { fmtPace(it) } ?: ""
                        lines.add("Разм.  ${fmtTime(seg.durationSec)}  $pace")
                    }
                    "WORK" -> {
                        workCount++
                        workSec = seg.durationSec
                        workPace = seg.targetPaceSecPerKm
                    }
                    "REST" -> restSec = seg.durationSec
                    "COOLDOWN" -> {
                        val pace = seg.targetPaceSecPerKm?.let { fmtPace(it) } ?: ""
                        lines.add("Замин.  ${fmtTime(seg.durationSec)}  $pace")
                    }
                }
            }
            if (workCount > 0) {
                val workPaceText = workPace?.let { "  ${fmtPace(it)}" } ?: ""
                lines.add(lines.size.coerceAtMost(1), "Интерв.  ${workCount}×${fmtTime(workSec)}+${fmtTime(restSec)}$workPaceText")
            }
            lines.add("────────")
            lines.add("Итого: ≈${fmtHms(scenario.segments.sumOf { it.durationSec })}")
            lines.joinToString("\n")
        }.getOrDefault("")
    }

    private fun buildComboPreview(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return runCatching {
            val scenario = comboGson().fromJson(json, ComboScenario::class.java)
            val lines = scenario.blocks.map { block ->
                when (block) {
                    is ComboBlock.WarmupBlock -> {
                        val pace = block.pace?.let { "  ${fmtPace(it)}" } ?: ""
                        "Разм.  ${fmtTime(block.durationSec)}$pace"
                    }
                    is ComboBlock.PaceBlock -> {
                        val distance = block.distanceKm?.let { String.format(Locale.getDefault(), "%.1f км", it) } ?: "?"
                        "Обыч.  $distance  ${fmtPace(block.paceSecPerKm)}"
                    }
                    is ComboBlock.IntervalBlock ->
                        "Интерв.  ${block.repeats}×${fmtTime(block.workSec)}+${fmtTime(block.restSec)}  ${fmtPace(block.workPace)}"
                    is ComboBlock.CooldownBlock -> {
                        val pace = block.pace?.let { "  ${fmtPace(it)}" } ?: ""
                        "Замин.  ${fmtTime(block.durationSec)}$pace"
                    }
                }
            }.toMutableList()
            lines.add("────────")
            lines.add("Итого: ≈${String.format(Locale.getDefault(), "%.1f", scenario.estimateTotalDistanceKm())} км, ${fmtHms(scenario.estimateTotalTimeSec())}")
            lines.joinToString("\n")
        }.getOrDefault("")
    }

    private fun buildNormalGoalLabel(goal: ActiveWorkoutGoal): String? {
        val distancePart = goal.targetDistanceKm
            ?.takeIf { it > 0f }
            ?.let { String.format(Locale.getDefault(), "%.2f км", it) }
        val secondary = when (goal.normalGoalMode) {
            1 -> goal.targetPaceSecPerKm?.takeIf { it > 0 }?.let(::fmtPace)
            else -> goal.targetTimeSec?.takeIf { it > 0 }?.let(::fmtHms)
        }
        return listOfNotNull(distancePart, secondary).takeIf { it.isNotEmpty() }?.joinToString(" • ")
    }

    private fun buildIntervalGoalLabel(json: String?): String {
        if (json.isNullOrBlank()) return "Интервальная"
        return runCatching {
            val scenario = Gson().fromJson(json, IntervalScenario::class.java)
            val workSeg = scenario.segments.firstOrNull { it.type == "WORK" }
            val restSeg = scenario.segments.firstOrNull { it.type == "REST" }
            val workCount = scenario.segments.count { it.type == "WORK" }
            when {
                workSeg != null && restSeg != null && workCount > 0 ->
                    "${workCount}×${fmtTime(workSeg.durationSec)} / ${fmtTime(restSeg.durationSec)}"
                workSeg != null && workCount > 0 ->
                    "${workCount}×${fmtTime(workSeg.durationSec)}"
                else -> "Интервальная"
            }
        }.getOrDefault("Интервальная")
    }

    private fun buildComboGoalLabel(json: String?): String {
        if (json.isNullOrBlank()) return "Комбо"
        return runCatching {
            val scenario = comboGson().fromJson(json, ComboScenario::class.java)
            "Комбо • ${fmtHms(scenario.estimateTotalTimeSec())}"
        }.getOrDefault("Комбо")
    }

    private fun fmtTime(sec: Int): String {
        val minutes = sec / 60
        val seconds = sec % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun fmtPace(secPerKm: Int): String {
        val minutes = secPerKm / 60
        val seconds = secPerKm % 60
        return String.format(Locale.getDefault(), "%d:%02d/км", minutes, seconds)
    }

    private fun fmtHms(sec: Int): String {
        val hours = sec / 3600
        val minutes = (sec % 3600) / 60
        val seconds = sec % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }
}
