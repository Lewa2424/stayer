package com.example.stayer

import com.example.stayer.debug.GlobalProgress
import com.example.stayer.debug.PacerLogicHelper
import org.junit.Test
import kotlin.math.roundToInt

class PacerLogicCoverageTest {

    private fun simulateRun(name: String, targetPaceSecPerKm: Int, targetDistKm: Double, segments: List<Pair<Double, Int>>) {
        println("\n=======================================================")
        println("СЦЕНАРИЙ: $name")
        println("Цель: ${targetDistKm} км, темп: ${PacerLogicHelper.formatPaceForSpeech(targetPaceSecPerKm)}")
        println("=======================================================")

        val totalSecTarget = (targetPaceSecPerKm * targetDistKm).roundToInt()
        var distM = 0.0
        var elapsedSec = 0
        var lastKmFloor = 0

        for ((segDist, segPace) in segments) {
            val speedMps = 1000.0 / segPace
            val timeToRunSec = (segDist * 1000.0 / speedMps).roundToInt()

            for (i in 1..timeToRunSec) {
                elapsedSec++
                distM += speedMps

                val curKmFloor = (distM / 1000).toInt()
                if (curKmFloor > lastKmFloor && distM < targetDistKm * 1000.0 - 50) {
                    lastKmFloor = curKmFloor
                    
                    val currentDistKm = distM / 1000.0
                    val remainingDistKm = targetDistKm - currentDistKm
                    val timeLeftSec = totalSecTarget - elapsedSec

                    val expectedElapsed = (targetPaceSecPerKm * currentDistKm).roundToInt()
                    val globalProgress = when {
                        elapsedSec <= expectedElapsed - 10 -> GlobalProgress.AHEAD
                        elapsedSec >= expectedElapsed + 10 -> GlobalProgress.BEHIND
                        else -> GlobalProgress.ON_TRACK
                    }
                    val globalDeltaSec = elapsedSec - expectedElapsed
                    val localDiff = segPace - targetPaceSecPerKm

                    val (prompt, _) = PacerLogicHelper.buildNormalPacerPrompt(
                        globalProgress = globalProgress,
                        globalDeltaSec = globalDeltaSec,
                        localDiffSecPerKm = localDiff,
                        currentPaceSecPerKm = segPace,
                        targetPaceSecPerKm = targetPaceSecPerKm,
                        remainingDistKm = remainingDistKm,
                        timeLeftSec = timeLeftSec,
                        currentDistKm = currentDistKm,
                        pacerPraiseAlternate = false
                    )
                    
                    val timeStr = "${elapsedSec / 60}:${String.format("%02d", elapsedSec % 60)}"
                    println("  [$timeStr / ${curKmFloor}км] Темп отрезка: ${PacerLogicHelper.formatPaceForSpeech(segPace)}")
                    println("  >> \"$prompt\"\n")
                }
            }
        }
        val timeStr = "${elapsedSec / 60}:${String.format("%02d", elapsedSec % 60)}"
        println("  [$timeStr] -> Финиш. ${(distM/1000).toInt()} км.\n")
    }

    @Test
    fun testEveryScenario() {
        val targetPace = 330 // 5:30

        // 1. Идём быстрее плана всё время, постепенно расслабляясь, но не теряя запас
        simulateRun(
            "Набираем запас и держим его",
            targetPace, 5.0,
            listOf(
                2.0 to 300, // 5:00 - копим запас
                2.0 to 330, // 5:30 - держим идеально (запас есть)
                1.0 to 345  // 5:45 - расслабились на финише, но в пределах допустимого
            )
        )

        // 2. Сильно отстаём с самого старта
        simulateRun(
            "Стабильное отставание",
            targetPace, 5.0,
            listOf(
                5.0 to 360  // 6:00 всю дорогу 
            )
        )

        // 3. Отстали, но начинаем нагонять
        simulateRun(
            "Провал на старте, затем героический рывок",
            targetPace, 6.0,
            listOf(
                2.0 to 360, // 6:00 (отстали)
                2.0 to 300, // 5:00 (нагоняем)
                2.0 to 330  // 5:30 (финиш по графику)
            )
        )
        
        // 4. По идеальному графику, с небольшими флуктуациями
        simulateRun(
            "Идеальный пейсинг с микроотклонениями",
            targetPace, 4.0,
            listOf(
                1.0 to 330, // 5:30
                1.0 to 335, // 5:35
                1.0 to 325, // 5:25
                1.0 to 330  // 5:30
            )
        )

        // 5. Выход за лимит времени
        val timeLimitRun = listOf(
            2.0 to 420 // 7:00 (time target is 3km at 4:00 = 12 mins. 2km at 7:00 is 14 mins. Time expires mid-run)
        )
        simulateRun(
            "Время вышло до финиша",
            240, 3.0, // 4:00/km, 3km
            timeLimitRun
        )
    }
}
