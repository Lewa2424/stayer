package com.example.stayer.debug

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PacerTestTtsHelper(context: Context) {
    private var tts: TextToSpeech? = null
    private var logWriter: FileWriter? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru")
            } else {
                Log.e("PacerTestTts", "TTS Init failed")
            }
        }

        try {
            val dir = context.getExternalFilesDir(null)
            if (dir != null) {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val logFile = File(dir, "stayer_test_tts_log_$timestamp.txt")
                logWriter = FileWriter(logFile, true)
                val initialLog = "=== СТАРТ ТЕСТОВОГО ЛОГА ОЗВУЧКИ: $timestamp ===\n"
                logWriter?.append(initialLog)
                logWriter?.flush()
            }
        } catch (e: Exception) {
            Log.e("PacerTestTts", "Failed to init TTS test log file", e)
        }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "PacerTest")
        try {
            val timeTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logWriter?.append("[$timeTime] $text\n")
            logWriter?.flush()
        } catch (e: Exception) {
            Log.e("PacerTestTts", "Failed to write TTS log", e)
        }
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        try {
            logWriter?.append("=== ТЕСТ ЗАВЕРШЕН ===\n")
            logWriter?.flush()
            logWriter?.close()
        } catch (e: Exception) {
            Log.e("PacerTestTts", "Failed to close TTS log", e)
        }
    }
}
