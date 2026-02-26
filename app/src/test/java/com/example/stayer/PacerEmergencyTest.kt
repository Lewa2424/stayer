package com.example.stayer

import com.example.stayer.debug.GlobalProgress
import com.example.stayer.debug.PacerLogicHelper
import org.junit.Test
import kotlin.math.roundToInt

class PacerEmergencyTest {

    @Test
    fun test15KmWith50mSteps() {
        val targetPaceSecPerKm = 360 // 6:00/km
        val targetDistanceKm = 15.0
        val targetTotalSec = (targetPaceSecPerKm * targetDistanceKm).roundToInt()

        var currentDistKm = 0.0
        var currentElapsedSec = 0
        var lastCheckpointProgress = GlobalProgress.ON_TRACK
        var lastPaceCheckDistance = 0.0
        var lastEmergencyCheckDistKm = 0.0
        var emergencyCooldownUntilDistKm = 0.0
        var pacerPraiseAlternate = false

        println("=== TЕСТ: 15 км, детализация 50 метров ===")
        println("Цель: 15 км по 6:00/km (итого 1:30:00)")
        println("Плановые чекпоинты: каждые 1.5 км (10% от 15км)")
        println("Аварийная проверка: каждые 250 метров (0.25 км)")

        // Define a sequence of paces for different segments. We will step by 50m (0.05 km)
        // 0-2k: ON_TRACK (exactly 6:00 = 360s)
        // 2-3k: BEHIND (slowing down to 6:30 = 390s, triggering emergency)
        // 3-4k: AHEAD (sprinting at 4:30 = 270s to catch up and gain a lead)
        // 4-8k: ON_TRACK (exactly 6:00 = 360s)

        val segments = listOf(
            2.0 to 360,   // 0 to 2.0 km at 6:00
            3.0 to 390,   // 2.0 to 3.0 km at 6:30
            4.0 to 270,   // 3.0 to 4.0 km at 4:30
            8.0 to 360    // 4.0 to 8.0 km at 6:00
        )

        val stepDist = 0.05 // 50 meters
        
        for ((targetDist, pace) in segments) {
            while (currentDistKm < targetDist - 0.001) {
                // Determine the next step size (don't overshoot the segment end)
                val remainingInSegment = targetDist - currentDistKm
                val actualStep = if (remainingInSegment < stepDist) remainingInSegment else stepDist
                
                currentDistKm += actualStep
                currentElapsedSec += (actualStep * pace).roundToInt()

                val targetElapsed = (targetPaceSecPerKm * currentDistKm).roundToInt()
                val globalDelta = currentElapsedSec - targetElapsed

                // 1. Плановые чекпоинты (каждые 1.5 км)
                val plannedStepSize = targetDistanceKm * 0.1 // 1.5 km
                val distanceSinceLastPlanned = currentDistKm - lastPaceCheckDistance
                
                if (distanceSinceLastPlanned >= plannedStepSize - 0.01 && currentDistKm > 0.1) {
                    lastPaceCheckDistance = currentDistKm
                    
                    val avgPaceTotalSecPerKm = currentElapsedSec / currentDistKm
                    val predictedFinishSec = (avgPaceTotalSecPerKm * targetDistanceKm).roundToInt()
                    val finishDeltaSec = predictedFinishSec - targetTotalSec
                    val remainingDistKm = (targetDistanceKm - currentDistKm).coerceAtLeast(0.0)
                    val timeLeftSec = targetTotalSec - currentElapsedSec
                    
                    val progress = when {
                        kotlin.math.abs(finishDeltaSec.toDouble()) <= 30.0 -> GlobalProgress.ON_TRACK
                        finishDeltaSec > 30 -> GlobalProgress.BEHIND
                        else -> GlobalProgress.AHEAD
                    }
                    
                    val (prompt, nextPraise) = PacerLogicHelper.buildNormalPacerPrompt(
                        globalProgress = progress,
                        globalDeltaSec = finishDeltaSec,
                        localDiffSecPerKm = pace - targetPaceSecPerKm,
                        currentPaceSecPerKm = pace,
                        targetPaceSecPerKm = targetPaceSecPerKm,
                        remainingDistKm = remainingDistKm,
                        timeLeftSec = timeLeftSec,
                        currentDistKm = currentDistKm,
                        pacerPraiseAlternate = pacerPraiseAlternate
                    )
                    pacerPraiseAlternate = nextPraise
                    
                    val statusStr = if (globalDelta < 0) "ЗАПАС: ${-globalDelta}с" else if (globalDelta > 0) "ОТСТАВАНИЕ: ${globalDelta}с" else "В ГРАФИКЕ"
                    println("\n[${String.format("%.2f", currentDistKm)} км] ($statusStr) Текущий темп: ${PacerLogicHelper.formatPaceForSpeech(pace)}")
                    println(">>> ПЛАНОВЫЙ ЧЕКПОИНТ: $prompt")
                    
                    lastCheckpointProgress = progress
                    emergencyCooldownUntilDistKm = 0.0
                }

                // 2. Аварийный мониторинг (проверяется каждые 250м)
                if (currentDistKm - lastEmergencyCheckDistKm >= 0.25 - 0.01) {
                    lastEmergencyCheckDistKm = currentDistKm
                    val nextCheckpointKm = lastPaceCheckDistance + plannedStepSize
                    val distToNextCheckpoint = nextCheckpointKm - currentDistKm

                    if (currentDistKm >= emergencyCooldownUntilDistKm && distToNextCheckpoint >= 0.1) {
                        val alert = PacerLogicHelper.buildEmergencyAlert(
                            prevGlobalProgress = lastCheckpointProgress,
                            currentDistKm = currentDistKm,
                            currentElapsedSec = currentElapsedSec,
                            targetPaceSecPerKm = targetPaceSecPerKm,
                            targetDistKm = targetDistanceKm,
                            targetTotalSec = targetTotalSec
                        )
                        if (alert != null) {
                            val statusStr = if (globalDelta < 0) "ЗАПАС: ${-globalDelta}с" else if (globalDelta > 0) "ОТСТАВАНИЕ: ${globalDelta}с" else "В ГРАФИКЕ"
                            println("\n[${String.format("%.2f", currentDistKm)} км] ($statusStr) Текущий темп: ${PacerLogicHelper.formatPaceForSpeech(pace)}")
                            println("!!! АВАРИЙНАЯ ПРОВЕРКА (250м): СРАБОТАЛА !!! -> $alert")
                            emergencyCooldownUntilDistKm = nextCheckpointKm
                        } else {
                            // Silent check passed (no alert)
                        }
                    }
                }
            }
        }
    }
}
