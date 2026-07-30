package com.example.stayer.pathnet.diagnostics

import com.example.stayer.engine.DistanceSource
import com.example.stayer.engine.DistanceTick
import com.example.stayer.pathnet.domain.RailMatcher
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Пишет JSONL-тики rail/GPS одометра рядом с GPX (для offline replay).
 * Writes rail/GPS odometer tick JSONL next to GPX (for offline replay).
 */
class RailTickJsonlLogger {
    private var writer: FileWriter? = null
    private var file: File? = null
    private var seq: Long = 0L

    val isOpen: Boolean
        get() = writer != null

    /**
     * Открывает новый файл тиков в [dir].
     * Opens a new tick file in [dir].
     */
    fun open(dir: File, stamp: String) {
        close()
        try {
            file = File(dir, "stayer_rail_ticks_$stamp.jsonl")
            writer = FileWriter(file, false)
            seq = 0L
        } catch (_: Exception) {
            writer = null
            file = null
        }
    }

    /**
     * Пишет один тик.
     * Writes one tick.
     */
    fun logTick(
        locationTimeMs: Long,
        elapsedRealtimeNanos: Long,
        dtMs: Double,
        rawLat: Double,
        rawLon: Double,
        smoothedLat: Double,
        smoothedLon: Double,
        accuracy: Float,
        locationSpeed: Float,
        rawDelta: Double,
        lockedBefore: Boolean,
        edgeBefore: String?,
        sBefore: Double,
        directionBefore: String,
        tick: DistanceTick,
        matcher: RailMatcher,
    ) {
        val w = writer ?: return
        seq++
        try {
            val status = tick.railStatus?.name ?: ""
            val line = buildString {
                append('{')
                append("\"seq\":").append(seq).append(',')
                append("\"locationTimeMs\":").append(locationTimeMs).append(',')
                append("\"elapsedRealtimeNanos\":").append(elapsedRealtimeNanos).append(',')
                append("\"dtMs\":").append(dtMs).append(',')
                append("\"rawLat\":").append(rawLat).append(',')
                append("\"rawLon\":").append(rawLon).append(',')
                append("\"smoothedLat\":").append(smoothedLat).append(',')
                append("\"smoothedLon\":").append(smoothedLon).append(',')
                append("\"accuracy\":").append(accuracy).append(',')
                append("\"locationSpeed\":").append(locationSpeed).append(',')
                append("\"rawDelta\":").append(rawDelta).append(',')
                append("\"lockedBefore\":").append(lockedBefore).append(',')
                append("\"edgeBefore\":").append(jsonString(edgeBefore)).append(',')
                append("\"sBefore\":").append(sBefore).append(',')
                append("\"directionBefore\":").append(jsonString(directionBefore)).append(',')
                append("\"deltaMeters\":").append(tick.deltaMeters).append(',')
                append("\"source\":").append(jsonString(tick.source.name)).append(',')
                append("\"arrivalStatus\":").append(jsonString(status)).append(',')
                append("\"creditedDelta\":").append(tick.deltaMeters).append(',')
                append("\"debtAfter\":").append(tick.railDebtMeters).append(',')
                append("\"capApplied\":").append(tick.railCapApplied).append(',')
                append("\"pathDeltaBeforeCap\":").append(tick.railPathDeltaBeforeCap).append(',')
                append("\"edgeAfter\":").append(jsonString(matcher.currentEdgeId)).append(',')
                append("\"sAfter\":").append(matcher.currentSMeters).append(',')
                append("\"directionAfter\":").append(
                    jsonString(
                        when {
                            !matcher.isLocked -> "none"
                            !matcher.isDirectionKnown -> "unknown"
                            matcher.isTravelingTowardEnd -> "towardEnd"
                            else -> "towardStart"
                        },
                    ),
                )
                append('}')
                append('\n')
            }
            w.write(line)
            if (seq % 25L == 0L) w.flush()
        } catch (_: Exception) {
            // ignore logging failures
        }
    }

    fun writeSessionHeader(headerJson: String) {
        val w = writer ?: return
        try {
            w.write(headerJson)
            w.write("\n")
            w.flush()
        } catch (_: Exception) {
            // ignore
        }
    }

    fun close() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
            // ignore
        } finally {
            writer = null
            file = null
        }
    }

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    companion object {
        fun stampNow(): String =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }
}
