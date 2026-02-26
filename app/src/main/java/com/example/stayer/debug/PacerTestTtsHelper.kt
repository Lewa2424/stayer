package com.example.stayer.debug

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class PacerTestTtsHelper(context: Context) {
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru")
            } else {
                Log.e("PacerTestTts", "TTS Init failed")
            }
        }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "PacerTest")
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
    }
}
