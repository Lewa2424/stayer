package com.example.stayer.engine

import com.example.stayer.pathnet.domain.RailAdvanceResult
import com.example.stayer.pathnet.domain.RailMatcher
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
        return DistanceTick(trackedPoint = smoothedPoint)
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
    ): DistanceTick {
        fallbackEngine.onGpsSignalReceived()
        ticksSinceLastGps = 0

        if (routeFollowEnabled && !railMatcher.isLocked) {
            railMatcher.tryLock(smoothedPoint)
        }

        if (routeFollowEnabled && railMatcher.isLocked) {
            val railAdvance = railMatcher.advance(smoothedPoint, deltaTimeSec)
            if (railAdvance != null) {
                return railTick(
                    railAdvance = railAdvance,
                    source = DistanceSource.RailGps,
                    deltaTimeSec = deltaTimeSec,
                    accuracyMeters = accuracyMeters,
                )
            }
        }

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
    ): DistanceTick {
        val railsLocked = routeFollowEnabled && railMatcher.isLocked
        fallbackEngine.processGpsRejected(suppressBlind = railsLocked)

        if (railsLocked) {
            if (!railMatcher.isLocked) {
                railMatcher.tryLock(smoothedPoint)
            }
            if (railMatcher.isLocked) {
                val projection = trySoftRailAdvance(smoothedPoint, deltaTimeSec)
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
                val railAdvance = railMatcher.advanceByArcLength(arcMeters, 1.0)
                if (railAdvance != null && railAdvance.deltaMeters > 0.0) {
                    fallbackEngine.recordAuthoritativeDistance(railAdvance.deltaMeters)
                    return DistanceTick(
                        deltaMeters = railAdvance.deltaMeters,
                        source = DistanceSource.RailDeadReckoning,
                        paceFeedMeters = railAdvance.deltaMeters,
                        paceFeedSec = 1.0,
                        trainable = false,
                        trackedPoint = railAdvance.point,
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

    private fun trySoftRailAdvance(smoothedPoint: GeoPoint, deltaTimeSec: Double): DistanceTick? {
        val railAdvance = railMatcher.advance(smoothedPoint, deltaTimeSec) ?: return null
        return railTick(
            railAdvance = railAdvance,
            source = DistanceSource.RailSoftGps,
            deltaTimeSec = deltaTimeSec,
            accuracyMeters = Float.MAX_VALUE,
        )
    }

    private fun railTick(
        railAdvance: RailAdvanceResult,
        source: DistanceSource,
        deltaTimeSec: Double,
        accuracyMeters: Float,
    ): DistanceTick {
        val delta = railAdvance.deltaMeters
        if (delta > 0.0) {
            fallbackEngine.recordAuthoritativeDistance(delta)
        }
        fallbackEngine.onGpsSignalReceived()
        return DistanceTick(
            deltaMeters = delta,
            source = if (delta > 0.0) source else DistanceSource.None,
            paceFeedMeters = delta,
            paceFeedSec = deltaTimeSec,
            trainable = isTrainable(accuracyMeters, source),
            trackedPoint = railAdvance.point,
        )
    }

    private fun isTrainable(accuracyMeters: Float, source: DistanceSource): Boolean {
        if (fallbackEngine.currentState != CadenceFallbackEngine.State.STABLE) return false
        if (accuracyMeters > goodGpsAccuracyMeters) return false
        return source == DistanceSource.RailGps || source == DistanceSource.Gps
    }
}
