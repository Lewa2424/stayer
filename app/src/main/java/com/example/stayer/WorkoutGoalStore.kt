package com.example.stayer

import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson

data class ActiveWorkoutGoal(
    val workoutMode: Int,
    val normalGoalMode: Int = 0,
    val targetDistanceKm: Float? = null,
    val targetTimeSec: Int? = null,
    val targetPaceSecPerKm: Int? = null,
    val intervalScenarioJson: String? = null,
    val comboScenarioJson: String? = null,
    val racePlanJson: String? = null
)

object WorkoutGoalStore {
    private const val KEY_ACTIVE_GOAL_JSON = "ACTIVE_GOAL_JSON"
    private const val KEY_WORKOUT_MODE = "WORKOUT_MODE"
    private const val KEY_NORMAL_GOAL_MODE = "NORMAL_GOAL_MODE"
    private const val KEY_TARGET_DISTANCE = "TARGET_DISTANCE"
    private const val KEY_TARGET_TIME = "TARGET_TIME"
    private const val KEY_TARGET_DISTANCE_KM = "TARGET_DISTANCE_KM"
    private const val KEY_TARGET_TIME_SEC = "TARGET_TIME_SEC"
    private const val KEY_TARGET_PACE_SEC_PER_KM = "TARGET_PACE_SEC_PER_KM"
    private const val KEY_INTERVAL_SCENARIO_JSON = "INTERVAL_SCENARIO_JSON"
    private const val KEY_COMBO_SCENARIO_JSON = "COMBO_SCENARIO_JSON"
    private const val KEY_RACE_PLAN_JSON = "RACE_PLAN_JSON"

    private val gson = Gson()

    fun load(prefs: SharedPreferences): ActiveWorkoutGoal {
        prefs.getString(KEY_ACTIVE_GOAL_JSON, null)?.let { json ->
            runCatching { gson.fromJson(json, ActiveWorkoutGoal::class.java) }
                .getOrNull()
                ?.let { return it }
        }

        val migrated = migrateFromLegacy(prefs)
        save(prefs, migrated)
        return migrated
    }

    fun save(prefs: SharedPreferences, goal: ActiveWorkoutGoal) {
        prefs.edit {
            putString(KEY_ACTIVE_GOAL_JSON, gson.toJson(goal))
            putInt(KEY_WORKOUT_MODE, goal.workoutMode)

            when (goal.workoutMode) {
                0 -> {
                    putInt(KEY_NORMAL_GOAL_MODE, goal.normalGoalMode)
                    goal.targetDistanceKm?.let {
                        putFloat(KEY_TARGET_DISTANCE_KM, it)
                        putString(KEY_TARGET_DISTANCE, formatDistance(it))
                    }
                    goal.targetTimeSec?.let {
                        putInt(KEY_TARGET_TIME_SEC, it)
                        putString(KEY_TARGET_TIME, formatTime(it))
                    }
                    goal.targetPaceSecPerKm?.let {
                        putInt(KEY_TARGET_PACE_SEC_PER_KM, it)
                    }
                }
                1 -> {
                    putString(KEY_INTERVAL_SCENARIO_JSON, goal.intervalScenarioJson)
                }
                2 -> {
                    putString(KEY_COMBO_SCENARIO_JSON, goal.comboScenarioJson)
                }
                3 -> Unit
                4 -> {
                    putString(KEY_RACE_PLAN_JSON, goal.racePlanJson)
                }
            }
        }
    }

    private fun migrateFromLegacy(prefs: SharedPreferences): ActiveWorkoutGoal {
        val mode = prefs.getInt(KEY_WORKOUT_MODE, 0)
        return when (mode) {
            1 -> ActiveWorkoutGoal(
                workoutMode = 1,
                intervalScenarioJson = prefs.getString(KEY_INTERVAL_SCENARIO_JSON, null)
            )
            2 -> ActiveWorkoutGoal(
                workoutMode = 2,
                comboScenarioJson = prefs.getString(KEY_COMBO_SCENARIO_JSON, null)
            )
            3 -> ActiveWorkoutGoal(
                workoutMode = 3
            )
            4 -> ActiveWorkoutGoal(
                workoutMode = 4,
                racePlanJson = prefs.getString(KEY_RACE_PLAN_JSON, null)
            )
            else -> {
                val targetDistanceKm = when {
                    prefs.contains(KEY_TARGET_DISTANCE_KM) -> prefs.getFloat(KEY_TARGET_DISTANCE_KM, 0f).takeIf { it > 0f }
                    else -> prefs.getString(KEY_TARGET_DISTANCE, null)
                        ?.replace(',', '.')
                        ?.toFloatOrNull()
                        ?.takeIf { it > 0f }
                }
                val targetTimeSec = when {
                    prefs.contains(KEY_TARGET_TIME_SEC) -> prefs.getInt(KEY_TARGET_TIME_SEC, 0).takeIf { it > 0 }
                    else -> parseLegacyTime(prefs.getString(KEY_TARGET_TIME, null))
                }
                val targetPaceSecPerKm = prefs.getInt(KEY_TARGET_PACE_SEC_PER_KM, 0).takeIf { it > 0 }
                ActiveWorkoutGoal(
                    workoutMode = 0,
                    normalGoalMode = prefs.getInt(KEY_NORMAL_GOAL_MODE, 0),
                    targetDistanceKm = targetDistanceKm,
                    targetTimeSec = targetTimeSec,
                    targetPaceSecPerKm = targetPaceSecPerKm
                )
            }
        }
    }

    private fun parseLegacyTime(value: String?): Int? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parts = raw.split(":")
        return when (parts.size) {
            2 -> {
                val m = parts[0].toIntOrNull() ?: return null
                val s = parts[1].toIntOrNull() ?: return null
                if (m < 0 || s !in 0..59) return null
                m * 60 + s
            }
            3 -> {
                val h = parts[0].toIntOrNull() ?: return null
                val m = parts[1].toIntOrNull() ?: return null
                val s = parts[2].toIntOrNull() ?: return null
                if (h < 0 || m !in 0..59 || s !in 0..59) return null
                h * 3600 + m * 60 + s
            }
            else -> null
        }
    }

    private fun formatDistance(distanceKm: Float): String = String.format("%.2f", distanceKm)

    private fun formatTime(totalSec: Int): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
