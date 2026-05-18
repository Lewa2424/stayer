package com.example.stayer.analytics

import com.example.stayer.WorkoutHistory
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Строит аналитический отчёт по истории тренировок.
 * Builds analytics report from workout history records.
 */
class WorkoutAnalyticsEngine {

    /**
     * Формирует отчёт по выбранному режиму и периоду.
     * Builds a report for the selected mode and period.
     */
    fun buildReport(
        history: List<WorkoutHistory>,
        mode: AnalyticsMode,
        period: AnalyticsPeriod,
        nowMs: Long = System.currentTimeMillis()
    ): WorkoutAnalyticsReport {
        val filtered = history
            .filter { matchesMode(it, mode) }
            .filter { matchesPeriod(it, period, nowMs) }
            .sortedBy { resolveTimestamp(it) }

        val testCount = filtered.count { it.isTest }
        val totalDistanceKm = filtered.sumOf { it.distance.toDouble() }.toFloat()
        val totalTimeSec = filtered.sumOf {
            ((it.elapsedMs.takeIf { value -> value > 0 } ?: parseTimeToSec(it.time) * 1000L) / 1000L).toInt()
        }

        if (filtered.size < 2) {
            return WorkoutAnalyticsReport(
                mode = mode,
                period = period,
                workoutsCount = filtered.size,
                testWorkoutsCount = testCount,
                totalDistanceKm = totalDistanceKm,
                totalTimeSec = totalTimeSec,
                metrics = emptyList(),
                improvements = emptyList(),
                regressions = emptyList(),
                focusPoints = emptyList(),
                insufficientDataMessage = "Для анализа нужно минимум 2 тренировки в выбранном режиме и периоде."
            )
        }

        return when (mode) {
            AnalyticsMode.NORMAL -> buildNormalReport(filtered, period, testCount, totalDistanceKm, totalTimeSec)
            AnalyticsMode.INTERVAL -> buildIntervalReport(filtered, period, testCount, totalDistanceKm, totalTimeSec)
            AnalyticsMode.COMBINED -> buildCombinedReport(filtered, period, testCount, totalDistanceKm, totalTimeSec)
            AnalyticsMode.RACE -> buildRaceReport(filtered, period, testCount, totalDistanceKm, totalTimeSec)
        }
    }

    /**
     * Проверяет принадлежность записи выбранному режиму аналитики.
     * Checks whether a history entry belongs to the selected analytics mode.
     */
    private fun matchesMode(workout: WorkoutHistory, mode: AnalyticsMode): Boolean {
        return when (mode) {
            AnalyticsMode.NORMAL -> workout.workoutMode == "normal"
            AnalyticsMode.INTERVAL -> workout.workoutMode == "interval"
            AnalyticsMode.COMBINED -> workout.workoutMode == "combined"
            AnalyticsMode.RACE -> workout.workoutMode == "race"
        }
    }

    /**
     * Проверяет попадание записи в выбранный период.
     * Checks whether a history entry belongs to the selected time period.
     */
    private fun matchesPeriod(workout: WorkoutHistory, period: AnalyticsPeriod, nowMs: Long): Boolean {
        val days = period.days ?: return true
        val timestamp = resolveTimestamp(workout)
        if (timestamp <= 0L) return false
        val boundary = nowMs - days * 24L * 60L * 60L * 1000L
        return timestamp >= boundary
    }

    /**
     * Возвращает timestamp записи.
     * Resolves stored entry timestamp.
     */
    private fun resolveTimestamp(workout: WorkoutHistory): Long = workout.timestamp

    /**
     * Строит отчёт по обычным тренировкам.
     * Builds report for normal workouts.
     */
    private fun buildNormalReport(
        history: List<WorkoutHistory>,
        period: AnalyticsPeriod,
        testCount: Int,
        totalDistanceKm: Float,
        totalTimeSec: Int
    ): WorkoutAnalyticsReport {
        val deviations = history.mapNotNull { workout ->
            computeTargetTimeSec(workout)?.let { targetTimeSec ->
                targetTimeSec - actualTimeSec(workout)
            }
        }
        val firstDeviation = deviations.firstOrNull() ?: 0
        val lastDeviation = deviations.lastOrNull() ?: 0
        val deviationTrend = lastDeviation - firstDeviation
        val averageDeviation = if (deviations.isNotEmpty()) deviations.average().roundToInt() else 0
        val averageAbsoluteDeviation = if (deviations.isNotEmpty()) deviations.map(::abs).average().roundToInt() else 0

        val checkpoints = history.flatMap { it.checkpointDetails.orEmpty() }
        val checkpointDeltas = checkpoints.map { it.deltaSec }
        val averageCheckpointDelta = if (checkpointDeltas.isNotEmpty()) checkpointDeltas.average().roundToInt() else 0
        val phaseWeakness = detectNormalWeakPhase(history)

        val metrics = buildList {
            add(AnalyticsMetric("Тренировок", history.size.toString()))
            add(AnalyticsMetric("Тестовых записей", testCount.toString()))
            add(AnalyticsMetric("Суммарная дистанция", formatDistance(totalDistanceKm)))
            add(AnalyticsMetric("Суммарное время", formatClock(totalTimeSec)))
            add(AnalyticsMetric("Среднее отклонение от цели", formatSignedClock(averageDeviation)))
            add(AnalyticsMetric("Средняя ошибка по чекпоинтам", formatSignedClock(averageCheckpointDelta)))
            add(AnalyticsMetric("Лучший результат", formatSignedClock(deviations.maxOrNull() ?: 0)))
            add(AnalyticsMetric("Худший результат", formatSignedClock(deviations.minOrNull() ?: 0)))
        }

        val improvements = mutableListOf<String>()
        val regressions = mutableListOf<String>()
        val focusPoints = mutableListOf<String>()

        when {
            deviationTrend >= 20 -> improvements += "Финишный результат стал лучше на ${formatClock(abs(deviationTrend))} от первой тренировки периода к последней."
            deviationTrend <= -20 -> regressions += "Финишный результат просел на ${formatClock(abs(deviationTrend))} от первой тренировки периода к последней."
        }

        when {
            averageAbsoluteDeviation <= 20 -> improvements += "Целевой график держится ровно, средняя ошибка уже небольшая."
            averageAbsoluteDeviation >= 45 -> regressions += "Темп пока удерживается нестабильно, средняя ошибка по цели остаётся заметной."
        }

        when (phaseWeakness) {
            "start" -> focusPoints += "Темп чаще проседает в начале дистанции. Стоит быстрее входить в рабочий ритм."
            "middle" -> focusPoints += "Просадка заметнее в середине дистанции. Стоит поработать над ровным удержанием темпа."
            "finish" -> focusPoints += "Темп чаще падает в последней трети дистанции. Стоит подтянуть финишное удержание."
        }

        if (checkpoints.isEmpty()) {
            regressions += "Для части записей нет деталей чекпоинтов, поэтому оценка стабильности по дистанции ограничена."
        } else if (averageCheckpointDelta >= 10) {
            improvements += "Чекпоинты в среднем проходятся с запасом относительно цели."
        } else if (averageCheckpointDelta <= -10) {
            regressions += "Чекпоинты в среднем проходятся медленнее цели."
        }

        if (focusPoints.isEmpty()) {
            focusPoints += "Продолжайте накапливать обычные тренировки с чекпоинтами, чтобы точнее видеть слабую фазу дистанции."
        }

        return WorkoutAnalyticsReport(
            mode = AnalyticsMode.NORMAL,
            period = period,
            workoutsCount = history.size,
            testWorkoutsCount = testCount,
            totalDistanceKm = totalDistanceKm,
            totalTimeSec = totalTimeSec,
            metrics = metrics,
            improvements = improvements,
            regressions = regressions,
            focusPoints = focusPoints
        )
    }

    /**
     * Строит отчёт по интервальным тренировкам.
     * Builds report for interval workouts.
     */
    private fun buildIntervalReport(
        history: List<WorkoutHistory>,
        period: AnalyticsPeriod,
        testCount: Int,
        totalDistanceKm: Float,
        totalTimeSec: Int
    ): WorkoutAnalyticsReport {
        val workouts = history.mapNotNull(::toIntervalWorkoutMetrics)
        if (workouts.size < 2) {
            return WorkoutAnalyticsReport(
                mode = AnalyticsMode.INTERVAL,
                period = period,
                workoutsCount = history.size,
                testWorkoutsCount = testCount,
                totalDistanceKm = totalDistanceKm,
                totalTimeSec = totalTimeSec,
                metrics = emptyList(),
                improvements = emptyList(),
                regressions = emptyList(),
                focusPoints = emptyList(),
                insufficientDataMessage = "Для интервальной аналитики нужно минимум 2 тренировки с сохранёнными участками."
            )
        }

        val averagePace = workouts.map { it.averagePaceSecPerKm }.average().roundToInt()
        val bestPace = workouts.minOf { it.bestSegmentPaceSecPerKm }
        val worstPace = workouts.maxOf { it.worstSegmentPaceSecPerKm }
        val averageDegradation = workouts.map { it.lastMinusFirstSec }.average().roundToInt()
        val trend = workouts.last().averagePaceSecPerKm - workouts.first().averagePaceSecPerKm

        val metrics = buildList {
            add(AnalyticsMetric("Тренировок", history.size.toString()))
            add(AnalyticsMetric("Тестовых записей", testCount.toString()))
            add(AnalyticsMetric("Суммарная дистанция", formatDistance(totalDistanceKm)))
            add(AnalyticsMetric("Суммарное время", formatClock(totalTimeSec)))
            add(AnalyticsMetric("Средний темп участков", formatPace(averagePace)))
            add(AnalyticsMetric("Лучший участок", formatPace(bestPace)))
            add(AnalyticsMetric("Худший участок", formatPace(worstPace)))
            add(AnalyticsMetric("Средняя просадка от 1-го к последнему участку", formatSignedClock(-averageDegradation)))
        }

        val improvements = mutableListOf<String>()
        val regressions = mutableListOf<String>()
        val focusPoints = mutableListOf<String>()

        when {
            trend <= -15 -> improvements += "Средний темп участков улучшился на ${formatClock(abs(trend))} по сравнению с первой тренировкой периода."
            trend >= 15 -> regressions += "Средний темп участков ухудшился на ${formatClock(abs(trend))} по сравнению с первой тренировкой периода."
        }

        when {
            averageDegradation <= 15 -> improvements += "Серия держится ровнее, просадка к последнему участку уже небольшая."
            averageDegradation >= 35 -> regressions += "Последние участки проседают заметно сильнее первых."
        }

        val weakestIndex = detectWeakestIntervalIndex(history)
        if (weakestIndex != null) {
            focusPoints += "Чаще всего просадка проявляется на участке ${weakestIndex + 1}. Его стоит держать под особым контролем."
        } else {
            focusPoints += "Накопите ещё интервальные записи с деталями участков для оценки слабого номера серии."
        }

        return WorkoutAnalyticsReport(
            mode = AnalyticsMode.INTERVAL,
            period = period,
            workoutsCount = history.size,
            testWorkoutsCount = testCount,
            totalDistanceKm = totalDistanceKm,
            totalTimeSec = totalTimeSec,
            metrics = metrics,
            improvements = improvements,
            regressions = regressions,
            focusPoints = focusPoints
        )
    }

    /**
     * Строит отчёт по комбо-тренировкам.
     * Builds report for combined workouts.
     */
    private fun buildCombinedReport(
        history: List<WorkoutHistory>,
        period: AnalyticsPeriod,
        testCount: Int,
        totalDistanceKm: Float,
        totalTimeSec: Int
    ): WorkoutAnalyticsReport {
        val paceSegments = history.flatMap { workout ->
            workout.segmentDetails.orEmpty().filter { it.type == "PACE" && it.actualPaceSecPerKm != null }
        }
        val workSegments = history.flatMap { workout ->
            workout.segmentDetails.orEmpty().filter { it.type == "WORK" && it.actualPaceSecPerKm != null }
        }
        if (paceSegments.isEmpty() && workSegments.isEmpty()) {
            return WorkoutAnalyticsReport(
                mode = AnalyticsMode.COMBINED,
                period = period,
                workoutsCount = history.size,
                testWorkoutsCount = testCount,
                totalDistanceKm = totalDistanceKm,
                totalTimeSec = totalTimeSec,
                metrics = emptyList(),
                improvements = emptyList(),
                regressions = emptyList(),
                focusPoints = emptyList(),
                insufficientDataMessage = "Для аналитики комбо нужны записи с сохранёнными темповыми или интервальными участками."
            )
        }

        val avgPaceBlocks = paceSegments.mapNotNull { it.actualPaceSecPerKm }.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 0
        val avgWorkBlocks = workSegments.mapNotNull { it.actualPaceSecPerKm }.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 0
        val firstWorkout = history.first()
        val lastWorkout = history.last()
        val paceTrend = averageSegmentPace(lastWorkout, "PACE") - averageSegmentPace(firstWorkout, "PACE")
        val workTrend = averageSegmentPace(lastWorkout, "WORK") - averageSegmentPace(firstWorkout, "WORK")

        val metrics = buildList {
            add(AnalyticsMetric("Тренировок", history.size.toString()))
            add(AnalyticsMetric("Тестовых записей", testCount.toString()))
            add(AnalyticsMetric("Суммарная дистанция", formatDistance(totalDistanceKm)))
            add(AnalyticsMetric("Суммарное время", formatClock(totalTimeSec)))
            add(AnalyticsMetric("Средний темп темповых блоков", formatPace(avgPaceBlocks)))
            add(AnalyticsMetric("Средний темп ускорений", formatPace(avgWorkBlocks)))
            add(AnalyticsMetric("Темповые блоки: тренд", formatSignedClock(-paceTrend)))
            add(AnalyticsMetric("Ускорения: тренд", formatSignedClock(-workTrend)))
        }

        val improvements = mutableListOf<String>()
        val regressions = mutableListOf<String>()
        val focusPoints = mutableListOf<String>()

        when {
            paceTrend <= -15 -> improvements += "Темповые блоки стали быстрее и ровнее по сравнению с первой тренировкой периода."
            paceTrend >= 15 -> regressions += "Темповые блоки сейчас держатся хуже, чем в начале периода."
        }

        when {
            workTrend <= -15 -> improvements += "Ускорения стали быстрее по среднему темпу."
            workTrend >= 15 -> regressions += "Ускорения стали медленнее по среднему темпу."
        }

        when {
            avgPaceBlocks in 1..avgWorkBlocks -> focusPoints += "Основная просадка сейчас в ускорениях. Темповая база выглядит лучше, чем интервальные куски."
            avgWorkBlocks in 1..avgPaceBlocks -> focusPoints += "Слабее держатся темповые блоки. Ускорения выглядят устойчивее, чем длительный темп."
            else -> focusPoints += "Темповые и интервальные блоки пока выглядят сбалансированно. Имеет смысл накапливать ещё комбо-записи."
        }

        return WorkoutAnalyticsReport(
            mode = AnalyticsMode.COMBINED,
            period = period,
            workoutsCount = history.size,
            testWorkoutsCount = testCount,
            totalDistanceKm = totalDistanceKm,
            totalTimeSec = totalTimeSec,
            metrics = metrics,
            improvements = improvements,
            regressions = regressions,
            focusPoints = focusPoints
        )
    }

    /**
     * Строит отчёт по режиму "Забег".
     * Builds report for race workouts.
     */
    private fun buildRaceReport(
        history: List<WorkoutHistory>,
        period: AnalyticsPeriod,
        testCount: Int,
        totalDistanceKm: Float,
        totalTimeSec: Int
    ): WorkoutAnalyticsReport {
        val workouts = history.mapNotNull(::toRaceWorkoutMetrics)
        if (workouts.isEmpty()) {
            return WorkoutAnalyticsReport(
                mode = AnalyticsMode.RACE,
                period = period,
                workoutsCount = history.size,
                testWorkoutsCount = testCount,
                totalDistanceKm = totalDistanceKm,
                totalTimeSec = totalTimeSec,
                metrics = emptyList(),
                improvements = emptyList(),
                regressions = emptyList(),
                focusPoints = emptyList(),
                insufficientDataMessage = "Для аналитики забега нужны записи с сохранёнными участками плана."
            )
        }

        val workoutPaces = history.mapNotNull(::actualWorkoutPace)
        val allSegmentPaces = workouts.flatMap { it.segmentPaces }
        val allPlanDeltas = workouts.flatMap { it.planDeltas }
        val overallAveragePace = if (workoutPaces.isNotEmpty()) workoutPaces.average().roundToInt() else 0
        val averageSegmentPace = if (allSegmentPaces.isNotEmpty()) allSegmentPaces.average().roundToInt() else 0
        val bestSegmentPace = allSegmentPaces.minOrNull() ?: 0
        val worstSegmentPace = allSegmentPaces.maxOrNull() ?: 0
        val averagePlanDelta = if (allPlanDeltas.isNotEmpty()) allPlanDeltas.average().roundToInt() else 0
        val firstPace = workoutPaces.firstOrNull() ?: 0
        val lastPace = workoutPaces.lastOrNull() ?: 0
        val paceTrend = if (firstPace > 0 && lastPace > 0) lastPace - firstPace else 0
        val weakestSegmentIndex = detectWeakestRaceSegmentIndex(history)

        val metrics = buildList {
            add(AnalyticsMetric("Тренировок", history.size.toString()))
            add(AnalyticsMetric("Тестовых записей", testCount.toString()))
            add(AnalyticsMetric("Суммарная дистанция", formatDistance(totalDistanceKm)))
            add(AnalyticsMetric("Суммарное время", formatClock(totalTimeSec)))
            add(AnalyticsMetric("Средний темп забега", formatPace(overallAveragePace)))
            add(AnalyticsMetric("Средний темп участков", formatPace(averageSegmentPace)))
            add(AnalyticsMetric("Лучший участок", formatPace(bestSegmentPace)))
            add(AnalyticsMetric("Худший участок", formatPace(worstSegmentPace)))
            if (allPlanDeltas.isNotEmpty()) {
                add(AnalyticsMetric("Среднее отклонение от плана", formatSignedPace(averagePlanDelta)))
            }
        }

        val improvements = mutableListOf<String>()
        val regressions = mutableListOf<String>()
        val focusPoints = mutableListOf<String>()

        when {
            paceTrend <= -10 -> improvements += "Средний темп забега улучшился по сравнению с первой записью периода."
            paceTrend >= 10 -> regressions += "Средний темп забега сейчас медленнее, чем в начале периода."
        }

        if (allPlanDeltas.isNotEmpty()) {
            when {
                averagePlanDelta >= 10 -> improvements += "План по участкам в среднем выполняется с запасом."
                averagePlanDelta <= -10 -> regressions += "План по участкам в среднем пока удерживается хуже цели."
            }
        } else {
            regressions += "Часть записей забега сохранена без целевых темпов по участкам, поэтому сравнение с планом ограничено."
        }

        if (weakestSegmentIndex != null) {
            focusPoints += "Чаще всего слабее держится участок ${weakestSegmentIndex + 1}. Его стоит отдельно контролировать в следующих забегах."
        } else {
            focusPoints += "Накопите ещё записи забега с деталями участков, чтобы выделить самый нестабильный участок."
        }

        return WorkoutAnalyticsReport(
            mode = AnalyticsMode.RACE,
            period = period,
            workoutsCount = history.size,
            testWorkoutsCount = testCount,
            totalDistanceKm = totalDistanceKm,
            totalTimeSec = totalTimeSec,
            metrics = metrics,
            improvements = improvements,
            regressions = regressions,
            focusPoints = focusPoints
        )
    }

    /**
     * Определяет примерную слабую фазу обычной тренировки по чекпоинтам.
     * Detects approximate weak phase of a normal run from checkpoint deltas.
     */
    private fun detectNormalWeakPhase(history: List<WorkoutHistory>): String? {
        val buckets = linkedMapOf(
            "start" to mutableListOf<Int>(),
            "middle" to mutableListOf<Int>(),
            "finish" to mutableListOf<Int>()
        )
        history.forEach { workout ->
            val totalDistance = workout.distance.takeIf { it > 0f } ?: return@forEach
            workout.checkpointDetails.orEmpty().forEach { checkpoint ->
                val progress = checkpoint.toKm / totalDistance
                when {
                    progress <= 0.33f -> buckets.getValue("start") += checkpoint.deltaSec
                    progress <= 0.66f -> buckets.getValue("middle") += checkpoint.deltaSec
                    else -> buckets.getValue("finish") += checkpoint.deltaSec
                }
            }
        }

        return buckets
            .filterValues { it.isNotEmpty() }
            .minByOrNull { (_, values) -> values.average() }
            ?.key
    }

    /**
     * Определяет номер самого слабого интервального участка по среднему темпу.
     * Detects the weakest interval segment index by average pace.
     */
    private fun detectWeakestIntervalIndex(history: List<WorkoutHistory>): Int? {
        val grouped = mutableMapOf<Int, MutableList<Int>>()
        history.forEach { workout ->
            workout.segmentDetails.orEmpty()
                .filter { it.type == "WORK" && it.actualPaceSecPerKm != null }
                .forEachIndexed { index, segment ->
                    grouped.getOrPut(index) { mutableListOf() }.add(segment.actualPaceSecPerKm!!)
                }
        }
        return grouped
            .filterValues { it.isNotEmpty() }
            .maxByOrNull { (_, paces) -> paces.average() }
            ?.key
    }

    /**
     * Определяет номер самого слабого участка забега по отклонению от плана.
     * Detects weakest race segment index by average plan deviation.
     */
    private fun detectWeakestRaceSegmentIndex(history: List<WorkoutHistory>): Int? {
        val grouped = mutableMapOf<Int, MutableList<Int>>()
        history.forEach { workout ->
            workout.segmentDetails.orEmpty()
                .filter { it.type == "RACE" && it.actualPaceSecPerKm != null && it.targetPaceSecPerKm != null }
                .forEachIndexed { index, segment ->
                    val target = segment.targetPaceSecPerKm ?: return@forEachIndexed
                    val actual = segment.actualPaceSecPerKm ?: return@forEachIndexed
                    grouped.getOrPut(index) { mutableListOf() }.add(target - actual)
                }
        }
        return grouped
            .filterValues { it.isNotEmpty() }
            .minByOrNull { (_, deltas) -> deltas.average() }
            ?.key
    }

    /**
     * Преобразует интервальную тренировку в набор метрик по её work-участкам.
     * Converts interval workout into metrics for its work segments.
     */
    private fun toIntervalWorkoutMetrics(workout: WorkoutHistory): IntervalWorkoutMetrics? {
        val paces = workout.segmentDetails.orEmpty()
            .filter { it.type == "WORK" && it.actualPaceSecPerKm != null }
            .mapNotNull { it.actualPaceSecPerKm }
        if (paces.size < 2) return null
        return IntervalWorkoutMetrics(
            averagePaceSecPerKm = paces.average().roundToInt(),
            bestSegmentPaceSecPerKm = paces.min(),
            worstSegmentPaceSecPerKm = paces.max(),
            lastMinusFirstSec = paces.last() - paces.first()
        )
    }

    /**
     * Преобразует запись забега в набор метрик по участкам.
     * Converts race workout into metrics for its stored plan segments.
     */
    private fun toRaceWorkoutMetrics(workout: WorkoutHistory): RaceWorkoutMetrics? {
        val segments = workout.segmentDetails.orEmpty()
            .filter { it.type == "RACE" && it.actualPaceSecPerKm != null }
        if (segments.isEmpty()) return null
        val paces = segments.mapNotNull { it.actualPaceSecPerKm }
        val planDeltas = segments.mapNotNull { segment ->
            val target = segment.targetPaceSecPerKm ?: return@mapNotNull null
            val actual = segment.actualPaceSecPerKm ?: return@mapNotNull null
            target - actual
        }
        return RaceWorkoutMetrics(
            segmentPaces = paces,
            planDeltas = planDeltas
        )
    }

    /**
     * Считает средний темп сегментов нужного типа у одной комбо-тренировки.
     * Computes average pace for specific segment type within one combined workout.
     */
    private fun averageSegmentPace(workout: WorkoutHistory, type: String): Int {
        val paces = workout.segmentDetails.orEmpty()
            .filter { it.type == type && it.actualPaceSecPerKm != null }
            .mapNotNull { it.actualPaceSecPerKm }
        return if (paces.isEmpty()) 0 else paces.average().roundToInt()
    }

    /**
     * Вычисляет целевое время для обычной тренировки.
     * Computes target duration for a normal workout.
     */
    private fun computeTargetTimeSec(workout: WorkoutHistory): Int? {
        workout.targetTimeSec?.takeIf { it > 0 }?.let { return it }
        val targetDistance = workout.targetDistanceKm?.takeIf { it > 0f } ?: return null
        val targetPace = workout.targetPaceSecPerKm?.takeIf { it > 0 } ?: return null
        return (targetDistance * targetPace).roundToInt()
    }

    /**
     * Возвращает фактическое время тренировки в секундах.
     * Returns actual workout duration in seconds.
     */
    private fun actualTimeSec(workout: WorkoutHistory): Int {
        return ((workout.elapsedMs.takeIf { it > 0 } ?: parseTimeToSec(workout.time) * 1000L) / 1000L).toInt()
    }

    /**
     * Возвращает средний фактический темп тренировки.
     * Returns actual average pace of the workout.
     */
    private fun actualWorkoutPace(workout: WorkoutHistory): Int? {
        val distance = workout.distance.takeIf { it > 0f } ?: return null
        return (actualTimeSec(workout) / distance).roundToInt()
    }

    /**
     * Парсит строку времени формата MM:SS или HH:MM:SS.
     * Parses MM:SS or HH:MM:SS time string.
     */
    private fun parseTimeToSec(value: String): Int {
        val parts = value.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    /**
     * Форматирует дистанцию для аналитики.
     * Formats distance for analytics.
     */
    private fun formatDistance(distanceKm: Float): String {
        return String.format(java.util.Locale.getDefault(), "%.2f км", distanceKm)
    }

    /**
     * Форматирует время в часы и минуты.
     * Formats duration as clock text.
     */
    private fun formatClock(totalSec: Int): String {
        val safe = totalSec.coerceAtLeast(0)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val seconds = safe % 60
        return if (hours > 0) {
            String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Форматирует темп в секундах на километр.
     * Formats pace in seconds per kilometer.
     */
    private fun formatPace(secPerKm: Int): String {
        if (secPerKm <= 0) return "—"
        val minutes = secPerKm / 60
        val seconds = secPerKm % 60
        return String.format(java.util.Locale.getDefault(), "%d:%02d/км", minutes, seconds)
    }

    /**
     * Форматирует signed-отклонение временем.
     * Formats signed time delta.
     */
    private fun formatSignedClock(deltaSec: Int): String {
        val sign = when {
            deltaSec > 0 -> "+"
            deltaSec < 0 -> "-"
            else -> "±"
        }
        return if (deltaSec == 0) {
            "±00:00"
        } else {
            "$sign${formatClock(abs(deltaSec))}"
        }
    }

    /**
     * Форматирует signed-отклонение темпом.
     * Formats signed pace delta.
     */
    private fun formatSignedPace(deltaSec: Int): String {
        val sign = when {
            deltaSec > 0 -> "+"
            deltaSec < 0 -> "-"
            else -> "±"
        }
        return if (deltaSec == 0) {
            "±00:00/км"
        } else {
            "$sign${formatPace(abs(deltaSec))}"
        }
    }
}

private data class IntervalWorkoutMetrics(
    val averagePaceSecPerKm: Int,
    val bestSegmentPaceSecPerKm: Int,
    val worstSegmentPaceSecPerKm: Int,
    val lastMinusFirstSec: Int
)

private data class RaceWorkoutMetrics(
    val segmentPaces: List<Int>,
    val planDeltas: List<Int>
)
