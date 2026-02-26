package com.example.stayer

import com.example.stayer.debug.PacerLogicHelper
import org.junit.Test

class ComboPacerTest {

    @Test
    fun testComboFlattenAndHints() {
        println("=== ТЕСТ: Комбинированная тренировка ===\n")

        // Build a combo scenario:
        // Warmup 5m → 2km @ 5:30 → 3×(2m work @ 4:30 + 1m rest) → 1km @ 5:30 → Cooldown 3m
        val combo = ComboScenario(listOf(
            ComboBlock.WarmupBlock(300, null),                           // 5 min warmup
            ComboBlock.PaceBlock(2.0, 330),                             // 2 km @ 5:30
            ComboBlock.IntervalBlock(120, 270, 60, null, 3),            // 3× (2m@4:30 + 1m rest)
            ComboBlock.PaceBlock(1.0, 330),                             // 1 km @ 5:30
            ComboBlock.CooldownBlock(180, null)                          // 3 min cooldown
        ))

        // Flatten
        val segments = combo.flatten()
        println("Развернутые сегменты (${segments.size}):")
        for ((i, seg) in segments.withIndex()) {
            val info = when {
                seg.type == "PACE" -> "${seg.distanceKm} км, темп ${seg.targetPaceSecPerKm?.let { PacerLogicHelper.formatPaceForSpeech(it) } ?: "—"}"
                else -> "${seg.durationSec}с, темп ${seg.targetPaceSecPerKm?.let { PacerLogicHelper.formatPaceForSpeech(it) } ?: "—"}"
            }
            println("  ${i + 1}. ${seg.type}: $info")
        }

        // Verify structure: WARMUP + PACE + 3×(WORK+REST) + PACE + COOLDOWN = 10
        assert(segments.size == 10) { "Expected 10 segments, got ${segments.size}" }
        assert(segments[0].type == "WARMUP") { "First should be WARMUP" }
        assert(segments[1].type == "PACE" && segments[1].distanceKm == 2.0) { "2nd should be PACE 2km" }
        assert(segments[2].type == "WORK") { "3rd should be WORK" }
        assert(segments[3].type == "REST") { "4th should be REST" }
        assert(segments[8].type == "PACE" && segments[8].distanceKm == 1.0) { "9th should be PACE 1km" }
        assert(segments[9].type == "COOLDOWN") { "Last should be COOLDOWN" }

        println("\n✓ Структура сегментов корректна\n")

        // Estimate totals
        val totalDist = combo.estimateTotalDistanceKm()
        val totalTime = combo.estimateTotalTimeSec()
        println("Итого: ~${String.format("%.1f", totalDist)} км, ~${totalTime / 60}:${String.format("%02d", totalTime % 60)}")

        // Simulate PACE segment hints
        println("\n═══ Симуляция PACE сегмента (2 км @ 5:30) ═══")
        val paceSeg = segments[1]
        val targetDistM = paceSeg.distanceKm!! * 1000.0
        val targetPace = paceSeg.targetPaceSecPerKm!!
        val speedKmh = 3600.0 / targetPace  // exactly on pace
        val checkpointStep = minOf(500.0, targetDistM * 0.1).coerceAtLeast(100.0)
        println("Скорость: ${String.format("%.1f", speedKmh)} км/ч, чекпоинт каждые ${checkpointStep.toInt()} м")

        var distM = 0.0
        var lastN = 0
        for (sec in 1..1200) {  // up to 20 min
            distM += speedKmh * 1000.0 / 3600.0
            val curN = (distM / checkpointStep).toInt()
            if (curN > lastN && distM < targetDistM - 50) {
                lastN = curN
                val curPace = (3600.0 / speedKmh).toInt()
                val diff = curPace - targetPace
                val remaining = (targetDistM - distM) / 1000.0
                val prompt = when {
                    kotlin.math.abs(diff) <= 15 -> "Темп хороший. Осталось ${String.format("%.1f", remaining)} км."
                    diff > 15 -> "Ускорьтесь."
                    else -> "Не гони."
                }
                println("  [${sec}с / ${String.format("%.0f", distM)}м] -> \"$prompt\"")
            }
            if (distM >= targetDistM) {
                println("  [${sec}с] -> \"Дистанция пройдена. 2.0 км.\"")
                break
            }
        }

        // Simulate WORK hint with 2-layer
        println("\n═══ Симуляция WORK сегмента (2мин @ 4:30) ═══")
        val workSeg = segments[2]
        val workPace = workSeg.targetPaceSecPerKm!!
        // Simulate running at 4:44 (slightly slower, within ±15s)
        val simPace = workPace + 14  // 284 = 4:44, diff = +14
        println("Целевой: ${PacerLogicHelper.formatPaceForSpeech(workPace)}, бежит: ${PacerLogicHelper.formatPaceForSpeech(simPace)}")

        val timing = PacerLogicHelper.intervalHintTiming(workSeg.durationSec)
        if (timing != null) {
            println("Таймер: первый через ${timing.first}с, затем каждые ${timing.second}с")
        } else if (workSeg.durationSec in 30..119) {
            val mp = workSeg.durationSec / 2
            val avgPace = simPace  // constant pace = avg equals instant
            val (hint, _) = PacerLogicHelper.buildIntervalHint(simPace, workPace, false, avgPace)
            println("  [${mp}с] -> \"$hint\"")
        }

        println("\n═══ JSON serialization test ═══")
        val gson = comboGson()
        val json = gson.toJson(combo)
        println("JSON length: ${json.length}")
        val restored = gson.fromJson(json, ComboScenario::class.java)
        assert(restored.blocks.size == combo.blocks.size) { "Block count mismatch after deserialization" }
        val restoredSegments = restored.flatten()
        assert(restoredSegments.size == segments.size) { "Segment count mismatch after deserialization" }
        println("✓ Сериализация/десериализация OK")

        println("\n=== ТЕСТ ПРОЙДЕН ===")
    }
}
