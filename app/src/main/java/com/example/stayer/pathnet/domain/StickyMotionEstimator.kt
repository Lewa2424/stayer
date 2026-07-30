package com.example.stayer.pathnet.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Оценка реального движения без использования candidate-проекции.
 * Real-motion estimate that does not use the candidate projection.
 *
 * EMA — только ограничитель/контекст, не самостоятельный одометр.
 * EMA is a limiter/context only, not a standalone odometer.
 */
class StickyMotionEstimator(
    private val vmaxMps: Double = 7.0,
    private val emaAlpha: Double = 0.22,
) {
    data class Estimate(
        val distanceMeters: Double,
        val confidence: Int,
        val stationary: Boolean,
        val sources: List<String>,
    )

    var emaSpeedMps: Double = 0.0
        private set
    var emaConfidence: Int = 0
        private set

    fun reset() {
        emaSpeedMps = 0.0
        emaConfidence = 0
    }

    fun hardCapMeters(dtSec: Double): Double = vmaxMps * max(dtSec, 1e-3)

    fun estimate(obs: RailObservation): Estimate {
        val dt = max(obs.dtSec, 1e-3)
        val hardCap = hardCapMeters(dt)

        val cadence = obs.cadenceDeltaMeters?.let { min(max(it, 0.0), hardCap) }
        val speedDistance = obs.locationSpeedMps?.let { speed ->
            val s = max(speed, 0.0)
            if (s <= vmaxMps * 1.25) min(s * dt, hardCap) else null
        }
        val raw = obs.rawDeltaMeters?.let { rawDelta ->
            val rawSpeed = max(rawDelta, 0.0) / dt
            if (rawSpeed <= vmaxMps * 1.35) min(max(rawDelta, 0.0), hardCap) else null
        }

        if (stationaryVotes(obs) >= 2) {
            return Estimate(0.0, 3, true, listOf("cadence=0", "speed=0"))
        }

        val trusted = mutableListOf<Pair<String, Double>>()

        if (cadence != null && cadence > 0.12) {
            trusted += "cadence" to cadence
            if (speedDistance != null && closeEnough(cadence, speedDistance)) {
                trusted += "locationSpeed" to speedDistance
            }
            if (raw != null && closeEnough(cadence, raw)) {
                trusted += "rawDelta" to raw
            }
        } else if (
            speedDistance != null &&
            raw != null &&
            speedDistance > 0.12 &&
            raw > 0.12 &&
            closeEnough(speedDistance, raw)
        ) {
            return Estimate(raw, 2, false, listOf("rawDelta", "locationSpeed"))
        } else {
            val expected = emaSpeedMps * dt
            if (emaConfidence >= 5 && expected > 0.15) {
                if (
                    speedDistance != null &&
                    speedDistance > 0.12 &&
                    closeEnough(speedDistance, expected)
                ) {
                    trusted += "locationSpeed+EMA" to speedDistance
                } else if (raw != null && raw > 0.12 && closeEnough(raw, expected)) {
                    trusted += "rawDelta+EMA" to raw
                }
            }
        }

        if (trusted.isEmpty()) {
            return Estimate(0.0, 0, false, emptyList())
        }

        val values = trusted.map { it.second }.sorted()
        val mid = values[values.size / 2]
        val confidence = min(3, trusted.size + if (cadence != null) 1 else 0)
        return Estimate(min(mid, hardCap), confidence, false, trusted.map { it.first })
    }

    fun updateEma(creditMeters: Double, obs: RailObservation, supported: Boolean) {
        if (creditMeters <= 0.0 || !supported) {
            if (stationaryVotes(obs) >= 2) decayEma(obs.dtSec)
            return
        }
        val speed = creditMeters / max(obs.dtSec, 1e-3)
        emaSpeedMps = if (emaConfidence == 0) {
            speed
        } else {
            (1.0 - emaAlpha) * emaSpeedMps + emaAlpha * speed
        }
        emaConfidence = min(20, emaConfidence + 1)
    }

    fun decayEma(dtSec: Double) {
        val factor = exp(-max(dtSec, 0.0) / 2.5)
        emaSpeedMps *= factor
        emaConfidence = max(0, emaConfidence - 1)
        if (emaSpeedMps < 0.08) emaSpeedMps = 0.0
    }

    private fun stationaryVotes(obs: RailObservation): Int {
        var votes = 0
        if (obs.cadenceDeltaMeters != null && obs.cadenceDeltaMeters <= 0.12) votes++
        if (obs.locationSpeedMps != null && obs.locationSpeedMps <= 0.18) votes++
        return votes
    }

    private fun closeEnough(left: Double, right: Double): Boolean {
        val tolerance = max(0.8, 0.55 * max(max(abs(left), abs(right)), 1.0))
        return abs(left - right) <= tolerance
    }
}
