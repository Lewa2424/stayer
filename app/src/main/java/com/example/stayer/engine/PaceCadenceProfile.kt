package com.example.stayer.engine

/**
 * Строка pace-профиля: каденс и шаг на конкретном темпе.
 * One row of the pace profile: cadence and stride at a specific pace.
 */
data class PaceCadenceProfileRow(
    val paceSecPerKm: Int,
    val avgCadenceSpm: Int?,
    val strideMeters: Double?,
    val sampleCount: Int = 0,
    val lastUpdatedMs: Long = 0L,
) {
    val isLearned: Boolean
        get() = sampleCount > 0 && avgCadenceSpm != null && strideMeters != null
}

/**
 * Константы шкалы темпов для калибровки каденса.
 * Pace scale constants for cadence calibration.
 */
object PaceCadenceScale {
    const val PACE_MIN_SEC_PER_KM = 240 // 4:00
    const val PACE_MAX_SEC_PER_KM = 420 // 7:00
    const val PACE_STEP_SEC = 15

    /** Все уровни темпа от 7:00 до 4:00 с шагом 15 с. / All pace levels from 7:00 to 4:00. */
    val PACE_LEVELS_SEC: List<Int> = generateSequence(PACE_MAX_SEC_PER_KM) { previous ->
        val next = previous - PACE_STEP_SEC
        if (next < PACE_MIN_SEC_PER_KM) null else next
    }.toList()

    /**
     * Форматирует темп в MM:SS.
     * Formats pace as MM:SS.
     */
    fun formatPace(paceSecPerKm: Int): String {
        val minutes = paceSecPerKm / 60
        val seconds = paceSecPerKm % 60
        return "%d:%02d".format(minutes, seconds)
    }

    /**
     * Находит ближайший pace-бакет к наблюдаемому темпу.
     * Finds the nearest pace bucket for an observed pace.
     */
    fun nearestPaceBucket(observedPaceSecPerKm: Int): Int {
        return PACE_LEVELS_SEC.minByOrNull { kotlin.math.abs(it - observedPaceSecPerKm) }
            ?: PACE_MAX_SEC_PER_KM
    }
}
