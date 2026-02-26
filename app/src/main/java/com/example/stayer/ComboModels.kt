package com.example.stayer

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

// ── Combo block types ───────────────────────────────────────────────────────

sealed class ComboBlock {
    data class WarmupBlock(
        val durationSec: Int,
        val pace: Int? = null
    ) : ComboBlock()

    data class PaceBlock(
        val distanceKm: Double?,       // null = "оставшаяся дистанция"
        val paceSecPerKm: Int
    ) : ComboBlock()

    data class IntervalBlock(
        val workSec: Int,
        val workPace: Int,
        val restSec: Int,
        val restPace: Int? = null,
        val repeats: Int
    ) : ComboBlock()

    data class CooldownBlock(
        val durationSec: Int,
        val pace: Int? = null
    ) : ComboBlock()
}

data class ComboScenario(val blocks: List<ComboBlock>)

// ── Flatten: ComboScenario → List<Segment> ──────────────────────────────────

fun ComboScenario.flatten(): List<Segment> {
    val result = mutableListOf<Segment>()

    for (block in blocks) {
        when (block) {
            is ComboBlock.WarmupBlock -> {
                result.add(Segment("WARMUP", block.durationSec, targetPaceSecPerKm = block.pace))
            }
            is ComboBlock.PaceBlock -> {
                // PACE segment: distance-based, durationSec=0 (ignored),
                // distanceKm carries the target distance
                result.add(Segment(
                    type = "PACE",
                    durationSec = 0,
                    distanceKm = block.distanceKm,
                    targetPaceSecPerKm = block.paceSecPerKm
                ))
            }
            is ComboBlock.IntervalBlock -> {
                repeat(block.repeats) {
                    result.add(Segment("WORK", block.workSec, targetPaceSecPerKm = block.workPace))
                    result.add(Segment("REST", block.restSec, targetPaceSecPerKm = block.restPace))
                }
            }
            is ComboBlock.CooldownBlock -> {
                result.add(Segment("COOLDOWN", block.durationSec, targetPaceSecPerKm = block.pace))
            }
        }
    }
    return result
}

// ── Summary helpers ─────────────────────────────────────────────────────────

fun ComboScenario.estimateTotalDistanceKm(): Double {
    var dist = 0.0
    for (block in blocks) {
        when (block) {
            is ComboBlock.WarmupBlock -> {
                // estimated distance from pace (or assume ~7:00/km if no pace)
                val pace = block.pace ?: 420
                dist += block.durationSec / pace.toDouble()
            }
            is ComboBlock.PaceBlock -> {
                dist += block.distanceKm ?: 0.0
            }
            is ComboBlock.IntervalBlock -> {
                repeat(block.repeats) {
                    dist += block.workSec / block.workPace.toDouble()
                    val rp = block.restPace ?: 720  // assume ~12:00/km for rest
                    dist += block.restSec / rp.toDouble()
                }
            }
            is ComboBlock.CooldownBlock -> {
                val pace = block.pace ?: 420
                dist += block.durationSec / pace.toDouble()
            }
        }
    }
    return dist
}

fun ComboScenario.estimateTotalTimeSec(): Int {
    var sec = 0
    for (block in blocks) {
        when (block) {
            is ComboBlock.WarmupBlock -> sec += block.durationSec
            is ComboBlock.PaceBlock -> {
                val d = block.distanceKm ?: 0.0
                sec += (d * block.paceSecPerKm).toInt()
            }
            is ComboBlock.IntervalBlock -> {
                sec += block.repeats * (block.workSec + block.restSec)
            }
            is ComboBlock.CooldownBlock -> sec += block.durationSec
        }
    }
    return sec
}

// ── JSON serialization ──────────────────────────────────────────────────────

class ComboBlockSerializer : JsonSerializer<ComboBlock> {
    override fun serialize(src: ComboBlock, typeOfSrc: Type, ctx: JsonSerializationContext): JsonElement {
        val obj = JsonObject()
        when (src) {
            is ComboBlock.WarmupBlock -> {
                obj.addProperty("blockType", "WARMUP")
                obj.addProperty("durationSec", src.durationSec)
                src.pace?.let { obj.addProperty("pace", it) }
            }
            is ComboBlock.PaceBlock -> {
                obj.addProperty("blockType", "PACE")
                src.distanceKm?.let { obj.addProperty("distanceKm", it) }
                obj.addProperty("paceSecPerKm", src.paceSecPerKm)
            }
            is ComboBlock.IntervalBlock -> {
                obj.addProperty("blockType", "INTERVAL")
                obj.addProperty("workSec", src.workSec)
                obj.addProperty("workPace", src.workPace)
                obj.addProperty("restSec", src.restSec)
                src.restPace?.let { obj.addProperty("restPace", it) }
                obj.addProperty("repeats", src.repeats)
            }
            is ComboBlock.CooldownBlock -> {
                obj.addProperty("blockType", "COOLDOWN")
                obj.addProperty("durationSec", src.durationSec)
                src.pace?.let { obj.addProperty("pace", it) }
            }
        }
        return obj
    }
}

class ComboBlockDeserializer : JsonDeserializer<ComboBlock> {
    override fun deserialize(json: JsonElement, typeOfT: Type, ctx: JsonDeserializationContext): ComboBlock {
        val obj = json.asJsonObject
        return when (obj.get("blockType").asString) {
            "WARMUP" -> ComboBlock.WarmupBlock(
                obj.get("durationSec").asInt,
                obj.get("pace")?.asInt
            )
            "PACE" -> ComboBlock.PaceBlock(
                obj.get("distanceKm")?.asDouble,
                obj.get("paceSecPerKm").asInt
            )
            "INTERVAL" -> ComboBlock.IntervalBlock(
                obj.get("workSec").asInt,
                obj.get("workPace").asInt,
                obj.get("restSec").asInt,
                obj.get("restPace")?.asInt,
                obj.get("repeats").asInt
            )
            "COOLDOWN" -> ComboBlock.CooldownBlock(
                obj.get("durationSec").asInt,
                obj.get("pace")?.asInt
            )
            else -> throw IllegalArgumentException("Unknown block type: ${obj.get("blockType")}")
        }
    }
}

fun comboGson(): Gson = GsonBuilder()
    .registerTypeAdapter(ComboBlock::class.java, ComboBlockSerializer())
    .registerTypeAdapter(ComboBlock::class.java, ComboBlockDeserializer())
    .create()
