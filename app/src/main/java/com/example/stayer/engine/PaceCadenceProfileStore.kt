package com.example.stayer.engine

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.roundToInt

/**
 * Хранилище pace-профиля каденса (темп → каденс + шаг).
 * Stores the pace cadence profile (pace → cadence + stride).
 */
class PaceCadenceProfileStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * Загружает полную таблицу для UI и runtime lookup.
     * Loads the full table for UI and runtime lookup.
     */
    fun loadTable(): List<PaceCadenceProfileRow> {
        migrateLegacyBucketsIfNeeded()
        val stored = readStoredRows()
        return PaceCadenceScale.PACE_LEVELS_SEC.map { pace ->
            stored[pace] ?: PaceCadenceProfileRow(
                paceSecPerKm = pace,
                avgCadenceSpm = null,
                strideMeters = null,
                sampleCount = 0,
            )
        }
    }

    /**
     * Обновляет строку профиля по наблюдению с хорошим GPS/рельсами.
     * Updates a profile row from a good GPS/rail observation.
     */
    fun observeSample(
        observedPaceSecPerKm: Int,
        cadenceSpm: Int,
        strideMeters: Double,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (cadenceSpm <= 0 || strideMeters !in 0.3..1.5) return
        val paceBucket = PaceCadenceScale.nearestPaceBucket(observedPaceSecPerKm)
        val rows = readStoredRows().toMutableMap()
        val existing = rows[paceBucket]
        val defaultStride = defaultStrideForPace(paceBucket)
        val cappedStride = strideMeters.coerceAtMost(defaultStride * 1.15)

        val newCadence = if (existing?.avgCadenceSpm != null) {
            ema(existing.avgCadenceSpm.toDouble(), cadenceSpm.toDouble()).roundToInt()
        } else {
            cadenceSpm
        }
        val newStride = if (existing?.strideMeters != null) {
            ema(existing.strideMeters, cappedStride)
        } else {
            cappedStride
        }

        rows[paceBucket] = PaceCadenceProfileRow(
            paceSecPerKm = paceBucket,
            avgCadenceSpm = newCadence,
            strideMeters = newStride,
            sampleCount = (existing?.sampleCount ?: 0) + 1,
            lastUpdatedMs = nowMs,
        )
        writeStoredRows(rows)
    }

    /**
     * Возвращает шаг для текущего каденса (fallback в BLIND).
     * Returns stride for the current cadence (BLIND fallback).
     */
    fun strideForCadence(cadenceSpm: Int): Double {
        if (cadenceSpm <= 0) return defaultStrideForPace(PaceCadenceScale.PACE_MAX_SEC_PER_KM)
        migrateLegacyBucketsIfNeeded()
        val rows = loadTable().filter { it.isLearned }
        if (rows.isEmpty()) {
            return defaultStrideForCadence(cadenceSpm)
        }
        val nearest = rows.minByOrNull { kotlin.math.abs((it.avgCadenceSpm ?: 0) - cadenceSpm) }
        return nearest?.strideMeters ?: defaultStrideForCadence(cadenceSpm)
    }

    /**
     * Дефолтный шаг для темпа (до обучения).
     * Default stride for a pace before training.
     */
    fun defaultStrideForPace(paceSecPerKm: Int): Double {
        val slow = 0.70
        val fast = 0.95
        val t = (PaceCadenceScale.PACE_MAX_SEC_PER_KM - paceSecPerKm).toDouble() /
            (PaceCadenceScale.PACE_MAX_SEC_PER_KM - PaceCadenceScale.PACE_MIN_SEC_PER_KM).toDouble()
        return slow + (fast - slow) * t.coerceIn(0.0, 1.0)
    }

    private fun defaultStrideForCadence(cadenceSpm: Int): Double {
        return when {
            cadenceSpm < 140 -> 0.70
            cadenceSpm <= 150 -> 0.78
            cadenceSpm <= 160 -> 0.85
            else -> 0.92
        }
    }

    private fun ema(old: Double, new: Double, alpha: Double = 0.2): Double {
        return (old * (1.0 - alpha)) + (new * alpha)
    }

    private fun readStoredRows(): Map<Int, PaceCadenceProfileRow> {
        val json = prefs.getString(KEY_ROWS_JSON, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<Int, PaceCadenceProfileRow>>() {}.type
            gson.fromJson<Map<Int, PaceCadenceProfileRow>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun writeStoredRows(rows: Map<Int, PaceCadenceProfileRow>) {
        prefs.edit().putString(KEY_ROWS_JSON, gson.toJson(rows)).apply()
    }

    /**
     * Мигрирует старые cadence-бакеты в pace-профиль (один раз).
     * Migrates legacy cadence buckets into the pace profile (once).
     */
    fun migrateLegacyBucketsIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val legacyPrefs = prefs
        val legacy = contextLegacyPrefs()
        val s1 = legacy.getFloat("bucket_under_140", -1f)
        val s2 = legacy.getFloat("bucket_140_150", -1f)
        val s3 = legacy.getFloat("bucket_150_160", -1f)
        val s4 = legacy.getFloat("bucket_over_160", -1f)
        if (s1 < 0 && s2 < 0 && s3 < 0 && s4 < 0) {
            legacyPrefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            return
        }

        val rows = readStoredRows().toMutableMap()
        val mapping = listOf(
            375..420 to Pair(130, s1.takeIf { it >= 0 }?.toDouble() ?: 0.70),
            330..360 to Pair(145, s2.takeIf { it >= 0 }?.toDouble() ?: 0.78),
            285..315 to Pair(155, s3.takeIf { it >= 0 }?.toDouble() ?: 0.85),
            240..270 to Pair(170, s4.takeIf { it >= 0 }?.toDouble() ?: 0.92),
        )
        for ((paceRange, cadenceStride) in mapping) {
            val (cadence, stride) = cadenceStride
            for (pace in PaceCadenceScale.PACE_LEVELS_SEC) {
                if (pace in paceRange && rows[pace] == null) {
                        rows[pace] = PaceCadenceProfileRow(
                        paceSecPerKm = pace,
                        avgCadenceSpm = cadence,
                        strideMeters = stride,
                        sampleCount = 1,
                        lastUpdatedMs = System.currentTimeMillis(),
                    )
                }
            }
        }
        writeStoredRows(rows)
        legacyPrefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    private fun contextLegacyPrefs(): SharedPreferences = prefs

    companion object {
        private const val PREFS_NAME = "StepCalibrationProfile"
        private const val KEY_ROWS_JSON = "pace_profile_rows_json"
        private const val KEY_MIGRATED = "pace_profile_migrated"
    }
}
