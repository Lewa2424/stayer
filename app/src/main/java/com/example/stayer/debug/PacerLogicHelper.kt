package com.example.stayer.debug

import kotlin.math.abs
import kotlin.math.roundToInt

enum class GlobalProgress {
    ON_TRACK, BEHIND, AHEAD
}

object PacerLogicHelper {

    fun getPlural(n: Int, form1: String, form2: String, form5: String): String {
        val n100 = n % 100
        val n10 = n % 10
        if (n100 in 11..19) return form5
        if (n10 == 1) return form1
        if (n10 in 2..4) return form2
        return form5
    }

    /**
     * Conversational pace format for TTS.
     * 330 sec/km -> "пять тридцать"
     * 300 sec/km -> "пять ровно"
     * 305 sec/km -> "пять ноль пять"
     */
    fun formatPaceForSpeech(secPerKm: Int): String {
        val m = secPerKm / 60
        val s = secPerKm % 60
        return when {
            s == 0 -> "$m ровно"
            s < 10 -> "$m ноль $s"
            else -> "$m $s"
        }
    }

    /**
     * Formats a time difference for speech with full Russian words.
     */
    fun formatTimeDiffForSpeech(diffSecs: Int): String {
        val absSec = abs(diffSecs)
        val m = absSec / 60
        val s = absSec % 60
        val mStr = getPlural(m, "минуту", "минуты", "минут")
        val sStr = getPlural(s, "секунду", "секунды", "секунд")
        if (m == 0) return "$s $sStr"
        if (s == 0) return "$m $mStr"
        return "$m $mStr $s $sStr"
    }

    /**
     * Builds the full TTS prompt for the normal mode pacer.
     *
     * @param globalProgress    ON_TRACK / BEHIND / AHEAD based on finish prediction
     * @param globalDeltaSec    |finishDeltaSec|: positive = behind, negative = ahead
     * @param localDiffSecPerKm current pace minus original target (positive = too slow)
     * @param currentPaceSecPerKm pace over the last 10% segment
     * @param targetPaceSecPerKm  original goal pace (for reference)
     * @param remainingDistKm   target distance minus current distance
     * @param timeLeftSec       targetTotalSec minus currentElapsedSec (can be negative if overdue)
     * @param currentDistKm     distance already covered, used for "on N-th km" phrasing
     * @param pacerPraiseAlternate toggle for alternating praise phrases
     */
    fun buildNormalPacerPrompt(
        globalProgress: GlobalProgress,
        globalDeltaSec: Int,
        localDiffSecPerKm: Int,
        currentPaceSecPerKm: Int,
        targetPaceSecPerKm: Int,
        remainingDistKm: Double,
        timeLeftSec: Int,
        currentDistKm: Double,
        pacerPraiseAlternate: Boolean
    ): Pair<String, Boolean> {

        // Dynamic target: pace needed right now to finish exactly on time
        val dynamicTargetPace = if (remainingDistKm > 0.1 && timeLeftSec > 0)
            (timeLeftSec / remainingDistKm).roundToInt()
        else
            targetPaceSecPerKm

        val dynText = formatPaceForSpeech(dynamicTargetPace)
        val curText = formatPaceForSpeech(currentPaceSecPerKm)
        val globalTimeText = formatTimeDiffForSpeech(abs(globalDeltaSec))

        // Difference vs dynamic target (positive = slower than needed, negative = faster)
        val diffVsDynamic = currentPaceSecPerKm - dynamicTargetPace

        var nextPraiseAlternate = pacerPraiseAlternate

        val prompt = when (globalProgress) {

            // ─── Сценарий A / B — Идём по графику ────────────────────────────────────
            GlobalProgress.ON_TRACK -> {
                val absLocal = abs(localDiffSecPerKm)
                when {
                    absLocal <= 20 -> {
                        // A: Идеальный темп — чередуем похвалу
                        nextPraiseAlternate = !pacerPraiseAlternate
                        if (nextPraiseAlternate) "Темп идеальный. Идём по графику."
                        else "Отличный темп. Так держать."
                    }
                    absLocal in 21..30 -> {
                        // B: Небольшое отклонение
                        if (localDiffSecPerKm > 0)
                            "По графику. Нужен темп $dynText. Чуть ускорьтесь."
                        else
                            "По графику. Нужен темп $dynText. Чуть замедлитесь."
                    }
                    else -> {
                        // B: Заметное или сильное отклонение
                        if (localDiffSecPerKm > 0)
                            "По графику. Сейчас темп $curText, нужен $dynText. Немного ускорьтесь."
                        else
                            "По графику. Сейчас темп $curText, нужен $dynText. Немного замедлитесь."
                    }
                }
            }

            // ─── Сценарий C — Отстаём ─────────────────────────────────────────────────
            GlobalProgress.BEHIND -> {
                // Can the runner finish on time at their current segment pace?
                val willFinishOnTime = remainingDistKm > 0.1 &&
                        currentPaceSecPerKm * remainingDistKm <= timeLeftSec

                when {
                    willFinishOnTime && currentPaceSecPerKm < targetPaceSecPerKm -> {
                        // C1: Running faster than original target, will finish on time
                        // Compute when the runner catches up to the schedule
                        val currentElapsedSec = (targetPaceSecPerKm * (currentDistKm + remainingDistKm)).roundToInt() - timeLeftSec
                        val timeBehindNow = currentElapsedSec - (targetPaceSecPerKm * currentDistKm).roundToInt()
                        val paceAdvantage = targetPaceSecPerKm - currentPaceSecPerKm
                        if (paceAdvantage > 0 && timeBehindNow > 0) {
                            val kmToRecover = timeBehindNow.toDouble() / paceAdvantage
                            val recoverAtKm = (currentDistKm + kmToRecover)
                                .roundToInt().coerceAtLeast(1)
                            "Отстаём на $globalTimeText. С текущим темпом нагоните на ${recoverAtKm}-м километре."
                        } else {
                            "Отстаём на $globalTimeText. С текущим темпом финишируете вовремя."
                        }
                    }
                    diffVsDynamic < 0 -> {
                        // C1-bad: Running faster than dynamic target but still can't finish on time
                        "Отстаём на $globalTimeText. С текущим темпом нагнать не успеть. Нужен темп $dynText. Постарайтесь ускориться."
                    }
                    else -> {
                        // C2: Pace equal or slower than dynamic target — debt growing
                        "Отстаём на $globalTimeText, отставание растёт. Нужен темп $dynText. Значительно ускорьтесь."
                    }
                }
            }

            // ─── Сценарий D — Идём с запасом ─────────────────────────────────────────
            GlobalProgress.AHEAD -> {
                // How much time are we ahead of the ORIGINAL target schedule at this exact distance?
                val targetFinishSec = (targetPaceSecPerKm * (currentDistKm + remainingDistKm)).roundToInt()
                val currentElapsedSec = targetFinishSec - timeLeftSec
                val expectedElapsedSecAtThisDist = (targetPaceSecPerKm * currentDistKm).roundToInt()
                val timeAheadNow = expectedElapsedSecAtThisDist - currentElapsedSec

                when {
                    currentPaceSecPerKm > targetPaceSecPerKm + 5 -> {
                        // D2: Running slower than original target — advantage is shrinking
                        val lossRate = currentPaceSecPerKm - targetPaceSecPerKm
                        val kmToLose = timeAheadNow.toDouble() / lossRate
                        val loseAtKm = (currentDistKm + kmToLose).roundToInt().coerceAtLeast(1)

                        if (loseAtKm <= currentDistKm + remainingDistKm) {
                            "Запас $globalTimeText, но с таким темпом потеряете преимущество на ${loseAtKm}-м километре. Сейчас темп $curText, а нужен $dynText."
                        } else {
                            "Запас $globalTimeText, но темп упал. Сейчас темп $curText, а нужен $dynText. Прибавьте."
                        }
                    }
                    diffVsDynamic < -20 -> {
                        // D3: Бежим существенно быстрее динамической цели — можно сбавить
                        "Запас $globalTimeText. Сейчас темп $curText, а нужен $dynText. Можно немного расслабиться."
                    }
                    else -> {
                        // D1: Запас держим, темп ровный
                        "Запас $globalTimeText. Темп хороший."
                    }
                }
            }
        }

        return Pair(prompt.trim(), nextPraiseAlternate)
    }

    /**
     * Emergency alert: fires out-of-schedule when the runner crosses into BEHIND territory.
     * Called every 250m. Returns null if no alert should be spoken.
     *
     * @param prevGlobalProgress  GlobalProgress at the last 10% checkpoint
     * @param currentDistKm       Current distance in km
     * @param currentElapsedSec   Current elapsed time in seconds
     * @param targetPaceSecPerKm  Original target pace (sec/km)
     * @param targetDistKm        Total target distance in km
     * @param targetTotalSec      Total target time in seconds
     * @return Alert string to speak, or null if everything is fine
     */
    fun buildEmergencyAlert(
        prevGlobalProgress: GlobalProgress,
        currentDistKm: Double,
        currentElapsedSec: Int,
        targetPaceSecPerKm: Int,
        targetDistKm: Double,
        targetTotalSec: Int
    ): String? {
        // Only alert when we were previously OK and have crossed into BEHIND
        if (prevGlobalProgress == GlobalProgress.BEHIND) return null

        val avgPaceNow = if (currentDistKm > 0.01) currentElapsedSec / currentDistKm else return null
        val predictedFinishSec = (avgPaceNow * targetDistKm).roundToInt()
        val finishDeltaSec = predictedFinishSec - targetTotalSec

        // Only fire if clearly behind (>30s projected deficit)
        if (finishDeltaSec <= 30) return null

        val remainingDistKm = (targetDistKm - currentDistKm).coerceAtLeast(0.1)
        val timeLeftSec = targetTotalSec - currentElapsedSec
        val dynamicTargetPace = if (timeLeftSec > 0) (timeLeftSec / remainingDistKm).roundToInt()
                                else targetPaceSecPerKm
        val dynText = formatPaceForSpeech(dynamicTargetPace)
        val deltaText = formatTimeDiffForSpeech(finishDeltaSec)

        return "Внимание! Отстаём на $deltaText. Нужен темп $dynText. Ускорьтесь."
    }

    // ─── Interval Pacer Helpers ───────────────────────────────────────────────

    /**
     * Returns the hint interval config for a WORK segment.
     * @return Pair(firstHintAtSec, repeatEverySec) — both relative to segment start (after stable phase)
     */
    fun intervalHintTiming(durationSec: Int): Pair<Int, Int>? {
        return when {
            durationSec < 30  -> null             // no mid-hints, only end report
            durationSec < 120 -> null             // single mid-hint handled separately
            durationSec < 300 -> Pair(30, 60)     // 2-5 min: first at 30s, then every 60s
            durationSec < 480 -> Pair(60, 60)     // 5-8 min: first at 60s, then every 60s
            else              -> Pair(60, 120)    // 8+ min: first at 60s, then every 2 min
        }
    }

    /**
     * Build a mid-segment pace hint for interval WORK.
     * Two-layer check:
     *   Layer 1: instant pace vs target (rolling window)
     *   Layer 2: average pace over the phase vs target (accumulated deficit)
     *
     * @param currentPaceSecPerKm  estimated instant pace from rolling window
     * @param targetPaceSecPerKm   target pace for the segment
     * @param alternate            alternation flag for positive messages
     * @param avgPaceSecPerKm      average pace since stable phase start (null = skip layer 2)
     * @return Pair(phrase, nextAlternate)
     */
    fun buildIntervalHint(
        currentPaceSecPerKm: Int,
        targetPaceSecPerKm: Int,
        alternate: Boolean,
        avgPaceSecPerKm: Int? = null
    ): Pair<String, Boolean> {
        val diff = currentPaceSecPerKm - targetPaceSecPerKm  // positive = slower
        val curText = formatPaceForSpeech(currentPaceSecPerKm)
        val targetText = formatPaceForSpeech(targetPaceSecPerKm)

        val prompt: String
        val nextAlt: Boolean

        if (abs(diff) <= 15) {
            // Layer 1 says OK — check layer 2 (average over phase)
            val avgDiff = if (avgPaceSecPerKm != null) avgPaceSecPerKm - targetPaceSecPerKm else 0
            val avgText = if (avgPaceSecPerKm != null) formatPaceForSpeech(avgPaceSecPerKm) else ""

            if (avgDiff > 10) {
                // Instant OK but average is slower — accumulated deficit
                prompt = "Темп сейчас хороший, но в среднем $avgText. Прибавь."
                nextAlt = alternate
            } else if (avgDiff < -10) {
                // Instant OK but average is faster — accumulated surplus
                prompt = "Темп хороший, в среднем $avgText. Можно расслабиться."
                nextAlt = alternate
            } else {
                // Both layers OK
                prompt = if (alternate) "Так держать." else "Темп хороший."
                nextAlt = !alternate
            }
        } else if (diff in 16..30) {
            prompt = "Темп $curText. Чуть быстрее."
            nextAlt = alternate
        } else if (diff > 30) {
            prompt = "Темп $curText. Нужен $targetText. Ускорьтесь."
            nextAlt = alternate
        } else if (diff in -30..-16) {
            prompt = "Не гони. Темп $curText."
            nextAlt = alternate
        } else { // diff < -30
            prompt = "Слишком быстро. Темп $curText, нужен $targetText."
            nextAlt = alternate
        }

        return Pair(prompt, nextAlt)
    }

    /**
     * Build end-of-WORK report with "Нужно А, было Б" phrasing.
     */
    fun buildIntervalEndReport(
        factPaceSecPerKm: Int,
        targetPaceSecPerKm: Int
    ): String {
        val diff = factPaceSecPerKm - targetPaceSecPerKm
        val factText = formatPaceForSpeech(factPaceSecPerKm)
        val targetText = formatPaceForSpeech(targetPaceSecPerKm)

        return when {
            abs(diff) <= 15 -> "Отличный темп. Так держать."
            diff > 0 -> "Медленнее заданного. Нужно $targetText, было $factText."
            else -> "Быстрее заданного. Нужно $targetText, было $factText."
        }
    }
}
