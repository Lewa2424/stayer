package com.example.stayer.pathnet.diagnostics

import android.util.Log
import com.example.stayer.pathnet.model.GeoPoint

/**
 * Диагностический логгер модуля маршрутной сети.
 * Diagnostic logger for the path network module.
 */
object PathNetLogger {
    private const val tag = "PathNet"

    /**
     * Пишет информационное сообщение в logcat.
     * Writes an informational message to logcat.
     */
    fun info(message: String) {
        try {
            Log.i(tag, message)
        } catch (_: Throwable) {
            // JVM unit-tests may lack android.util.Log.
        }
    }

    /**
     * Пишет отладочное сообщение в logcat.
     * Writes a debug message to logcat.
     */
    fun debug(message: String) {
        try {
            Log.d(tag, message)
        } catch (_: Throwable) {
        }
    }

    /**
     * Пишет предупреждение в logcat.
     * Writes a warning message to logcat.
     */
    fun warn(message: String) {
        try {
            Log.w(tag, message)
        } catch (_: Throwable) {
        }
    }

    /**
     * Пишет ошибку в logcat.
     * Writes an error message to logcat.
     */
    fun error(
        message: String,
        error: Throwable? = null,
    ) {
        try {
            Log.e(tag, message, error)
        } catch (_: Throwable) {
        }
    }

    /**
     * Форматирует точку для короткого вывода в лог.
     * Formats a point for compact log output.
     */
    fun point(point: GeoPoint): String {
        return "(${format(point.lat)}, ${format(point.lon)})"
    }

    /**
     * Форматирует bbox для короткого вывода в лог.
     * Formats a bbox for compact log output.
     */
    fun bounds(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): String {
        return "[${format(minLat)}, ${format(minLon)}]..[${format(maxLat)}, ${format(maxLon)}]"
    }

    /**
     * Форматирует число с умеренной точностью для логов.
     * Formats a number with moderate precision for logs.
     */
    private fun format(value: Double): String {
        return String.format("%.6f", value)
    }
}
