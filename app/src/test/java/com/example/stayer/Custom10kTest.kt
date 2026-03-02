package com.example.stayer

import com.example.stayer.debug.GlobalProgress
import com.example.stayer.debug.PacerLogicHelper
import org.junit.Test
import kotlin.math.roundToInt

class Custom10kTest {

    @Test
    fun testCustom10kScenario() {
        println("=== ТЕСТ: 10 км, целевой темп 5:30 ===")
        println("Сценарий:")
        println("1. Первые 2 км с темпом 5:00")
        println("2. Затем снижение темпа, чтобы терять запас")
        println("3. Изменение темпа (+/-) для проверки уведомлений")
        println("4. Финиш вовремя (итог 55 минут)\n")

        val targetDistKm = 10.0
        val targetPace = 330 // 5:30
        val totalSecTarget = 3300 // 55 mins

        var distM = 0.0
        var elapsedSec = 0
        var lastCheckpointN = 0
        val checkpointM = 1000.0

        var alternate = false

        // Helper to run a segment with a specific pace
        fun runSegment(distToRunM: Double, paceSecPerKm: Int) {
            val speedMps = 1000.0 / paceSecPerKm
            val timeToRunSec = (distToRunM / speedMps).roundToInt()
            
            for (i in 1..timeToRunSec) {
                elapsedSec++
                distM += speedMps

                val curN = (distM / checkpointM).toInt()
                if (curN > lastCheckpointN && distM < targetDistKm * 1000.0 - 50) {
                    lastCheckpointN = curN
                    
                    val currentDistKm = distM / 1000.0
                    val remainingDistKm = targetDistKm - currentDistKm
                    val timeLeftSec = totalSecTarget - elapsedSec

                    val expectedElapsed = (targetPace * currentDistKm).roundToInt()
                    val globalProgress = when {
                        elapsedSec < expectedElapsed - 10 -> GlobalProgress.AHEAD
                        elapsedSec > expectedElapsed + 10 -> GlobalProgress.BEHIND
                        else -> GlobalProgress.ON_TRACK
                    }
                    val globalDeltaSec = elapsedSec - expectedElapsed
                    val localDiff = paceSecPerKm - targetPace

                    val (prompt, nextAlt) = PacerLogicHelper.buildNormalPacerPrompt(
                        globalProgress = globalProgress,
                        globalDeltaSec = globalDeltaSec,
                        localDiffSecPerKm = localDiff,
                        currentPaceSecPerKm = paceSecPerKm,
                        targetPaceSecPerKm = targetPace,
                        remainingDistKm = remainingDistKm,
                        timeLeftSec = timeLeftSec,
                        currentDistKm = currentDistKm,
                        pacerPraiseAlternate = alternate
                    )
                    alternate = nextAlt
                    
                    val timeStr = "${elapsedSec / 60}:${String.format("%02d", elapsedSec % 60)}"
                    println("  [$timeStr / ${String.format("%.0f", distM)}м] Темп: ${PacerLogicHelper.formatPaceForSpeech(paceSecPerKm)}")
                    println("  >> \"$prompt\"\n")
                }
                
                if (distM >= targetDistKm * 1000.0) {
                    val timeStr = "${elapsedSec / 60}:${String.format("%02d", elapsedSec % 60)}"
                    println("  [$timeStr] -> Финиш. 10 км.")
                    break
                }
            }
        }

        // 1. Первые 2 км с темпом 5:00
        runSegment(2000.0, 300)

        // 2. Снижение темпа, чтобы терялась фора 
        // Запас после 2км = 60 секунд. 
        // Чтобы потерять запас к 5-му км (еще 3км), темп должен быть на 20 сек медленнее целевого. Бежим по 6:00 (360 сек/км)
        runSegment(3000.0, 360)

        // 3. Изменение темпа (+/-) 
        // Бежим 2км по 5:30 (330)
        runSegment(2000.0, 330)
        
        // Бежим 1км очень быстро, чтобы снова сделать запас 4:30 (270)
        runSegment(1000.0, 270)

        // 4. Финиш вовремя (подгоняем оставшуюся дистанцию, чтобы вышло 55 минут)
        // Оставшаяся дистанция: 2км = 2000м
        // Прошедшее время: 10 + 18 + 11 + 4.5 = 43.5 минут
        // Осталось времени до 55 минут: 11.5 минут = 690 секунд
        // Темп для финиша: 690 / 2 = 345 сек/км (5:45)
        runSegment(2000.0, 345)
        
        println("=== ТЕСТ ЗАВЕРШЕН ===")
    }
}
