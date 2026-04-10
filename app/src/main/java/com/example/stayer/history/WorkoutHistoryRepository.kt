package com.example.stayer.history

import android.content.Context
import androidx.core.content.edit
import com.example.stayer.WorkoutHistory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Хранилище истории тренировок в SharedPreferences.
 * Единая точка чтения и записи истории с нормализацией timestamp и лимитом записей.
 *
 * Stores workout history in SharedPreferences.
 * Single source of truth for reading and writing history with timestamp normalization and record cap.
 */
class WorkoutHistoryRepository(
    context: Context
) {

    companion object {
        private const val PREFS_NAME = "WorkoutHistory"
        private const val KEY_HISTORY_JSON = "workoutHistoryList"
        private const val MAX_HISTORY_ITEMS = 500
        private const val DATE_PATTERN = "dd.MM.yy"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * Загружает всю историю тренировок с восстановлением отсутствующих timestamp у старых записей.
     * Loads full workout history and restores missing timestamps for legacy entries.
     */
    fun loadAll(): MutableList<WorkoutHistory> {
        val json = prefs.getString(KEY_HISTORY_JSON, null).orEmpty()
        if (json.isBlank()) return mutableListOf()

        return try {
            val type = object : TypeToken<List<WorkoutHistory>>() {}.type
            val parsed = gson.fromJson<List<WorkoutHistory>>(json, type).orEmpty()
            parsed.map(::normalizeTimestamp).toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    /**
     * Полностью заменяет список истории и применяет лимит хранения.
     * Replaces the entire history list and applies the storage cap.
     */
    fun saveAll(workouts: List<WorkoutHistory>) {
        val normalized = workouts
            .map(::normalizeTimestamp)
            .sortedByDescending { resolveTimestamp(it) }
            .take(MAX_HISTORY_ITEMS)
        prefs.edit {
            putString(KEY_HISTORY_JSON, gson.toJson(normalized))
        }
    }

    /**
     * Добавляет новую тренировку в начало истории с применением лимита хранения.
     * Prepends a workout to history and applies the storage cap.
     */
    fun prepend(workout: WorkoutHistory) {
        val workouts = loadAll()
        workouts.add(0, normalizeTimestamp(workout))
        saveAll(workouts)
    }

    /**
     * Возвращает нормализованный timestamp записи.
     * Returns the normalized timestamp of a history entry.
     */
    fun resolveTimestamp(workout: WorkoutHistory): Long {
        return normalizeTimestamp(workout).timestamp
    }

    /**
     * Обеспечивает наличие timestamp у записи.
     * Ensures that a workout history entry has a timestamp.
     */
    fun normalizeTimestamp(workout: WorkoutHistory): WorkoutHistory {
        if (workout.timestamp > 0L) return workout
        return workout.copy(timestamp = parseLegacyDate(workout.date))
    }

    /**
     * Преобразует старую строку даты dd.MM.yy в timestamp начала дня.
     * Converts legacy dd.MM.yy date string into a start-of-day timestamp.
     */
    private fun parseLegacyDate(date: String): Long {
        return try {
            val parsed = SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).parse(date)
            if (parsed != null) {
                val calendar = Calendar.getInstance().apply {
                    time = parsed
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                calendar.timeInMillis
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }
}
