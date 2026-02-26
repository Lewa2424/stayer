package com.example.stayer

import com.example.stayer.debug.PacerLogicHelper
import org.junit.Test
import kotlin.math.abs

class IntervalPacerTest {

    @Test
    fun testAllIntervalScenarios() {
        println("=== ТЕСТ: Адаптивный интервальный штурман (2 слоя) ===\n")

        data class Scenario(val name: String, val durationSec: Int, val targetPace: Int, val runPaces: List<Pair<IntRange, Int>>)

        val scenarios = listOf(
            Scenario("Спринт 20с", 20, 240, listOf(0..20 to 230)),  // faster than target
            Scenario("Средний 90с", 90, 330, listOf(0..45 to 340, 46..90 to 350)),  // starts ok, slows
            Scenario("3 мин", 180, 330, listOf(0..60 to 325, 61..120 to 360, 121..180 to 320)),
            Scenario("6 мин", 360, 330, listOf(0..120 to 330, 121..240 to 370, 241..360 to 310)),
            Scenario("10 мин", 600, 300, listOf(0..180 to 295, 181..360 to 340, 361..600 to 280))
        )

        for (sc in scenarios) {
            println("═══ ${sc.name} (цель: ${PacerLogicHelper.formatPaceForSpeech(sc.targetPace)}) ═══")

            val timing = PacerLogicHelper.intervalHintTiming(sc.durationSec)
            if (timing != null) {
                println("  Таймер: первый хинт через ${timing.first}с, затем каждые ${timing.second}с")
            } else if (sc.durationSec in 30..119) {
                println("  Таймер: одиночный хинт на ${sc.durationSec / 2}с")
            } else {
                println("  Таймер: без хинтов (только отчёт в конце)")
            }

            val workIgnoreSec = 3
            var lastHintInSegSec = -1
            var alternate = false
            var midHintDone = false

            // Accumulate distance for average pace calculation
            var stableStartSec = workIgnoreSec
            var totalDistAtStableStartM = 0.0
            var totalDistM = 0.0

            for (sec in 0..sc.durationSec) {
                val curPace = sc.runPaces.firstOrNull { sec in it.first }?.second ?: sc.targetPace
                val inSegSec = sec
                val stableInSegSec = inSegSec - workIgnoreSec
                val remainingSec = sc.durationSec - sec

                // Accumulate distance (meters per second = 1000/pace)
                if (sec > 0) {
                    totalDistM += 1000.0 / curPace
                }
                if (sec == workIgnoreSec) {
                    totalDistAtStableStartM = totalDistM
                }

                if (stableInSegSec < 5) continue

                // Compute average pace over stable phase
                val stableDistM = totalDistM - totalDistAtStableStartM
                val stableTimeSec = sec - stableStartSec
                val avgPace = if (stableDistM > 25.0) (stableTimeSec / (stableDistM / 1000.0)).toInt() else null

                // Check if should hint
                val shouldHint = if (timing != null) {
                    val (firstAt, repeatEvery) = timing
                    if (lastHintInSegSec < 0) {
                        stableInSegSec >= firstAt
                    } else {
                        stableInSegSec - lastHintInSegSec >= repeatEvery
                    }
                } else if (sc.durationSec in 30..119) {
                    val midPoint = sc.durationSec / 2
                    inSegSec >= midPoint && !midHintDone
                } else {
                    false
                }

                if (shouldHint && remainingSec > 15) {
                    val (hint, nextAlt) = PacerLogicHelper.buildIntervalHint(
                        curPace, sc.targetPace, alternate,
                        avgPaceSecPerKm = avgPace
                    )
                    alternate = nextAlt
                    lastHintInSegSec = stableInSegSec
                    if (timing == null) midHintDone = true

                    val avgInfo = if (avgPace != null) " [среднее: ${PacerLogicHelper.formatPaceForSpeech(avgPace)}]" else ""
                    println("  [${sec}с] (темп ${PacerLogicHelper.formatPaceForSpeech(curPace)})$avgInfo -> \"$hint\"")
                }

                if (remainingSec == 10) {
                    println("  [${sec}с] -> \"Смена через 10 секунд\"")
                }
            }

            val lastPace = sc.runPaces.last().second
            val endReport = PacerLogicHelper.buildIntervalEndReport(lastPace, sc.targetPace)
            println("  [конец] (факт ${PacerLogicHelper.formatPaceForSpeech(lastPace)}) -> \"$endReport\"")
            println()
        }

        // Special test: instant OK but average drifting
        println("═══ ТЕСТ 2-го СЛОЯ: мгновенный ОК, средний дрейфует ═══")
        println("  Сценарий: 8 минут, цель 5:30/км (330)")
        println("  0-4 мин: бежит 5:44 (344, instant ОК ±15с, но средний копит дефицит)")
        println("  4-8 мин: бежит 5:20 (320, instant ОК, средний выравнивается)")
        println()

        val sc2 = Scenario("8 мин дрейф", 480, 330,
            listOf(0..240 to 344, 241..480 to 320))

        val timing2 = PacerLogicHelper.intervalHintTiming(480)!!
        var lastHint2 = -1
        var alt2 = false
        var totalDist2 = 0.0
        var stableStartDist2 = 0.0

        for (sec in 0..480) {
            val curPace = sc2.runPaces.firstOrNull { sec in it.first }?.second ?: 330
            if (sec > 0) totalDist2 += 1000.0 / curPace
            if (sec == 3) stableStartDist2 = totalDist2

            val stableInSec = sec - 3
            if (stableInSec < 5) continue

            val stableDist = totalDist2 - stableStartDist2
            val stableTime = sec - 3
            val avgPace = if (stableDist > 25.0) (stableTime / (stableDist / 1000.0)).toInt() else null

            val shouldHint = if (lastHint2 < 0) stableInSec >= timing2.first
                             else stableInSec - lastHint2 >= timing2.second

            if (shouldHint && (480 - sec) > 15) {
                val (hint, nextAlt) = PacerLogicHelper.buildIntervalHint(
                    curPace, 330, alt2, avgPaceSecPerKm = avgPace
                )
                alt2 = nextAlt
                lastHint2 = stableInSec

                val avgInfo = if (avgPace != null) " [среднее: ${PacerLogicHelper.formatPaceForSpeech(avgPace)}]" else ""
                println("  [${sec}с] (темп ${PacerLogicHelper.formatPaceForSpeech(curPace)})$avgInfo -> \"$hint\"")
            }
        }
        println("  [конец] -> \"${PacerLogicHelper.buildIntervalEndReport(320, 330)}\"")
    }
}
