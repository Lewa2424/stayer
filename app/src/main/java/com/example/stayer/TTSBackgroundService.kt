package com.example.stayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.PowerManager
import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TTSBackgroundService : Service() {
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val CHANNEL_ID = "TTSBackgroundChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        writeExitLog("APP_EXIT_TTS: onCreate begin")
        createNotificationChannel()
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TTSBackgroundService::WakeLock")
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Настраиваем AudioAttributes для воспроизведения поверх музыки
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = Locale("ru")
                if (textToSpeech.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                    textToSpeech.language = locale
                    setupFemaleVoice()
                }
            }
        }
        
        // Настраиваем AudioAttributes для TTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech.setAudioAttributes(audioAttributes)
        }
        
        startForeground(NOTIFICATION_ID, createNotification())
        writeExitLog("APP_EXIT_TTS: onCreate end")
    }
    
    // Настройка женского голоса
    private fun setupFemaleVoice() {
        try {
            // Получаем список доступных голосов
            val voices = textToSpeech.voices
            var femaleVoice: android.speech.tts.Voice? = null
            
            // Ищем женский голос для русского языка
            for (voice in voices) {
                val voiceLocale = voice.locale
                if (voiceLocale.language == "ru") {
                    val voiceName = voice.name.lowercase()
                    if (voiceName.contains("female") || 
                        voiceName.contains("женск") || 
                        voiceName.contains("женский") ||
                        voiceName.contains("anna") ||
                        voiceName.contains("elena") ||
                        voiceName.contains("milena") ||
                        voiceName.contains("katya")) {
                        femaleVoice = voice
                        break
                    }
                    if (femaleVoice == null) {
                        femaleVoice = voice
                    }
                }
            }
            
            if (femaleVoice != null) {
                val result = textToSpeech.setVoice(femaleVoice)
                if (result == TextToSpeech.SUCCESS) {
                    android.util.Log.d("TTS", "Установлен голос: ${femaleVoice.name}")
                }
            }
            
            // Настраиваем параметры для более человечного звучания
            textToSpeech.setPitch(1.1f) // Немного выше для женского голоса
            textToSpeech.setSpeechRate(0.95f) // Немного медленнее для естественности
            
        } catch (e: Exception) {
            android.util.Log.e("TTS", "Ошибка настройки голоса: ${e.message}")
            textToSpeech.setPitch(1.1f)
            textToSpeech.setSpeechRate(0.95f)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "SPEAK" -> {
                val text = intent.getStringExtra("text") ?: return START_NOT_STICKY
                
                // Запрашиваем AudioFocus перед каждым воспроизведением
                requestAudioFocus()
                
                // Активируем WakeLock для работы при выключенном экране
                wakeLock.acquire(10*60*1000L) // 10 минут максимум
                
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
                
                textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        // Ничего не делаем при старте
                    }

                    override fun onDone(utteranceId: String?) {
                        // Освобождаем AudioFocus после завершения
                        abandonAudioFocus()
                        if (wakeLock.isHeld) {
                            wakeLock.release()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        // Освобождаем AudioFocus при ошибке
                        abandonAudioFocus()
                        if (wakeLock.isHeld) {
                            wakeLock.release()
                        }
                    }
                })
            }
            "STOP" -> {
                writeExitLog("APP_EXIT_TTS: STOP action begin wakeLockHeld=${wakeLock.isHeld}")
                textToSpeech.stop()
                writeExitLog("APP_EXIT_TTS: textToSpeech.stop done")
                abandonAudioFocus()
                writeExitLog("APP_EXIT_TTS: abandonAudioFocus done")
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    writeExitLog("APP_EXIT_TTS: wakeLock released from STOP")
                }
                writeExitLog("APP_EXIT_TTS: stopSelf called")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    
    @android.annotation.SuppressLint("NewApi")
    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                // Хотим, чтобы стороннее медиа реально уступало, а не просто "подглушалось если сможет".
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                // Музыка приглушена, можно воспроизводить
                            }
                            AudioManager.AUDIOFOCUS_LOSS -> {
                                // Потеряли фокус, останавливаем воспроизведение
                                textToSpeech.stop()
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                // Получили фокус обратно
                            }
                        }
                    }
                    .build()
            }
            
            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                android.util.Log.w("TTS", "Не удалось получить AudioFocus")
            }
        } else {
            // Для старых версий Android
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                android.util.Log.w("TTS", "Не удалось получить AudioFocus")
            }
        }
    }
    
    @android.annotation.SuppressLint("NewApi")
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TTS Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Тренировка")
            .setContentText("Воспроизведение голосовых подсказок")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        writeExitLog("APP_EXIT_TTS: onDestroy begin wakeLockHeld=${wakeLock.isHeld}")
        super.onDestroy()
        try {
            writeExitLog("APP_EXIT_TTS: textToSpeech.shutdown begin")
            textToSpeech.shutdown()
            writeExitLog("APP_EXIT_TTS: textToSpeech.shutdown end")
        } catch (e: Exception) {
            writeExitLog("APP_EXIT_TTS_ERROR: textToSpeech.shutdown failed: ${e.javaClass.simpleName}: ${e.message}")
            android.util.Log.e("TTSBackgroundService", "textToSpeech.shutdown failed", e)
        }
        if (wakeLock.isHeld) {
            wakeLock.release()
            writeExitLog("APP_EXIT_TTS: wakeLock released in onDestroy")
        }
        writeExitLog("APP_EXIT_TTS: onDestroy end")
    }

    private fun writeExitLog(message: String) {
        try {
            val logFile = File(getExternalFilesDir(null), "stayer_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            FileWriter(logFile, true).use { writer ->
                writer.append("[$timestamp] $message\n")
            }
        } catch (_: Exception) {
        }
    }
} 
