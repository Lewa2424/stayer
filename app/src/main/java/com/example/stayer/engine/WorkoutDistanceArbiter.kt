package com.example.stayer.engine

import com.example.stayer.pathnet.domain.RailAdvanceResult
import com.example.stayer.pathnet.domain.RailAdvanceStatus
import com.example.stayer.pathnet.domain.RailCreditKind
import com.example.stayer.pathnet.domain.RailMatcher
import com.example.stayer.pathnet.domain.RailObservation
import com.example.stayer.pathnet.domain.ObservationQuality
import com.example.stayer.pathnet.model.GeoPoint

/**
 * Источник дистанции за тик.
 * Distance source for a single tick.
 */
enum class DistanceSource {
    None,
    RailGps,
    RailSoftGps,
    RailDeadReckoning,
    Gps,
    Cadence,
}

/**
 * Результат одного тика дистанции.
 * Result of a single distance tick.
 */
data class DistanceTick(
    val deltaMeters: Double = 0.0,
    val source: DistanceSource = DistanceSource.None,
    val paceFeedMeters: Double = 0.0,
    val paceFeedSec: Double = 0.0,
    val trainable: Boolean = false,
    val trackedPoint: GeoPoint? = null,
    val railStatus: RailAdvanceStatus? = null,
    val railDebtMeters: Double = 0.0,
    val railCapApplied: Boolean = false,
    val railPathDeltaBeforeCap: Double = 0.0,
)

/**
 * Оркестратор дистанции: рельсы → GPS → каденс.
 * Distance orchestrator: rails → GPS → cadence.
 */
class WorkoutDistanceArbiter(
    private val railMatcher: RailMatcher,
    private val fallbackEngine: CadenceFallbackEngine,
    private val profileStore: PaceCadenceProfileStore,
) {
    var routeFollowEnabled: Boolean = false

    private var ticksSinceLastGps = 0
    private val maxTicksWithoutGps = 3
    private val goodGpsAccuracyMeters = 25f

    /**
     * Сбрасывает таймеры оркестратора (не state machine).
     * Resets arbiter timers (not the state machine).
     */
    fun resetTimers() {
        ticksSinceLastGps = 0
    }

    /**
     * Полный сброс вместе с fallback engine.
     * Full reset including the fallback engine.
     */
    fun reset() {
        resetTimers()
        fallbackEngine.reset()
    }

    /**
     * Первая GPS-точка тренировки.
     * First GPS point of the workout.
     */
    fun onFirstGpsPoint(smoothedPoint: GeoPoint): DistanceTick {
        if (routeFollowEnabled) {
            railMatcher.tryLock(smoothedPoint)
        }
        fallbackEngine.onGpsSignalReceived()
        ticksSinceLastGps = 0
        return DistanceTick(
            trackedPoint = smoothedPoint,
            railStatus = if (railMatcher.isLocked) RailAdvanceStatus.RELOCK else null,
        )
    }

    /**
     * Принятая GPS-точка (фильтр пройден).
     * Accepted GPS point (filter passed).
     */
    fun onGpsAccepted(
        smoothedPoint: GeoPoint,
        rawDeltaMeters: Double,
        deltaTimeSec: Double,
        accuracyMeters: Float,
        locationSpeedMps: Double? = null,
    ): DistanceTick {
        fallbackEngine.onGpsSignalReceived()
        ticksSinceLastGps = 0

        if (routeFollowEnabled && !railMatcher.isLocked) {
            railMatcher.tryLock(smoothedPoint)
        }

        if (routeFollowEnabled && railMatcher.isLocked) {
            val railAdvance = railMatcher.process(
                RailObservation(
                    point = smoothedPoint,
                    dtSec = deltaTimeSec,
                    rawDeltaMeters = rawDeltaMeters,
                    accuracyMeters = accuracyMeters.toDouble(),
                    locationSpeedMps = locationSpeedMps,
                    cadenceDeltaMeters = null,
                    quality = ObservationQuality.ACCEPTED_GPS,
                ),
            )
            if (railAdvance != null) {
                return railTick(
                    railAdvance = railAdvance,
                    source = DistanceSource.RailGps,
                    deltaTimeSec = deltaTimeSec,
                    accuracyMeters = accuracyMeters,
                )
            }
        }
        if (routeFollowEnabled) return DistanceTick(trackedPoint = smoothedPoint)

        val gpsDistance = fallbackEngine.processGpsAccepted(rawDeltaMeters)
        return DistanceTick(
            deltaMeters = gpsDistance,
            source = if (gpsDistance > 0.0) DistanceSource.Gps else DistanceSource.None,
            paceFeedMeters = gpsDistance,
            paceFeedSec = deltaTimeSec,
            trainable = isTrainable(accuracyMeters, DistanceSource.Gps),
            trackedPoint = smoothedPoint,
        )
    }

    /**
     * Отклонённая GPS-точка: soft-rail при locked, иначе BLIND.
     * Rejected GPS point: soft-rail when locked, otherwise BLIND.
     */
    fun onGpsRejected(
        smoothedPoint: GeoPoint,
        deltaTimeSec: Double,
        accuracyMeters: Float,
        rawDeltaMeters: Double? = null,
    ): DistanceTick {
        val railsLocked = routeFollowEnabled && railMatcher.isLocked
        fallbackEngine.processGpsRejected(suppressBlind = railsLocked)

        if (railsLocked) {
            if (!railMatcher.isLocked) {
                railMatcher.tryLock(smoothedPoint)
            }
            if (railMatcher.isLocked) {
                val projection = trySoftRailAdvance(smoothedPoint, deltaTimeSec, accuracyMeters, rawDeltaMeters)
                if (projection != null) {
                    return projection
                }
            }
        }

        return DistanceTick(trackedPoint = smoothedPoint)
    }

    /**
     * Секундный тик шагомера.
     * One-second pedometer tick.
     */
    fun onStepTick(stepDelta: Int): DistanceTick {
        val railsLocked = routeFollowEnabled && railMatcher.isLocked
        val emitCadenceDistance = !railsLocked &&
            fallbackEngine.currentState == CadenceFallbackEngine.State.BLIND

        if (railsLocked) {
            ticksSinceLastGps++
            fallbackEngine.processTick(stepDelta, emitDistance = false, suppressBlindTransition = true)

            if (ticksSinceLastGps > maxTicksWithoutGps) {
                val stride = profileStore.strideForCadence(fallbackEngine.currentCadenceSpm)
                val arcMeters = stepDelta * stride
                val railAdvance = railMatcher.process(
                    RailObservation(
                        point = null,
                        dtSec = 1.0,
                        rawDeltaMeters = null,
                        accuracyMeters = Double.POSITIVE_INFINITY,
                        locationSpeedMps = null,
                        cadenceDeltaMeters = arcMeters,
                        quality = ObservationQuality.CADENCE_ONLY,
                    ),
                )
                if (railAdvance != null && railAdvance.deltaMeters > 0.0) {
                    fallbackEngine.recordAuthoritativeDistance(railAdvance.deltaMeters)
                    return DistanceTick(
                        deltaMeters = railAdvance.deltaMeters,
                        source = DistanceSource.RailDeadReckoning,
                        paceFeedMeters = railAdvance.deltaMeters,
                        paceFeedSec = 1.0,
                        trainable = false,
                        trackedPoint = railAdvance.point,
                        railStatus = railAdvance.status,
                        railDebtMeters = railAdvance.debtMeters,
                        railCapApplied = railAdvance.capApplied,
                        railPathDeltaBeforeCap = railAdvance.pathDeltaBeforeCap,
                    )
                }
            }
            return DistanceTick()
        }

        val cadenceDistance = fallbackEngine.processTick(stepDelta, emitDistance = emitCadenceDistance)
        if (cadenceDistance > 0.0) {
            return DistanceTick(
                deltaMeters = cadenceDistance,
                source = DistanceSource.Cadence,
                paceFeedMeters = cadenceDistance,
                paceFeedSec = 1.0,
                trainable = false,
                trackedPoint = null,
            )
        }
        return DistanceTick()
    }

    private fun trySoftRailAdvance(
        smoothedPoint: GeoPoint,
        deltaTimeSec: Double,
        accuracyMeters: Float,
        rawDeltaMeters: Double?,
    ): DistanceTick? {
        val railAdvance = railMatcher.process(
            RailObservation(
                point = smoothedPoint,
                dtSec = deltaTimeSec,
                rawDeltaMeters = rawDeltaMeters,
                accuracyMeters = accuracyMeters.toDouble(),
                locationSpeedMps = null,
                cadenceDeltaMeters = null,
                quality = ObservationQuality.SOFT_GPS,
            ),
        ) ?: return null
        return railTick(
            railAdvance = railAdvance,
            source = DistanceSource.RailSoftGps,
            deltaTimeSec = deltaTimeSec,
            accuracyMeters = accuracyMeters,
        )
    }

    private fun railTick(
        railAdvance: RailAdvanceResult,
        source: DistanceSource,
        deltaTimeSec: Double,
        accuracyMeters: Float,
    ): DistanceTick {
        // Soft-rail и обычный rail сбрасывают таймер GPS — иначе следом включится DR.
        // Soft-rail and normal rail both reset the GPS timer — otherwise DR starts next.
        ticksSinceLastGps = 0
        val delta = railAdvance.deltaMeters
        if (delta > 0.0) {
            fallbackEngine.recordAuthoritativeDistance(delta)
        }
        fallbackEngine.onGpsSignalReceived()
        // Provenance: даже при delta=0 сохраняем rail source + status, не маскируем в None.
        // Provenance: keep rail source + status even when delta=0; do not mask as None.
        return DistanceTick(
            deltaMeters = delta,
            source = source,
            paceFeedMeters = delta,
            paceFeedSec = railAdvance.coveredDurationSec.takeIf { it > 0.0 } ?: deltaTimeSec,
            trainable = railAdvance.creditKind == RailCreditKind.PROJECTION_CONFIRMED &&
                source == DistanceSource.RailGps &&
                isTrainable(accuracyMeters, source),
            trackedPoint = railAdvance.point,
            railStatus = railAdvance.status,
            railDebtMeters = railAdvance.debtMeters,
            railCapApplied = railAdvance.capApplied,
            railPathDeltaBeforeCap = railAdvance.pathDeltaBeforeCap,
        )
    }

    private fun isTrainable(accuracyMeters: Float, source: DistanceSource): Boolean {
        if (fallbackEngine.currentState != CadenceFallbackEngine.State.STABLE) return false
        if (accuracyMeters > goodGpsAccuracyMeters) return false
        return source == DistanceSource.RailGps || source == DistanceSource.Gps
    }
}
