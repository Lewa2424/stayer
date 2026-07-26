package com.example.stayer

import android.app.Application
import android.util.Log
import com.example.stayer.pathnet.data.OsmdroidInitializer
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        OsmdroidInitializer.init(this)
        installCrashLogger()
    }

    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrashLog(thread, throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        try {
            val logDir = getExternalFilesDir(null) ?: filesDir
            val logFile = File(logDir, "crash_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val stackWriter = StringWriter()
            throwable.printStackTrace(PrintWriter(stackWriter))
            val logEntry = buildString {
                append("[$timestamp] UNCAUGHT_EXCEPTION\n")
                append("thread=")
                append(thread.name)
                append('\n')
                append("type=")
                append(throwable::class.java.name)
                append('\n')
                append("message=")
                append(throwable.message ?: "null")
                append('\n')
                append(stackWriter.toString())
                append("\n\n")
            }
            FileWriter(logFile, true).use { writer ->
                writer.append(logEntry)
            }
        } catch (e: Exception) {
            Log.e("MyApp", "Failed to write crash log", e)
        }
    }
}
