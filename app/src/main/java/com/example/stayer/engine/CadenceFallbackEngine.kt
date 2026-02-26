package com.example.stayer.engine

import android.content.Context
import android.content.SharedPreferences
import java.util.ArrayDeque
import kotlin.math.roundToInt

class CadenceFallbackEngine(private val context: Context) {

    enum class State { STABLE, BLIND, QUARANTINE }

    var currentState: State = State.STABLE
        private set

    // --- State Machine & Timers ---
    private var stableTicks = 0
    private var quarantineTicks = 0
    private var ticksSinceLastGps = 0

    // --- Cadence Calculation (Ring Buffer) ---
    private val stepHistory = ArrayDeque<Int>()
    private val historyWindowSize = 10 // 10 seconds

    // --- Calibration Data Collection ---
    private var currentPhaseStepCount = 0
    private var currentPhaseGpsDistanceM = 0.0

    // --- Storage ---
    private val prefs: SharedPreferences = context.getSharedPreferences("StepCalibrationProfile", Context.MODE_PRIVATE)

    // --- Constants ---
    private val REQUIRED_STABLE_TICKS_FOR_CALIBRATION = 30
    private val MAX_TICKS_WITHOUT_GPS = 7
    private val QUARANTINE_DURATION_TICKS = 15

    /**
     * Updates the cadence ring buffer and returns current calculated cadence.
     */
    private fun updateAndGetCadence(stepDelta: Int): Int {
        if (stepHistory.size >= historyWindowSize) {
            stepHistory.removeFirst()
        }
        stepHistory.addLast(stepDelta)

        val totalSteps = stepHistory.sum()
        return if (stepHistory.isNotEmpty()) {
            (totalSteps.toDouble() * (60.0 / stepHistory.size)).roundToInt()
        } else {
            0
        }
    }

    /**
     * Determines which cadence bucket a given cadence falls into.
     */
    private fun getBucketKeyForCadence(cadence: Int): String {
        return when {
            cadence < 140 -> "bucket_under_140"
            cadence in 140..150 -> "bucket_140_150"
            cadence in 151..160 -> "bucket_150_160"
            else -> "bucket_over_160" // > 160
        }
    }

    /**
     * Gets default fallback stride length for a specific bucket.
     */
    private fun getDefaultStrideForBucket(bucketKey: String): Double {
        return when (bucketKey) {
            "bucket_under_140" -> 0.70
            "bucket_140_150" -> 0.78
            "bucket_150_160" -> 0.85
            else -> 0.92 // bucket_over_160
        }
    }

    /**
     * Retrieves the calibrated stride length from storage or falls back to defaults.
     */
    private fun getStrideLengthForCadence(cadence: Int): Double {
        if (cadence <= 0) return 0.0
        val bucketKey = getBucketKeyForCadence(cadence)
        val defaultStride = getDefaultStrideForBucket(bucketKey)
        // Store and retrieve as Float since SharedPreferences doesn't support Double directly
        return prefs.getFloat(bucketKey, defaultStride.toFloat()).toDouble()
    }

    /**
     * Updates the calibration profile with new observation using exponential moving average.
     */
    private fun calibrateStrideLength(cadence: Int, actualStride: Double) {
        val bucketKey = getBucketKeyForCadence(cadence)
        val oldStride = getStrideLengthForCadence(cadence)
        
        // EMA: 80% old, 20% new
        val newStride = (oldStride * 0.8) + (actualStride * 0.2)
        
        prefs.edit().putFloat(bucketKey, newStride.toFloat()).apply()
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Called every second by the tickRunnable in WorkoutForegroundService.
     * @param stepDelta Steps taken since the last tick (last 1 second).
     * @return Distance (meters) to add to the total training distance.
     */
    fun processTick(stepDelta: Int): Double {
        val currentCadence = updateAndGetCadence(stepDelta)
        ticksSinceLastGps++

        // 1. Check for signal loss condition
        if (currentState == State.STABLE && ticksSinceLastGps > MAX_TICKS_WITHOUT_GPS) {
            // Signal lost -> go BLIND
            currentState = State.BLIND
            stableTicks = 0
            currentPhaseStepCount = 0
            currentPhaseGpsDistanceM = 0.0
        }

        // 2. Act based on current state
        return when (currentState) {
            State.STABLE -> {
                stableTicks++
                currentPhaseStepCount += stepDelta

                // Check if we can commit calibration
                if (stableTicks >= REQUIRED_STABLE_TICKS_FOR_CALIBRATION) {
                    if (currentPhaseStepCount > 0 && currentPhaseGpsDistanceM > 0.0) {
                        val observedStride = currentPhaseGpsDistanceM / currentPhaseStepCount
                        // Calculate average cadence over this stable period
                        val avgCadence = (currentPhaseStepCount.toDouble() / (stableTicks / 60.0)).roundToInt()
                        
                        // Sanity check: stride should be somewhat realistic (0.3m to 1.5m)
                        if (observedStride in 0.3..1.5) {
                            calibrateStrideLength(avgCadence, observedStride)
                        }
                    }
                    
                    // Reset windows after committing
                    stableTicks = 0
                    currentPhaseStepCount = 0
                    currentPhaseGpsDistanceM = 0.0
                }
                
                0.0 // True distance is handled by GPS in STABLE state
            }
            State.BLIND -> {
                val strideMeters = getStrideLengthForCadence(currentCadence)
                stepDelta * strideMeters
            }
            State.QUARANTINE -> {
                quarantineTicks++
                
                if (quarantineTicks >= QUARANTINE_DURATION_TICKS) {
                    // Survive quarantine -> go back to STABLE
                    currentState = State.STABLE
                    stableTicks = 0
                    quarantineTicks = 0
                    currentPhaseStepCount = 0
                    currentPhaseGpsDistanceM = 0.0
                }
                
                // Still use fallback distance while in quarantine
                val strideMeters = getStrideLengthForCadence(currentCadence)
                stepDelta * strideMeters
            }
        }
    }

    /**
     * Called when the filtering layer successfully accepts a geographic location.
     * @param deltaMeters Distance delta calculated by GPS vs last point.
     * @return Distance (meters) to actually add. Will return 0.0 if rejecting the point 
     *         to prevent double counting after exiting BLIND/QUARANTINE.
     */
    fun processGpsAccepted(deltaMeters: Double): Double {
        ticksSinceLastGps = 0

        return when (currentState) {
            State.STABLE -> {
                // Collect data for calibration
                currentPhaseGpsDistanceM += deltaMeters
                // Normal addition
                deltaMeters
            }
            State.BLIND -> {
                // First good point after blind phase. Enter QUARANTINE.
                currentState = State.QUARANTINE
                quarantineTicks = 0
                // Crucial: Reject this huge GPS delta, we already covered it with steps!
                0.0
            }
            State.QUARANTINE -> {
                // We are inside quarantine, GPS points are good, but we ignore their distance
                // because we are returning fallback distance in processTick().
                0.0
            }
        }
    }

    /**
     * Called when the filtering layer receives a point but rejects it (e.g. speed jump).
     */
    fun processGpsRejected(reason: String) {
        when (currentState) {
            State.STABLE -> {
                // A rejected point drops us immediately to BLIND mode
                currentState = State.BLIND
                stableTicks = 0
                currentPhaseStepCount = 0
                currentPhaseGpsDistanceM = 0.0
            }
            State.QUARANTINE -> {
                // Failed quarantine -> back to BLIND
                currentState = State.BLIND
                quarantineTicks = 0
            }
            State.BLIND -> {
                // Already blind, do nothing
            }
        }
    }
}
