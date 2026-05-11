package com.example.stayer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.gson.Gson
import com.example.stayer.engine.CadenceFallbackEngine
import com.example.stayer.engine.CurrentPaceEstimator
import com.example.stayer.history.WorkoutHistoryRepository
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SpeechPriority { PACER, AUXILIARY }

class WorkoutForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "WorkoutTrackingChannel"
        private const val NOTIFICATION_ID = 101

        private const val PREFS_WORKOUT = "WorkoutRuntime"
        private const val KEY_DISTANCE_KM = "CURRENT_DISTANCE_KM"
        private const val KEY_START_TIME_MS = "START_TIME_MS"
        private const val KEY_TOTAL_PAUSED_MS = "TOTAL_PAUSED_MS"
        private const val KEY_PAUSED_AT_MS = "PAUSED_AT_MS"
        private const val KEY_IS_RUNNING = "IS_RUNNING"
        private const val KEY_IS_PAUSED = "IS_PAUSED"
        private const val KEY_LAST_PACE_CHECK_DISTANCE = "LAST_PACE_CHECK_DISTANCE"
        private const val KEY_GOAL_REACHED = "GOAL_REACHED"

        // Goal Prefs Keys (matching GoalActivity)
        private const val WORKOUT_MODE = "WORKOUT_MODE" // 0=normal, 1=interval, 2=combo

        const val ACTION_START_OR_RESUME = "com.example.stayer.action.START_OR_RESUME"
        const val ACTION_PAUSE = "com.example.stayer.action.PAUSE"
        const val ACTION_STOP_AND_RESET = "com.example.stayer.action.STOP_AND_RESET"
        const val ACTION_SAVE_ACK = "com.example.stayer.action.SAVE_ACK"

        const val ACTION_BROADCAST_UPDATE = "com.example.stayer.action.WORKOUT_UPDATE"
        const val EXTRA_DISTANCE_KM = "extra_distance_km"
        const val EXTRA_ELAPSED_MS = "extra_elapsed_ms"
        const val EXTRA_IS_RUNNING = "extra_is_running"
        const val EXTRA_IS_PAUSED = "extra_is_paused"
        const val EXTRA_CURRENT_PACE_SEC_PER_KM = "extra_current_pace_sec_per_km"

        const val ACTION_BROADCAST_FINAL_SNAPSHOT = "com.example.stayer.action.FINAL_SNAPSHOT"
        const val EXTRA_SNAPSHOT_JSON = "extra_snapshot_json"
        
        private const val PREFS_PENDING_SNAPSHOT = "PendingSnapshot"
        private const val KEY_PENDING_SNAPSHOT_JSON = "PENDING_SNAPSHOT_JSON"
        private const val KEY_PENDING_SNAPSHOT_TIMESTAMP = "PENDING_SNAPSHOT_TIMESTAMP"

        fun startOrResume(context: Context) {
            val intent = Intent(context, WorkoutForegroundService::class.java).apply {
                action = ACTION_START_OR_RESUME
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, WorkoutForegroundService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun stopAndReset(context: Context) {
            val intent = Intent(context, WorkoutForegroundService::class.java).apply {
                action = ACTION_STOP_AND_RESET
            }
            context.startService(intent)
        }
        
        fun sendSaveAck(context: Context) {
            val intent = Intent(context, WorkoutForegroundService::class.java).apply {
                action = ACTION_SAVE_ACK
            }
            context.startService(intent)
        }
    }

    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var locationCallback: LocationCallback? = null
    private var lastAcceptedRawLocation: Location? = null
    private var lastSmoothedLocation: Location? = null
    // РЈРјРµРЅСЊС€РµРЅРѕ РѕРєРЅРѕ СЃРіР»Р°Р¶РёРІР°РЅРёСЏ СЃ 7 РґРѕ 3 РґР»СЏ СЃРЅРёР¶РµРЅРёСЏ СЌС„С„РµРєС‚Р° "СЃСЂРµР·Р°РЅРёСЏ СѓРіР»РѕРІ" РЅР° РїРѕРІРѕСЂРѕС‚Р°С…
    private var smoother = LocationSmoother(windowSize = 3)

    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private var tickDebugLogged = false

    private var totalDistanceKm: Float = 0f
    private var lastPaceCheckDistance: Float = 0f
    private var goalReached: Boolean = false

    private var isRunning: Boolean = false
    private var isPaused: Boolean = false
    private var startTimeMs: Long = 0L
    private var pausedAtMs: Long = 0L
    private var totalPausedMs: Long = 0L

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var ttsWakeLock: PowerManager.WakeLock

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private lateinit var textToSpeech: TextToSpeech
    private var pendingTtsUtterances = 0

    // === Interval execution state ===
    private var intervalScenario: IntervalScenario? = null
    private var segmentIndex = 0
    private var segmentStartElapsedSec = 0

    // Ignoring first N seconds of WORK for pace estimation (acceleration phase)
    private val workIgnoreSec = 3
    private var stableStarted = false
    private var stableStartElapsedSec = 0
    private var stableStartDistanceM = 0.0

    // TTS announcement flags (per-segment, track by segment index)
    private var lastAnnouncedSegmentIndex = -1
    private var warned10sIndex = -1
    private var midHintIndex = -1
    private var rest40Index = -1
    private var endReportIndex = -1
    private var lastIntervalHintInSegSec = -1
    private var intervalHintAlternate = false

    // Rolling speed window for pace estimation
    private val speedWindowSec = 8
    private val stableDeltasM: ArrayDeque<Double> = ArrayDeque()
    private var lastTickDistanceM = 0.0

    // Smart Pace Corrector (30-sec rolling window)
    private val paceCorrector = com.example.stayer.engine.PaceCorrectionManager()
    private val currentPaceEstimator = CurrentPaceEstimator()

    // Phase accumulators for history stats (distance in meters, time in seconds)
    private var accumWorkDistM = 0.0
    private var accumWorkTimeSec = 0
    private var accumRestDistM = 0.0
    private var accumRestTimeSec = 0
    private var accumWarmupDistM = 0.0
    private var accumWarmupTimeSec = 0
    private var accumCooldownDistM = 0.0
    private var accumCooldownTimeSec = 0
    private var segmentStartDistanceM = 0.0
    private var activeWorkoutModeInt = 0
    private val completedHistorySegments = mutableListOf<WorkoutHistorySegment>()
    private val completedNormalCheckpoints = mutableListOf<WorkoutHistoryCheckpoint>()
    private var nextHistorySegmentNumber = 1

    // Normal mode Pacer state
    private var lastPacerCheckpointDistanceM = 0.0
    private var lastPacerCheckpointElapsedSec = 0
    private var pacerPraiseAlternate = false
    private var comboPaceCheckpointDistanceM = 0.0
    private var comboPaceCheckpointElapsedSec = 0
    private var comboPacePraiseAlternate = false

    // Emergency monitor state (250m check)
    private var lastEmergencyCheckDistKm = 0.0
    private var lastCheckpointProgress = com.example.stayer.debug.GlobalProgress.ON_TRACK
    private var emergencyCooldownUntilDistKm = 0.0

    // GPX Logging state
    private var gpxWriter: FileWriter? = null
    private var gpxFile: File? = null

    private var lastPacerSpeechTimeMs: Long = 0L

    // Fallback Engine (Intelligent Steps Calibration)
    private lateinit var fallbackEngine: CadenceFallbackEngine
    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private var stepsSinceLastTick = 0
    private var lastTotalSteps = -1

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val totalSteps = event.values[0].toInt()
            if (lastTotalSteps == -1) {
                lastTotalSteps = totalSteps
                return
            }
            if (isRunning && !isPaused) {
                stepsSinceLastTick += (totalSteps - lastTotalSteps)
            }
            lastTotalSteps = totalSteps
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate() {
        super.onCreate()

        fallbackEngine = CadenceFallbackEngine(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WorkoutForegroundService::WakeLock")
        ttsWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WorkoutForegroundService::TTSWake")

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        restoreState()
        
        // РљР РРўРР§Р•РЎРљР Р’РђР–РќРћ: РїСЂРѕРІРµСЂСЏРµРј pending snapshot РїСЂРё СЃС‚Р°СЂС‚Рµ
        checkAndRestorePendingSnapshot()

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = Locale("ru")
                if (textToSpeech.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                    textToSpeech.language = locale
                    setupFemaleVoice()
                }
            }
        }
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                handleTtsUtteranceFinished()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handleTtsUtteranceFinished()
            }
        })

        // Р’Р°Р¶РЅРѕ: РґР»СЏ TTS РїРѕРІРµСЂС… РјСѓР·С‹РєРё
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_OR_RESUME -> handleStartOrResume()
            ACTION_PAUSE -> handlePause()
            ACTION_STOP_AND_RESET -> handleStopAndReset()
            ACTION_SAVE_ACK -> handleSaveAck()
        }
        return START_STICKY
    }

    private fun handleStartOrResume() {
        // РЎС‚Р°СЂС‚ foreground СЃСЂР°Р·Сѓ, С‡С‚РѕР±С‹ Android РЅРµ СѓР±РёР» СЃРµСЂРІРёСЃ Р·Р° С‚Р°Р№РјР°СѓС‚
        startForeground(NOTIFICATION_ID, buildNotification())
        writeLog("SERVICE_START: before start branch isRunning=$isRunning, isPaused=$isPaused, startTimeMs=$startTimeMs, elapsed=${currentElapsedMs()}")

        if (!wakeLock.isHeld) {
            // РќР° РІСЂРµРјСЏ С‚СЂРµРЅРёСЂРѕРІРєРё РґРµСЂР¶РёРј CPU Р¶РёРІС‹Рј (РёРЅР°С‡Рµ Doze РјРѕР¶РµС‚ Р¶С‘СЃС‚РєРѕ СЂРµР·Р°С‚СЊ Р°РїРґРµР№С‚С‹)
            wakeLock.acquire(10 * 60 * 60 * 1000L) // 10 С‡Р°СЃРѕРІ РјР°РєСЃРёРјСѓРј (manual stop/reset)
        }

        val now = System.currentTimeMillis()
        if (!isRunning) {
            isRunning = true
            isPaused = false
            startTimeMs = if (startTimeMs == 0L) now else startTimeMs
            pausedAtMs = 0L
            goalReached = false
            // РџСЂРё РЅРѕРІРѕРј СЃС‚Р°СЂС‚Рµ С‚РѕС‡РєСѓ РёРЅРёС†РёР°Р»РёР·РёСЂСѓРµРј СЃ РЅСѓР»СЏ, С‡С‚РѕР±С‹ РЅРµ Р±С‹Р»Рѕ СЃРєР°С‡РєР°
            resetTrackState()
            paceCorrector.reset()
            
            // РќР°С‡РёРЅР°РµРј РЅРѕРІС‹Р№ GPX Р»РѕРі
            initGpxLog()

            // Load interval scenario on fresh start
            loadWorkoutModeAndScenario()

            // Apply location type: stadium=smoothing(3), park=no smoothing(1)
            val goalPrefs = getSharedPreferences("Goals", MODE_PRIVATE)
            val locationType = goalPrefs.getInt(GoalActivity.LOCATION_TYPE, 0)
            smoother = LocationSmoother(windowSize = if (locationType == 1) 1 else 3)
            writeLog("SMOOTHER: windowSize=${if (locationType == 1) 1 else 3} (locationType=$locationType)")

        } else if (isPaused) {
            isPaused = false
            if (pausedAtMs > 0L) {
                totalPausedMs += (now - pausedAtMs)
            }
            pausedAtMs = 0L

            // После паузы сбрасываем lastLocation, иначе будет скачок

            resetTrackState()

            paceCorrector.reset()

        }


        persistState()
        writeLog("SERVICE_START: after init mode=$activeWorkoutModeInt, startTimeMs=$startTimeMs, segmentIndex=$segmentIndex, lastAnnouncedSegmentIndex=$lastAnnouncedSegmentIndex")
        startLocationUpdates()
        
        stepSensor?.let {
            sensorManager.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        
        startTicking()
        broadcastUpdate()
        updateNotification()
    }

    private fun handlePause() {
        if (!isRunning || isPaused) return

        isPaused = true
        pausedAtMs = System.currentTimeMillis()
        resetTrackState()
        paceCorrector.reset()

        stopLocationUpdates()
        sensorManager.unregisterListener(stepListener)
        stopTicking()
        persistState()
        broadcastUpdate()
        updateNotification()
    }

    private fun handleStopAndReset() {
        writeLog("SERVICE: handleStopAndReset called - building final snapshot BEFORE reset")
        
        // 1. РЎРќРђР§РђР›Рђ С„РѕСЂРјРёСЂСѓРµРј С„РёРЅР°Р»СЊРЅС‹Р№ snapshot Р”Рћ Р»СЋР±РѕРіРѕ СЃР±СЂРѕСЃР°
        val finalSnapshot = buildFinalSnapshot()
        
        // 2. Р›РѕРіРёСЂСѓРµРј Рё РїСЂРѕРІРµСЂСЏРµРј РІР°Р»РёРґРЅРѕСЃС‚СЊ
        if (!finalSnapshot.isValid()) {
            writeLog("WORKOUT_SAVE_ABORTED_INVALID_SNAPSHOT: distance=${finalSnapshot.distanceKm}, elapsed=${finalSnapshot.elapsedMs}")
            // РќРµРІР°Р»РёРґРЅР°СЏ С‚СЂРµРЅРёСЂРѕРІРєР° - СЃСЂР°Р·Сѓ РґРµР»Р°РµРј РїРѕР»РЅС‹Р№ reset
            performFullReset()
            return
        }
        
        writeLog("WORKOUT_SNAPSHOT_CREATED: distance=${finalSnapshot.distanceKm}km, elapsed=${finalSnapshot.elapsedMs}ms")
        
        // 3. РљР РРўРР§Р•РЎРљР Р’РђР–РќРћ: РЎРѕС…СЂР°РЅСЏРµРј snapshot Р’ РќРђР”РЃР–РќРћР• РҐР РђРќРР›РР©Р• РґРѕ reset'Р°
        savePendingSnapshot(finalSnapshot)
        
        // 4. Service РЎРђРњ СЃРѕС…СЂР°РЅСЏРµС‚ РёСЃС‚РѕСЂРёСЋ (РЅРµ Р·Р°РІРёСЃРёС‚ РѕС‚ Activity)
        val saved = saveWorkoutHistoryFromSnapshot(finalSnapshot)
        
        if (saved) {
            writeLog("SERVICE_HISTORY_SAVED: workout saved by Service directly")
            
            // 5. РћС‚РїСЂР°РІР»СЏРµРј snapshot РІ Activity РґР»СЏ РѕР±РЅРѕРІР»РµРЅРёСЏ UI (РѕРїС†РёРѕРЅР°Р»СЊРЅРѕ)
            // Activity РјРѕР¶РµС‚ РїРѕРєР°Р·Р°С‚СЊ Toast РёР»Рё РѕР±РЅРѕРІРёС‚СЊ СЃРїРёСЃРѕРє, РЅРѕ РќР• РѕР±СЏР·Р°РЅР° СЃРѕС…СЂР°РЅСЏС‚СЊ
            broadcastFinalSnapshot(finalSnapshot)
            
            // 6. Р–РґС‘Рј ACK РѕС‚ Activity СЃ С‚Р°Р№РјР°СѓС‚РѕРј
            // Р•СЃР»Рё ACK РЅРµ РїСЂРёРґС‘С‚ Р·Р° 3 СЃРµРєСѓРЅРґС‹ - РїСЂРѕРґРѕР»Р¶Р°РµРј reset СЃР°РјРѕСЃС‚РѕСЏС‚РµР»СЊРЅРѕ
            scheduleResetWithTimeout()
        } else {
            writeLog("ERROR: Service failed to save history, keeping snapshot in pending storage")
            // Snapshot РѕСЃС‚Р°С‘С‚СЃСЏ РІ pending storage, РїРѕРїСЂРѕР±СѓРµРј РІРѕСЃСЃС‚Р°РЅРѕРІРёС‚СЊ РїСЂРё СЃР»РµРґСѓСЋС‰РµРј Р·Р°РїСѓСЃРєРµ
            performFullReset()
        }
    }
    
    private fun handleSaveAck() {
        writeLog("SERVICE: Received SAVE_ACK from Activity")
        cancelResetTimeout()
        performFullReset()
    }
    
    private var resetTimeoutRunnable: Runnable? = null
    
    private fun scheduleResetWithTimeout() {
        resetTimeoutRunnable = Runnable {
            writeLog("SERVICE: Reset timeout reached, proceeding with reset")
            performFullReset()
        }
        tickHandler.postDelayed(resetTimeoutRunnable!!, 3000)
    }
    
    private fun cancelResetTimeout() {
        resetTimeoutRunnable?.let {
            tickHandler.removeCallbacks(it)
            resetTimeoutRunnable = null
        }
    }
    
    private fun performFullReset() {
        writeLog("SERVICE: Performing full reset")
        
        stopLocationUpdates()
        stopTicking()

        isRunning = false
        isPaused = false
        startTimeMs = 0L
        pausedAtMs = 0L
        totalPausedMs = 0L
        totalDistanceKm = 0f
        lastPaceCheckDistance = 0f
        goalReached = false
        tickDebugLogged = false
        
        // Reset Pacer state
        lastPacerCheckpointDistanceM = 0.0
        lastPacerCheckpointElapsedSec = 0
        pacerPraiseAlternate = false
        activeWorkoutModeInt = 0
        completedHistorySegments.clear()
        completedNormalCheckpoints.clear()
        nextHistorySegmentNumber = 1

        resetTrackState()
        closeGpxLog()

        sensorManager.unregisterListener(stepListener)
        lastTotalSteps = -1
        stepsSinceLastTick = 0

        persistState()
        broadcastUpdate()
        
        // РћС‡РёС‰Р°РµРј pending snapshot РўРћР›Р¬РљРћ РїРѕСЃР»Рµ СѓСЃРїРµС€РЅРѕРіРѕ reset
        clearPendingSnapshot()
        
        writeLog("WORKOUT_RESET_DONE")

        if (wakeLock.isHeld) wakeLock.release()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun resetTrackState() {
        lastAcceptedRawLocation = null
        lastSmoothedLocation = null
        smoother.reset()
        currentPaceEstimator.reset()
        // Reset emergency monitor
        lastEmergencyCheckDistKm = 0.0
        lastCheckpointProgress = com.example.stayer.debug.GlobalProgress.ON_TRACK
        emergencyCooldownUntilDistKm = 0.0
    }

    private fun startTicking() {
        if (tickRunnable != null) return
        tickDebugLogged = false
        tickRunnable = object : Runnable {
            override fun run() {
                if (isRunning && !isPaused) {
                    if (!tickDebugLogged) {
                        writeLog("TICK: first active tick mode=$activeWorkoutModeInt, distance=$totalDistanceKm, elapsed=${currentElapsedMs()}, segmentIndex=$segmentIndex")
                        tickDebugLogged = true
                    }
                    // 1. Process Cadence Fallback (Intelligent Steps)
                    val stepsToProcess = stepsSinceLastTick
                    stepsSinceLastTick = 0
                    val fallbackDistM = fallbackEngine.processTick(stepsToProcess)
                    if (fallbackDistM > 0.0) {
                        totalDistanceKm += (fallbackDistM.toFloat() / 1000f)
                    }

                    // 2. РўР°Р№РјРµСЂ Рё РЅРѕС‚РёС„РёРєР°С†РёСЏ РґРѕР»Р¶РЅС‹ РѕР±РЅРѕРІР»СЏС‚СЊСЃСЏ РЅРµР·Р°РІРёСЃРёРјРѕ РѕС‚ С‡Р°СЃС‚РѕС‚С‹ GPS-С‚РѕС‡РµРє
                    broadcastUpdate()
                    updateNotification()

                    // 3. Pace Corrector: fallback is the source of truth only outside STABLE GPS mode
                    if (fallbackEngine.currentState != CadenceFallbackEngine.State.STABLE) {
                        paceCorrector.feedSample(fallbackDistM, 1.0)
                        currentPaceEstimator.feed(fallbackDistM, 1.0)
                    }

                    // 4. Normal-mode corrector suggestion
                    maybePaceCorrectorNormal(totalDistanceKm)

                    // 5. Interval logic
                    handleIntervalTick()
                    tickHandler.postDelayed(this, 1000L)
                } else if (isRunning) {
                    // Paused вЂ” still tick for timer display but skip interval logic
                    broadcastUpdate()
                    updateNotification()
                    tickHandler.postDelayed(this, 1000L)
                }
            }
        }
        tickHandler.post(tickRunnable!!)
    }

    private fun stopTicking() {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        tickRunnable = null
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val elapsedMs = currentElapsedMs()
        val seconds = (elapsedMs / 1000) % 60
        val minutes = (elapsedMs / (1000 * 60)) % 60
        val hours = (elapsedMs / (1000 * 60 * 60))
        val timeText = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        val distanceText = String.format(Locale.getDefault(), "%.2f км", totalDistanceKm)
        val stateText = if (!isRunning) "Остановлено" else if (isPaused) "Пауза" else "Идёт"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Тренировка: $stateText")
            .setContentText("$timeText • $distanceText")
            .setOngoing(isRunning && !isPaused)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Workout tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun restoreState() {
        val prefs = getSharedPreferences(PREFS_WORKOUT, MODE_PRIVATE)
        totalDistanceKm = prefs.getFloat(KEY_DISTANCE_KM, 0f)
        startTimeMs = prefs.getLong(KEY_START_TIME_MS, 0L)
        totalPausedMs = prefs.getLong(KEY_TOTAL_PAUSED_MS, 0L)
        pausedAtMs = prefs.getLong(KEY_PAUSED_AT_MS, 0L)
        isRunning = prefs.getBoolean(KEY_IS_RUNNING, false)
        isPaused = prefs.getBoolean(KEY_IS_PAUSED, false)
        lastPaceCheckDistance = prefs.getFloat(KEY_LAST_PACE_CHECK_DISTANCE, 0f)
        goalReached = prefs.getBoolean(KEY_GOAL_REACHED, false)
    }

    private fun persistState() {
        val prefs = getSharedPreferences(PREFS_WORKOUT, MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_DISTANCE_KM, totalDistanceKm)
            .putLong(KEY_START_TIME_MS, startTimeMs)
            .putLong(KEY_TOTAL_PAUSED_MS, totalPausedMs)
            .putLong(KEY_PAUSED_AT_MS, pausedAtMs)
            .putBoolean(KEY_IS_RUNNING, isRunning)
            .putBoolean(KEY_IS_PAUSED, isPaused)
            .putFloat(KEY_LAST_PACE_CHECK_DISTANCE, lastPaceCheckDistance)
            .putBoolean(KEY_GOAL_REACHED, goalReached)
            .apply()
    }

    private fun broadcastUpdate() {
        val intent = Intent(ACTION_BROADCAST_UPDATE).apply {
            `package` = packageName
            putExtra(EXTRA_DISTANCE_KM, totalDistanceKm)
            putExtra(EXTRA_ELAPSED_MS, currentElapsedMs())
            putExtra(EXTRA_IS_RUNNING, isRunning)
            putExtra(EXTRA_IS_PAUSED, isPaused)
            putExtra(EXTRA_CURRENT_PACE_SEC_PER_KM, currentPaceEstimator.currentPaceSecPerKm() ?: -1)

            // Interval extras (always sent; UI decides what to show)
            val scenario = intervalScenario
            if (scenario != null && segmentIndex in scenario.segments.indices) {
                val seg = scenario.segments[segmentIndex]
                val elapsedSec = (currentElapsedMs() / 1000).toInt()
                val inSegSec = elapsedSec - segmentStartElapsedSec
                val remainingSec = (seg.durationSec - inSegSec).coerceAtLeast(0)
                val workCount = scenario.segments.count { it.type == "WORK" }

                putExtra("interval_active", true)
                putExtra("interval_type", seg.type)
                putExtra("interval_remaining_sec", remainingSec)
                putExtra("interval_index", segmentIndex + 1)
                putExtra("interval_total", scenario.segments.size)
                putExtra("interval_work_count", workCount)
                seg.targetPaceSecPerKm?.let { putExtra("interval_target_pace_sec_per_km", it) }
            } else {
                putExtra("interval_active", false)
            }

        }
        sendBroadcast(intent)
    }

    private fun currentElapsedMs(): Long {
        if (!isRunning || startTimeMs == 0L) return 0L
        val now = System.currentTimeMillis()
        val effectivePausedMs = totalPausedMs + if (isPaused && pausedAtMs > 0L) (now - pausedAtMs) else 0L
        return (now - startTimeMs - effectivePausedMs).coerceAtLeast(0L)
    }

    private fun loadActiveGoal(): ActiveWorkoutGoal {
        val prefs = getSharedPreferences("Goals", MODE_PRIVATE)
        return WorkoutGoalStore.load(prefs)
    }

    private fun loadNormalGoal(): ActiveWorkoutGoal? {
        val goal = loadActiveGoal()
        return goal.takeIf { it.workoutMode == 0 }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            // РќРµС‚ РїСЂР°РІ вЂ” РЅРµ РїР°РґР°РµРј, РїСЂРѕСЃС‚Рѕ РѕСЃС‚Р°С‘РјСЃСЏ РІ foreground Р±РµР· С‚СЂРµРєРёРЅРіР°
            return
        }

        if (locationCallback != null) return

        // РњР°РєСЃРёРјСѓРј РєР°С‡РµСЃС‚РІР° СЃРѕ СЃС‚РѕСЂРѕРЅС‹ Fused:
        // - С‡Р°СЃС‚С‹Рµ С‚РѕС‡РєРё
        // - Р±РµР· РїР°РєРµС‚РёСЂРѕРІР°РЅРёСЏ (maxDelay=0)
        // - minDistance=0 (С„РёР»СЊС‚СЂСѓРµРј РЅРёР¶Рµ СЃР°РјРё)
        // - waitForAccurateLocation=true (РїСЂРѕСЃРёРј РґРѕР¶РґР°С‚СЊСЃСЏ С‚РѕС‡РЅРѕСЃС‚Рё)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(0L)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(true)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (!isRunning || isPaused) return

                for (location in locationResult.locations) {
                    handleLocation(location)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun handleLocation(location: Location) {
        val prev = lastAcceptedRawLocation
        if (prev == null) {
            lastAcceptedRawLocation = location
            logGpxPoint(location, null)
            val smoothed = smoother.addAndGetSmoothed(location)
            lastSmoothedLocation = smoothed
            paceCorrector.feedGpsPoint(
                latDeg = smoothed.latitude,
                lonDeg = smoothed.longitude,
                elapsedSec = currentElapsedMs() / 1000.0
            )
            return
        }

        val rejectReason = acceptPointReason(prev, location)
        logGpxPoint(location, rejectReason)

        if (rejectReason != null) {
            fallbackEngine.processGpsRejected(rejectReason)
            // Р•СЃР»Рё С‚РѕС‡РєРё РЅРµ Р±СѓРґРµС‚ 7 СЃРµРєСѓРЅРґ, РґРІРёР¶РѕРє СЃР°Рј РјСЏРіРєРѕ СѓР№РґРµС‚ РІ BLIND С‡РµСЂРµР· ticksSinceLastGps.
            return
        }

        lastAcceptedRawLocation = location
        val smoothed = smoother.addAndGetSmoothed(location)
        paceCorrector.feedGpsPoint(
            latDeg = smoothed.latitude,
            lonDeg = smoothed.longitude,
            elapsedSec = currentElapsedMs() / 1000.0
        )
        val prevSmoothed = lastSmoothedLocation
        if (prevSmoothed != null) {
            val rawDeltaM = prevSmoothed.distanceTo(smoothed).toDouble()
            if (rawDeltaM > 0.0) {
                val acceptedDistM = fallbackEngine.processGpsAccepted(rawDeltaM)
                val deltaTimeSec = ((location.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000_000.0)
                if (acceptedDistM > 0.0) {
                    totalDistanceKm += (acceptedDistM.toFloat() / 1000f)
                    if (fallbackEngine.currentState == CadenceFallbackEngine.State.STABLE && deltaTimeSec > 0.0) {
                        paceCorrector.feedSample(acceptedDistM, deltaTimeSec)
                        currentPaceEstimator.feed(acceptedDistM, deltaTimeSec)
                    }
                }
            }
        }
        lastSmoothedLocation = smoothed

        maybeAutoPauseOnTarget(totalDistanceKm)
        maybeNotifyPace(totalDistanceKm)
        maybeEmergencyAlert(totalDistanceKm)

        persistState()
        broadcastUpdate()
        updateNotification()
    }

    /**
     * РђРІС‚Рѕ-РїР°СѓР·Р° РїРѕ РґРѕСЃС‚РёР¶РµРЅРёСЋ С†РµР»Рё РґРёСЃС‚Р°РЅС†РёРё.
     * Р’РђР–РќРћ: СЃРµР№С‡Р°СЃ СЃС‡РёС‚Р°РµРј РїРѕ GPS-РґРёСЃС‚Р°РЅС†РёРё СЃРµСЂРІРёСЃР° (РёСЃС‚РѕС‡РЅРёРє РёСЃС‚РёРЅС‹). РЁР°РіРё Activity СЃСЋРґР° РЅРµ РІС…РѕРґСЏС‚.
     */
    private fun maybeAutoPauseOnTarget(currentDistanceKm: Float) {
        if (!isRunning || isPaused || goalReached) return

        val goal = loadNormalGoal() ?: return
        val targetDistanceKm = goal.targetDistanceKm ?: 0f
        if (targetDistanceKm <= 0f) return

        // РќРµР±РѕР»СЊС€Р°СЏ РґРµР»СЊС‚Р°, С‡С‚РѕР±С‹ РЅРµ Р·Р°РІРёСЃРµС‚СЊ РѕС‚ РїР»Р°РІР°СЋС‰РµР№ С‚РѕС‡РєРё Рё РѕРєСЂСѓРіР»РµРЅРёР№.
        val epsilon = 0.001f
        if (currentDistanceKm + epsilon >= targetDistanceKm) {
            goalReached = true
            val cond = "Дистанция достигла целевой. Текущая: ${String.format("%.2f", currentDistanceKm)} км, цель: $targetDistanceKm км"
            speak("Цель достигнута. Тренировка поставлена на паузу.", cond)
            handlePause()
        }
    }

    private fun acceptPointReason(prev: Location, cur: Location): String? {
        val dtSec = ((cur.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000_000.0).toFloat()
        if (dtSec <= 0.2f) return "Too frequent (${String.format("%.2f", dtSec)}s)"

        // 1) С‚РѕС‡РЅРѕСЃС‚СЊ
        val acc = if (cur.hasAccuracy()) cur.accuracy else Float.MAX_VALUE
        if (acc > 40f) return "Bad accuracy (${String.format("%.1f", acc)}m)" // 40Рј РјСЏРіС‡Рµ (РґР»СЏ Р Р­Р‘)

        // 2) РґРёСЃС‚Р°РЅС†РёСЏ
        val d = prev.distanceTo(cur)
        if (d < 0.2f) return "Too close (${String.format("%.1f", d)}m)" // РѕС‚СЃРµРєР°РµРј С‚РѕР»СЊРєРѕ "СЃС‚РѕСЏРЅРѕС‡РЅС‹Р№ РґСЂРµР№С„" (РјРµРЅСЊС€Рµ РїРѕР»СѓРјРµС‚СЂР°)

        // 3) СЃРєРѕСЂРѕСЃС‚СЊ
        val v = d / dtSec // m/s
        if (v < 0.15f) return "Too slow (${String.format("%.1f", v)}m/s)" // "СЃС‚РѕСЋ/С€СѓРј"
        if (v > 12.0f) return "Too fast (${String.format("%.1f", v)}m/s) (Teleport)" // "С‚РµР»РµРїРѕСЂС‚/РіР»СЋРє" (СѓРІРµР»РёС‡РµРЅРѕ СЃ 7.5 РґРѕ 12.0 Рј/СЃ РґР»СЏ Р±С‹СЃС‚СЂС‹С… СЂС‹РІРєРѕРІ GPS)

        // 4) РґРѕРїРѕР»РЅРёС‚РµР»СЊРЅС‹Р№ СЃС‚РѕРї-РєСЂР°РЅ РѕС‚ Р±РѕР»СЊС€РёС… РїСЂС‹Р¶РєРѕРІ
        if (d > 120f && dtSec < 10f) return "Jump (${String.format("%.1f", d)}m in ${String.format("%.1f", dtSec)}s)"

        return null
    }

    private class LocationSmoother(private val windowSize: Int) {
        private val window: ArrayDeque<Location> = ArrayDeque()

        fun reset() {
            window.clear()
        }

        fun addAndGetSmoothed(location: Location): Location {
            if (window.size == windowSize) {
                window.removeFirst()
            }
            window.add(location)

            var latSum = 0.0
            var lonSum = 0.0
            var count = 0
            for (l in window) {
                latSum += l.latitude
                lonSum += l.longitude
                count++
            }
            val avgLat = latSum / count.toDouble()
            val avgLon = lonSum / count.toDouble()

            return Location(location).apply {
                latitude = avgLat
                longitude = avgLon
            }
        }
    }

    private fun maybeEmergencyAlert(currentDistanceKm: Float) {
        val goal = loadNormalGoal() ?: return
        val targetDistance = goal.targetDistanceKm ?: 0f
        val targetTotalSeconds = goal.targetTimeSec ?: 0
        if (targetDistance <= 0f || targetTotalSeconds <= 0) return

        // Only check every 250m
        if (currentDistanceKm - lastEmergencyCheckDistKm < 0.25f) return
        lastEmergencyCheckDistKm = currentDistanceKm.toDouble()

        // Measure next planned checkpoint distance
        val nextCheckpointKm = lastPaceCheckDistance + calculatePaceNotificationStep(currentDistanceKm)
        val distToNextCheckpoint = nextCheckpointKm - currentDistanceKm
        if (distToNextCheckpoint < 0.1f) return  // Too close to planned checkpoint

        // Check cooldown
        if (currentDistanceKm < emergencyCooldownUntilDistKm) return

        val currentElapsedSec = (currentElapsedMs() / 1000).toInt()
        val alert = com.example.stayer.debug.PacerLogicHelper.buildEmergencyAlert(
            prevGlobalProgress = lastCheckpointProgress,
            currentDistKm = currentDistanceKm.toDouble(),
            currentElapsedSec = currentElapsedSec,
            targetPaceSecPerKm = (targetTotalSeconds.toFloat() / targetDistance).roundToInt(),
            targetDistKm = targetDistance.toDouble(),
            targetTotalSec = targetTotalSeconds
        )
        if (alert != null) {
            val cond = "Отставание от графика. Статус: $lastCheckpointProgress, пройдено: ${String.format("%.2f", currentDistanceKm)} км, цель: $targetDistance км"
            speak(alert, cond)
            emergencyCooldownUntilDistKm = nextCheckpointKm.toDouble()
            paceCorrector.triggerCooldown(System.currentTimeMillis())
        }
    }

    /**
     * Smart Pace Corrector вЂ” normal mode.
     * Uses dynamicTargetPace (pace needed right now to finish on time).
     * Stays silent if < 30 sec to the next distance checkpoint.
     */
    private fun maybePaceCorrectorNormal(currentDistanceKm: Float) {
        if (!isPaceCorrectorEnabled()) return

        val goal = loadNormalGoal() ?: return
        val targetDist = goal.targetDistanceKm ?: 0f
        val targetTimeSec = goal.targetTimeSec ?: 0
        if (targetDist <= 0f || targetTimeSec <= 0) return

        val elapsedSec = (currentElapsedMs() / 1000).toInt()
        val remainDist = (targetDist - currentDistanceKm).coerceAtLeast(0.01f)
        val timeLeft = targetTimeSec - elapsedSec
        val dynamicTarget = if (timeLeft > 0 && remainDist > 0.05f)
            (timeLeft / remainDist).toInt()
        else
            (targetTimeSec.toFloat() / targetDist).toInt()

        // Silence if < 30 sec to next distance checkpoint
        val nextCheckpointKm = lastPaceCheckDistance + calculatePaceNotificationStep(currentDistanceKm)
        val distToCheckpoint = nextCheckpointKm - currentDistanceKm
        if (distToCheckpoint > 0f && dynamicTarget > 0) {
            val secsToCheckpoint = (distToCheckpoint * dynamicTarget).toInt()
            if (secsToCheckpoint < 30) return
        }

        val suggestion = paceCorrector.maybeSuggest(
            targetPaceSecPerKm = dynamicTarget,
            currentTimeMs = System.currentTimeMillis(),
            currentElapsedSec = elapsedSec.toDouble()
        )
        if (suggestion != null) {
            val cond = "Корректировщик темпа (обычный). Целевой: ${formatPaceShort(dynamicTarget)}"
            speak(suggestion, cond)
            paceCorrector.triggerCooldown(System.currentTimeMillis())
        }
    }

    private fun isPaceCorrectorEnabled(): Boolean {
        return getSharedPreferences("Goals", MODE_PRIVATE)
            .getBoolean(GoalActivity.PACE_CORRECTOR_ENABLED, true)
    }

    private fun maybeNotifyPace(currentDistanceKm: Float) {
        if (loadActiveGoal().workoutMode != 0) return  // Only for normal mode

        val step = calculatePaceNotificationStep(currentDistanceKm)
        if (step == Float.MAX_VALUE) return

        val distanceSinceLast = currentDistanceKm - lastPaceCheckDistance
        if (distanceSinceLast >= step && currentDistanceKm > 0.1f) {
            lastPaceCheckDistance = currentDistanceKm
            checkAndCorrectPace(currentDistanceKm)
        }
    }

    private fun calculatePaceNotificationStep(currentDistanceKm: Float): Float {
        val targetDistance = loadNormalGoal()?.targetDistanceKm ?: 0f
        if (targetDistance <= 0f) return Float.MAX_VALUE

        val progressPercent = (currentDistanceKm / targetDistance) * 100f
        return when {
            targetDistance < 10f -> targetDistance * 0.1f
            progressPercent < 80f -> targetDistance * 0.1f
            progressPercent < 90f -> 1.0f
            else -> 0.5f
        }
    }

    private fun checkAndCorrectPace(currentDistanceKm: Float) {
        val goal = loadNormalGoal() ?: return
        val targetDistance = goal.targetDistanceKm ?: 0f
        val targetTotalSeconds = goal.targetTimeSec ?: 0

        if (targetDistance <= 0f || targetTotalSeconds <= 0) return

        val targetPaceSecPerKm = targetTotalSeconds.toFloat() / targetDistance

        val currentDistM = currentDistanceKm * 1000.0
        val currentElapsedSec = (currentElapsedMs() / 1000).toInt()
        val previousCheckpointDistanceM = lastPacerCheckpointDistanceM
        val previousCheckpointElapsedSec = lastPacerCheckpointElapsedSec

        val deltaDistM = currentDistM - previousCheckpointDistanceM
        val deltaTimeSec = currentElapsedSec - previousCheckpointElapsedSec

        // Update checkpoints for NEXT time
        lastPacerCheckpointDistanceM = currentDistM
        lastPacerCheckpointElapsedSec = currentElapsedSec

        if (deltaDistM < 10.0 || deltaTimeSec <= 0) return

        val currentPaceSecPerKm = deltaTimeSec / (deltaDistM / 1000.0)

        // Protection from noise / unrealistic pace (e.g., < 3 min/km or > 20 min/km)
        if (currentPaceSecPerKm < 180 || currentPaceSecPerKm > 1200) return

        // Local Segment Prediction
        val diffSecPerKm = (currentPaceSecPerKm - targetPaceSecPerKm).roundToInt()

        // Global Prediction
        val avgPaceTotalSecPerKm = currentElapsedSec / currentDistanceKm
        val predictedFinishSec = (avgPaceTotalSecPerKm * targetDistance).roundToInt()
        val finishDeltaSec = predictedFinishSec - targetTotalSeconds
        val remainingDistKm = (targetDistance - currentDistanceKm).toDouble().coerceAtLeast(0.0)
        val timeLeftSec = targetTotalSeconds - currentElapsedSec

        val globalProgress = when {
            kotlin.math.abs(finishDeltaSec.toDouble()) <= 30.0 -> com.example.stayer.debug.GlobalProgress.ON_TRACK
            finishDeltaSec > 30 -> com.example.stayer.debug.GlobalProgress.BEHIND
            else -> com.example.stayer.debug.GlobalProgress.AHEAD
        }

        val (message, nextPraise) = com.example.stayer.debug.PacerLogicHelper.buildNormalPacerPrompt(
            globalProgress = globalProgress,
            globalDeltaSec = finishDeltaSec,
            localDiffSecPerKm = diffSecPerKm,
            currentPaceSecPerKm = currentPaceSecPerKm.toInt(),
            targetPaceSecPerKm = targetPaceSecPerKm.roundToInt(),
            remainingDistKm = remainingDistKm,
            timeLeftSec = timeLeftSec,
            currentDistKm = currentDistanceKm.toDouble(),
            pacerPraiseAlternate = pacerPraiseAlternate
        )
        pacerPraiseAlternate = nextPraise
        // Update emergency monitor context
        lastCheckpointProgress = globalProgress
        emergencyCooldownUntilDistKm = 0.0

        val targetSegmentSec = (targetPaceSecPerKm * (deltaDistM / 1000.0)).roundToInt()
        completedNormalCheckpoints += WorkoutHistoryCheckpoint(
            fromKm = (previousCheckpointDistanceM / 1000.0).toFloat(),
            toKm = currentDistanceKm,
            durationSec = deltaTimeSec,
            paceSecPerKm = currentPaceSecPerKm.toInt(),
            deltaSec = targetSegmentSec - deltaTimeSec
        )

        val checkpointCondition =
            "Плановый чекпоинт обычного режима. Пройдено: ${String.format(Locale.getDefault(), "%.2f", currentDistanceKm)} км, " +
                "темп сегмента: ${formatPaceShort(currentPaceSecPerKm.toInt())}, " +
                "глобальный статус: $globalProgress, финишное отклонение: $finishDeltaSec сек"
        speak(message, checkpointCondition)
        paceCorrector.triggerCooldown(System.currentTimeMillis())
    }

    // === Interval Execution Logic ===

    private fun getTotalDistanceMeters(): Double {
        return totalDistanceKm.toDouble() * 1000.0
    }

    private fun loadWorkoutModeAndScenario() {
        val goal = loadActiveGoal()
        val mode = goal.workoutMode
        activeWorkoutModeInt = mode

        intervalScenario = when (mode) {
            1 -> {
                val json = goal.intervalScenarioJson
                if (json.isNullOrBlank()) null
                else try {
                    Gson().fromJson(json, IntervalScenario::class.java)
                } catch (_: Exception) { null }
            }
            2 -> {
                val json = goal.comboScenarioJson
                if (json.isNullOrBlank()) null
                else try {
                    val combo = comboGson().fromJson(json, ComboScenario::class.java)
                    IntervalScenario(combo.flatten())
                } catch (_: Exception) { null }
            }
            else -> null
        }
        writeLog("SCENARIO_LOAD: mode=$mode, scenarioSegments=${intervalScenario?.segments?.size ?: 0}")

        // Reset interval state
        segmentIndex = 0
        lastAnnouncedSegmentIndex = -1
        warned10sIndex = -1
        midHintIndex = -1
        rest40Index = -1
        endReportIndex = -1
        lastIntervalHintInSegSec = -1
        intervalHintAlternate = false

        segmentStartElapsedSec = (currentElapsedMs() / 1000).toInt()
        stableStarted = false
        stableDeltasM.clear()
        lastTickDistanceM = getTotalDistanceMeters()

        // Reset phase accumulators
        accumWorkDistM = 0.0; accumWorkTimeSec = 0
        accumRestDistM = 0.0; accumRestTimeSec = 0
        accumWarmupDistM = 0.0; accumWarmupTimeSec = 0
        accumCooldownDistM = 0.0; accumCooldownTimeSec = 0
        segmentStartDistanceM = getTotalDistanceMeters()
        completedHistorySegments.clear()
        completedNormalCheckpoints.clear()
        nextHistorySegmentNumber = 1

        // Reset Pacer state
        lastPacerCheckpointDistanceM = 0.0
        lastPacerCheckpointElapsedSec = 0
        pacerPraiseAlternate = false
        resetComboPaceCheckpointState()
        writeLog("SCENARIO_RESET: segmentIndex=$segmentIndex, lastAnnouncedSegmentIndex=$lastAnnouncedSegmentIndex, segmentStartElapsedSec=$segmentStartElapsedSec, segmentStartDistanceM=$segmentStartDistanceM")
    }

    private fun handleIntervalTick() {
        val scenario = intervalScenario ?: return
        if (scenario.segments.isEmpty()) return
        if (segmentIndex !in scenario.segments.indices) return

        val seg = scenario.segments[segmentIndex]
        val elapsedSec = (currentElapsedMs() / 1000).toInt()
        val inSegSec = elapsedSec - segmentStartElapsedSec
        if (segmentIndex == lastAnnouncedSegmentIndex + 1 || lastAnnouncedSegmentIndex == -1) {
            writeLog("INTERVAL_TICK: segmentIndex=$segmentIndex, segType=${seg.type}, elapsedSec=$elapsedSec, inSegSec=$inSegSec, startElapsedSec=$segmentStartElapsedSec")
        }

        // PACE segments use distance-based remaining; others use time-based
        val totalDistM = getTotalDistanceMeters()
        val segDistCoveredKm = (totalDistM - segmentStartDistanceM) / 1000.0
        val isPaceSegment = seg.type == "PACE" && seg.distanceKm != null && seg.distanceKm > 0
        val remainingSec = if (isPaceSegment) Int.MAX_VALUE else (seg.durationSec - inSegSec)
        val deltaM = (totalDistM - lastTickDistanceM).coerceAtLeast(0.0)
        lastTickDistanceM = totalDistM

        // 1) Announce segment start (once per segment)
        if (segmentIndex != lastAnnouncedSegmentIndex) {
            writeLog("INTERVAL_ANNOUNCE: segmentIndex=$segmentIndex, segType=${seg.type}, targetPace=${seg.targetPaceSecPerKm}, duration=${seg.durationSec}, distanceKm=${seg.distanceKm}")
            lastAnnouncedSegmentIndex = segmentIndex

            // Reset stable tracking for new segment
            stableStarted = false
            stableDeltasM.clear()
            if (seg.type == "PACE") {
                resetComboPaceCheckpointState()
            }

            speakSegmentStart(seg)
            broadcastIntervalState(seg, remainingSec.coerceAtLeast(0), segmentIndex + 1, scenario.segments.size)
        }

        // 2) Warning 10 seconds before segment ends
        if (remainingSec == 10 && warned10sIndex != segmentIndex) {
            warned10sIndex = segmentIndex
            speak("Смена через 10 секунд", "Осталось 10 секунд до конца текущего сегмента (тип: ${seg.type})")
        }

        // 3) Start "stable" tracking for WORK/WARMUP/COOLDOWN after ignoring first 3 seconds
        val needsStableTracking = seg.type == "WORK" || seg.type == "WARMUP" || seg.type == "COOLDOWN"
        if (needsStableTracking && !stableStarted && inSegSec >= workIgnoreSec) {
            stableStarted = true
            stableStartElapsedSec = elapsedSec
            stableStartDistanceM = totalDistM
            stableDeltasM.clear()
        }

        // 4) Collect rolling speed data during stable phase
        if (stableStarted && (seg.type == "WORK" || seg.type == "REST" || seg.type == "WARMUP" || seg.type == "COOLDOWN")) {
            stableDeltasM.add(deltaM)
            while (stableDeltasM.size > speedWindowSec) stableDeltasM.removeFirst()
        }

        // 5) Smart Pace Corrector for WORK segments (replaces old mid-hints)
        if (seg.type == "WORK" && seg.targetPaceSecPerKm != null && remainingSec > 20 && isPaceCorrectorEnabled()) {
            val suggestion = paceCorrector.maybeSuggest(
                targetPaceSecPerKm = seg.targetPaceSecPerKm,
                currentTimeMs = System.currentTimeMillis(),
                currentElapsedSec = elapsedSec.toDouble()
            )
            if (suggestion != null) {
                val cond = "Корректировщик темпа (интервал). Целевой: ${formatPaceShort(seg.targetPaceSecPerKm)}"
                speak(suggestion, cond)
                paceCorrector.triggerCooldown(System.currentTimeMillis())
            }
        }

        // 5.5) Time-based checkpoints + corrector for WARMUP/COOLDOWN
        val isWarmupOrCooldown = seg.type == "WARMUP" || seg.type == "COOLDOWN"
        if (isWarmupOrCooldown && seg.targetPaceSecPerKm != null) {
            // First checkpoint at 2 min (120s), then every 3 min (180s)
            val firstHintSec = 120
            val repeatHintSec = 180
            val hintDue = when {
                lastIntervalHintInSegSec < 0 && inSegSec >= firstHintSec -> true
                lastIntervalHintInSegSec >= 0 && (inSegSec - lastIntervalHintInSegSec) >= repeatHintSec -> true
                else -> false
            }
            if (hintDue && remainingSec > 15) {
                lastIntervalHintInSegSec = inSegSec
                val curPace = estimatePaceFromWindow()
                val target = seg.targetPaceSecPerKm
                val label = if (seg.type == "WARMUP") "Разминка" else "Заминка"
                val passedMin = inSegSec / 60
                if (curPace != null) {
                    val diff = curPace - target
                    val prompt = when {
                        kotlin.math.abs(diff) <= 15 -> "$label, $passedMin минут. Темп отличный."
                        diff > 15 -> "$label, $passedMin минут. Темп ${formatPaceShort(curPace)}, нужен ${formatPaceShort(target)}. Чуть быстрее."
                        else -> "$label, $passedMin минут. Не торопись. Темп ${formatPaceShort(curPace)}."
                    }
                    speak(prompt, "Чекпоинт $label. прошло $passedMin мин, темп $curPace, цель $target")
                    paceCorrector.triggerCooldown(System.currentTimeMillis())
                } else {
                    speak("$label, $passedMin минут.", "Чекпоинт $label. прошло $passedMin мин, темп не определён")
                }
            }

            // Corrector between checkpoints
            if (remainingSec > 20 && isPaceCorrectorEnabled()) {
                val suggestion = paceCorrector.maybeSuggest(
                    targetPaceSecPerKm = seg.targetPaceSecPerKm,
                    currentTimeMs = System.currentTimeMillis(),
                    currentElapsedSec = elapsedSec.toDouble()
                )
                if (suggestion != null) {
                    val cond = "Корректировщик (${seg.type}). Целевой: ${formatPaceShort(seg.targetPaceSecPerKm)}"
                    speak(suggestion, cond)
                    paceCorrector.triggerCooldown(System.currentTimeMillis())
                }
            }
        }

        // 6.5) PACE segment: normal pacer hints every 500m
        if (isPaceSegment && seg.targetPaceSecPerKm != null) {
            val segDistM = totalDistM - segmentStartDistanceM
            maybeNotifyComboPaceSegment(seg, segDistM, inSegSec, deltaM)
        }

        // 7) Segment transition
        val shouldTransition = if (isPaceSegment) {
            segDistCoveredKm >= seg.distanceKm!!
        } else {
            remainingSec <= 0
        }

        if (shouldTransition) {
            // End report for WORK
            if (seg.type == "WORK" && seg.targetPaceSecPerKm != null && endReportIndex != segmentIndex) {
                endReportIndex = segmentIndex
                speakWorkEndReport(seg, totalDistM, elapsedSec)
            }

            // Accumulate stats for the completed segment
            accumulateSegmentStats(seg, totalDistM, elapsedSec)

            segmentIndex += 1
            if (segmentIndex >= scenario.segments.size) {
                // Interval workout complete вЂ” persist stats for history
                persistIntervalStats()
                speak("Тренировка завершена", "Завершены все сегменты интервальной тренировки")
                handlePause()
                intervalScenario = null
                return
            }

            // Next segment starts at current second
            segmentStartElapsedSec = elapsedSec
            segmentStartDistanceM = totalDistM
            lastIntervalHintInSegSec = -1  // reset hint timer for new segment
            paceCorrector.reset()  // restart 30-sec warm-up for new segment
            resetComboPaceCheckpointState()
            // Next tick will announce the new segment
        }
    }

    private fun estimatePaceFromWindow(): Int? {
        if (stableDeltasM.isEmpty()) return null
        val meters = stableDeltasM.sum()
        val seconds = stableDeltasM.size
        if (seconds < 5) return null
        if (meters < 8.0) return null // Too little movement = noise

        val speed = meters / seconds // m/s
        if (speed <= 0.1) return null

        return (1000.0 / speed).toInt()
    }

    /**
     * Обычный темповый чекпоинт внутри combo-блока PACE.
     * Normal-mode style checkpoint inside a combo PACE block.
     */
    private fun maybeNotifyComboPaceSegment(seg: Segment, segDistM: Double, inSegSec: Int, deltaM: Double) {
        val targetDistKm = seg.distanceKm ?: return
        val targetPace = seg.targetPaceSecPerKm ?: return
        if (targetDistKm <= 0.0 || targetPace <= 0 || inSegSec <= 0) return

        val targetDistM = targetDistKm * 1000.0
        val checkpointStep = minOf(500.0, targetDistM * 0.1).coerceAtLeast(100.0)
        val lastCheckpointN = ((segDistM - deltaM).coerceAtLeast(0.0) / checkpointStep).toInt()
        val currentCheckpointN = (segDistM / checkpointStep).toInt()
        if (currentCheckpointN <= lastCheckpointN || segDistM >= targetDistM - 50.0) return

        val deltaDistM = segDistM - comboPaceCheckpointDistanceM
        val deltaTimeSec = inSegSec - comboPaceCheckpointElapsedSec
        comboPaceCheckpointDistanceM = segDistM
        comboPaceCheckpointElapsedSec = inSegSec
        if (deltaDistM < 10.0 || deltaTimeSec <= 0) return

        val currentPace = (deltaTimeSec / (deltaDistM / 1000.0)).roundToInt()
        if (currentPace !in 180..1200) return

        val currentDistKm = segDistM / 1000.0
        val targetTotalSec = (targetDistKm * targetPace).roundToInt()
        val averagePace = (inSegSec / currentDistKm).roundToInt()
        val predictedFinishSec = (averagePace * targetDistKm).roundToInt()
        val finishDeltaSec = predictedFinishSec - targetTotalSec
        val remainingDistKm = (targetDistKm - currentDistKm).coerceAtLeast(0.0)
        val timeLeftSec = targetTotalSec - inSegSec
        val localDiffSecPerKm = currentPace - targetPace
        val globalProgress = when {
            kotlin.math.abs(finishDeltaSec.toDouble()) <= 30.0 -> com.example.stayer.debug.GlobalProgress.ON_TRACK
            finishDeltaSec > 30 -> com.example.stayer.debug.GlobalProgress.BEHIND
            else -> com.example.stayer.debug.GlobalProgress.AHEAD
        }

        val (message, nextPraiseAlternate) = com.example.stayer.debug.PacerLogicHelper.buildNormalPacerPrompt(
            globalProgress = globalProgress,
            globalDeltaSec = finishDeltaSec,
            localDiffSecPerKm = localDiffSecPerKm,
            currentPaceSecPerKm = currentPace,
            targetPaceSecPerKm = targetPace,
            remainingDistKm = remainingDistKm,
            timeLeftSec = timeLeftSec,
            currentDistKm = currentDistKm,
            pacerPraiseAlternate = comboPacePraiseAlternate
        )
        comboPacePraiseAlternate = nextPraiseAlternate

        val cond = "Чекпоинт обычного блока комбо. Пройдено: ${String.format(Locale.getDefault(), "%.2f", currentDistKm)} км, темп отрезка: ${formatPaceShort(currentPace)}, цель: ${formatPaceShort(targetPace)}, прогноз: ${finishDeltaSec} сек"
        speak(message, cond)
        paceCorrector.triggerCooldown(System.currentTimeMillis())
    }

    /**
     * Сбрасывает состояние чекпоинтов обычного блока combo.
     * Resets checkpoint state for a combo normal block.
     */
    private fun resetComboPaceCheckpointState() {
        comboPaceCheckpointDistanceM = 0.0
        comboPaceCheckpointElapsedSec = 0
        comboPacePraiseAlternate = false
    }

    /** Accumulate distance/time for the completed segment into per-type buckets. */
    private fun accumulateSegmentStats(seg: Segment, currentTotalDistM: Double, currentElapsedSec: Int) {
        val segDistM = (currentTotalDistM - segmentStartDistanceM).coerceAtLeast(0.0)
        val segTimeSec = (currentElapsedSec - segmentStartElapsedSec).coerceAtLeast(0)

        when (seg.type) {
            "WORK"     -> { accumWorkDistM += segDistM;     accumWorkTimeSec += segTimeSec }
            "REST"     -> { accumRestDistM += segDistM;     accumRestTimeSec += segTimeSec }
            "WARMUP"   -> { accumWarmupDistM += segDistM;   accumWarmupTimeSec += segTimeSec }
            "COOLDOWN" -> { accumCooldownDistM += segDistM; accumCooldownTimeSec += segTimeSec }
        }

        recordSegmentForHistory(seg, segDistM, segTimeSec)
    }

    private fun recordSegmentForHistory(seg: Segment, segDistM: Double, segTimeSec: Int) {
        val shouldStore = shouldStoreSegmentInHistory(seg.type)
        if (!shouldStore) return

        completedHistorySegments += WorkoutHistorySegment(
            title = "Участок ${nextHistorySegmentNumber++}",
            type = seg.type,
            distanceKm = (segDistM / 1000.0).toFloat(),
            durationSec = segTimeSec,
            actualPaceSecPerKm = paceOrNull(segDistM, segTimeSec),
            targetPaceSecPerKm = seg.targetPaceSecPerKm?.takeIf { it > 0 }
        )
    }

    private fun shouldStoreSegmentInHistory(type: String): Boolean {
        return when (activeWorkoutModeInt) {
            1 -> type == "WORK"
            2 -> type == "WORK" || type == "PACE"
            else -> false
        }
    }

    private fun buildSnapshotSegmentDetails(): List<WorkoutHistorySegment> {
        val result = completedHistorySegments.toMutableList()
        val scenario = intervalScenario ?: return result
        if (segmentIndex !in scenario.segments.indices) return result

        val seg = scenario.segments[segmentIndex]
        if (!shouldStoreSegmentInHistory(seg.type)) return result

        val segDistM = (getTotalDistanceMeters() - segmentStartDistanceM).coerceAtLeast(0.0)
        val segTimeSec = ((currentElapsedMs() / 1000).toInt() - segmentStartElapsedSec).coerceAtLeast(0)
        if (segDistM < 1.0 && segTimeSec <= 0) return result

        result += WorkoutHistorySegment(
            title = "Участок $nextHistorySegmentNumber",
            type = seg.type,
            distanceKm = (segDistM / 1000.0).toFloat(),
            durationSec = segTimeSec,
            actualPaceSecPerKm = paceOrNull(segDistM, segTimeSec),
            targetPaceSecPerKm = seg.targetPaceSecPerKm?.takeIf { it > 0 }
        )
        return result
    }

    /**
     * Собирает успешно озвученные чекпоинты обычного режима для истории.
     * Builds successfully spoken normal-mode checkpoints for workout history.
     */
    private fun buildSnapshotCheckpointDetails(): List<WorkoutHistoryCheckpoint> {
        return completedNormalCheckpoints.toList()
    }

    private fun paceOrNull(distM: Double, timeSec: Int): Int? {
        if (distM < 10.0 || timeSec < 5) return null
        val speed = distM / timeSec
        if (speed <= 0.1) return null
        return (1000.0 / speed).toInt()
    }

    /** Compute 4 average paces and write to SharedPreferences for MainActivity to read. */
    private fun persistIntervalStats() {
        val avgWork = paceOrNull(accumWorkDistM, accumWorkTimeSec)
        val avgRest = paceOrNull(accumRestDistM, accumRestTimeSec)
        val avgWithout = paceOrNull(
            accumWorkDistM + accumRestDistM,
            accumWorkTimeSec + accumRestTimeSec
        )
        val avgTotal = paceOrNull(
            accumWorkDistM + accumRestDistM + accumWarmupDistM + accumCooldownDistM,
            accumWorkTimeSec + accumRestTimeSec + accumWarmupTimeSec + accumCooldownTimeSec
        )

        getSharedPreferences("WorkoutRuntime", MODE_PRIVATE).edit()
            .putInt("INTERVAL_AVG_WORK", avgWork ?: -1)
            .putInt("INTERVAL_AVG_REST", avgRest ?: -1)
            .putInt("INTERVAL_AVG_NO_WARMUP", avgWithout ?: -1)
            .putInt("INTERVAL_AVG_TOTAL", avgTotal ?: -1)
            .apply()
    }

    private fun speakWorkEndReport(seg: Segment, totalDistM: Double, elapsedSec: Int) {
        val target = seg.targetPaceSecPerKm
        val condExt = "Фаза работы завершена"
        if (!stableStarted || target == null) {
            speak("Фаза работы завершена", "$condExt (нет данных стабильного или целевого темпа)")
            return
        }

        val stableTimeSec = (elapsedSec - stableStartElapsedSec).coerceAtLeast(0)
        val stableDistM = (totalDistM - stableStartDistanceM).coerceAtLeast(0.0)

        if (stableTimeSec < 10 || stableDistM < 25.0) {
            speak("Работа завершена. Темп оценить точно не удалось", "$condExt (слишком короткая фаза для оценки темпа)")
            return
        }

        val speed = stableDistM / stableTimeSec
        val factPace = (1000.0 / speed).toInt()

        val report = com.example.stayer.debug.PacerLogicHelper.buildIntervalEndReport(
            factPaceSecPerKm = factPace,
            targetPaceSecPerKm = target
        )
        val condFull = "$condExt. Фактический темп: ${formatPaceShort(factPace)}, целевой: ${formatPaceShort(target)}"
        speak(report, condFull)
    }

    private fun speakSegmentStart(seg: Segment) {
        val label = when (seg.type) {
            "WARMUP" -> "Разминка"
            "WORK" -> "Работа"
            "REST" -> "Отдых"
            "COOLDOWN" -> "Заминка"
            "PACE" -> "Обычная"
            else -> "Сегмент"
        }

        // PACE segments announce distance instead of time
        if (seg.type == "PACE" && seg.distanceKm != null) {
            val distText = formatDistanceSpeech(seg.distanceKm)
            val pacePart = seg.targetPaceSecPerKm?.let { " Темп ${formatPaceShort(it)}." } ?: ""
            val cond = "Начало нового сегмента (тип: ${seg.type}, дистанция: ${seg.distanceKm} км)"
            speak("$label. $distText.$pacePart", cond)
            return
        }

        val dur = seg.durationSec
        val durText = formatDurationSpeech(dur)

        val pacePart = seg.targetPaceSecPerKm?.let { " Темп ${formatPaceShort(it)}." } ?: ""
        val cond2 = "Начало нового сегмента (тип: ${seg.type}, длительность: $dur сек)"
        speak("$label. $durText.$pacePart", cond2)
    }

    private fun formatPaceShort(secPerKm: Int): String {
        val m = secPerKm / 60
        val s = secPerKm % 60
        return if (s == 0) "${unitText(m, "минута", "минуты", "минут")}"
        else "${unitText(m, "минута", "минуты", "минут")} ${unitText(s, "секунда", "секунды", "секунд")}"
    }

    private fun broadcastIntervalState(seg: Segment, remainingSec: Int, idx: Int, total: Int) {
        val intent = Intent(ACTION_BROADCAST_UPDATE).apply {
            `package` = packageName
            putExtra(EXTRA_DISTANCE_KM, totalDistanceKm)
            putExtra(EXTRA_ELAPSED_MS, currentElapsedMs())
            putExtra(EXTRA_IS_RUNNING, isRunning)
            putExtra(EXTRA_IS_PAUSED, isPaused)
            putExtra(EXTRA_CURRENT_PACE_SEC_PER_KM, currentPaceEstimator.currentPaceSecPerKm() ?: -1)
            putExtra("interval_active", true)
            putExtra("interval_type", seg.type)
            putExtra("interval_remaining_sec", remainingSec)
            putExtra("interval_index", idx)
            putExtra("interval_total", total)
            seg.targetPaceSecPerKm?.let { putExtra("interval_target_pace_sec_per_km", it) }
        }
        sendBroadcast(intent)
    }

    private fun speak(text: String, condition: String? = null, priority: SpeechPriority = SpeechPriority.PACER): Boolean {
        val now = System.currentTimeMillis()

        if (priority == SpeechPriority.AUXILIARY) {
            // Drop if TTS is currently speaking
            if (pendingTtsUtterances > 0) {
                writeLog("SPEECH_DROPPED: Auxiliary TTS busy. Text: $text")
                return false
            }
            // Drop if pacer spoke less than 15s ago
            if (now - lastPacerSpeechTimeMs < 15_000L) {
                writeLog("SPEECH_DROPPED: Pacer silence window active. Text: $text")
                return false
            }
        } else {
            lastPacerSpeechTimeMs = now
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val effectiveCondition = condition ?: "не указано"
        val logLine = "[$timestamp] СПИЧ: \"$text\" | УСЛОВИЕ: $effectiveCondition | ПРИОРИТЕТ: $priority\n"
        try {
            val logFile = File(getExternalFilesDir(null), "stayer_log.txt")
            FileWriter(logFile, true).use {
                it.append(logLine)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        synchronized(this) {
            if (pendingTtsUtterances == 0) {
                requestAudioFocusForTTS()
                if (!ttsWakeLock.isHeld) {
                    ttsWakeLock.acquire(15_000L)
                }
            }
            pendingTtsUtterances += 1
        }

        textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, "pace_${System.currentTimeMillis()}")
        return true
    }

    private fun handleTtsUtteranceFinished() {
        synchronized(this) {
            pendingTtsUtterances = (pendingTtsUtterances - 1).coerceAtLeast(0)
            if (pendingTtsUtterances > 0) return
        }
        abandonAudioFocusForTTS()
        if (ttsWakeLock.isHeld) ttsWakeLock.release()
    }

    private fun formatPace(secondsPerKm: Float): String {
        if (secondsPerKm <= 0f || secondsPerKm == Float.MAX_VALUE) return "0 секунд"
        val totalSeconds = secondsPerKm.toInt()
        val minutes = totalSeconds / 60
        val seconds = (totalSeconds % 60)
        val roundedSeconds = ((seconds / 5) * 5)
        return when {
            minutes == 0 -> unitText(roundedSeconds, "секунда", "секунды", "секунд")
            roundedSeconds == 0 -> unitText(minutes, "минута", "минуты", "минут")
            else -> "${unitText(minutes, "минута", "минуты", "минут")} ${unitText(roundedSeconds, "секунда", "секунды", "секунд")}"
        }
    }

    private fun formatRemainingDistance(remainingKm: Float): String {
        if (remainingKm <= 0f) return "0 метров"
        val kilometers = remainingKm.toInt()
        val meters = ((remainingKm - kilometers) * 1000).toInt()
        return when {
            kilometers == 0 -> unitText(meters, "метр", "метра", "метров")
            meters == 0 -> unitText(kilometers, "километр", "километра", "километров")
            else -> {
                val kmText = unitText(kilometers, "километр", "километра", "километров")
                val mText = unitText(meters, "метр", "метра", "метров")
                "$kmText $mText"
            }
        }
    }

    private fun formatDurationSpeech(totalSec: Int): String {
        if (totalSec <= 0) return "0 секунд"
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return when {
            minutes == 0 -> unitText(seconds, "секунда", "секунды", "секунд")
            seconds == 0 -> unitText(minutes, "минута", "минуты", "минут")
            else -> "${unitText(minutes, "минута", "минуты", "минут")} ${unitText(seconds, "секунда", "секунды", "секунд")}"
        }
    }

    private fun formatDistanceSpeech(distanceKm: Double): String {
        return if (distanceKm >= 1.0) {
            val rounded = String.format(Locale.getDefault(), "%.1f", distanceKm)
            "$rounded километра"
        } else {
            val meters = (distanceKm * 1000.0).toInt()
            unitText(meters, "метр", "метра", "метров")
        }
    }

    private fun unitText(value: Int, one: String, few: String, many: String): String {
        val mod100 = value % 100
        val mod10 = value % 10
        val unit = when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
        return "$value $unit"
    }

    private fun setupFemaleVoice() {
        try {
            val voices = textToSpeech.voices
            var femaleVoice: android.speech.tts.Voice? = null
            for (voice in voices) {
                val voiceLocale = voice.locale
                if (voiceLocale.language == "ru") {
                    val voiceName = voice.name.lowercase()
                    if (voiceName.contains("female") ||
                        voiceName.contains("Р¶РµРЅСЃРє") ||
                        voiceName.contains("Р¶РµРЅСЃРєРёР№") ||
                        voiceName.contains("anna") ||
                        voiceName.contains("elena") ||
                        voiceName.contains("milena") ||
                        voiceName.contains("katya")
                    ) {
                        femaleVoice = voice
                        break
                    }
                    if (femaleVoice == null) femaleVoice = voice
                }
            }
            if (femaleVoice != null) {
                textToSpeech.setVoice(femaleVoice)
            }
            textToSpeech.setPitch(1.1f)
            textToSpeech.setSpeechRate(0.95f)
        } catch (_: Exception) {
            textToSpeech.setPitch(1.1f)
            textToSpeech.setSpeechRate(0.95f)
        }
    }

    @SuppressLint("NewApi")
    private fun requestAudioFocusForTTS() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                // РҐРѕС‚РёРј, С‡С‚РѕР±С‹ СЃС‚РѕСЂРѕРЅРЅРµРµ РјРµРґРёР° СЂРµР°Р»СЊРЅРѕ СѓСЃС‚СѓРїР°Р»Рѕ (Р° РЅРµ "РјРѕР¶РµС‚ РїСЂРёРіР»СѓС€РёС‚СЊСЃСЏ").
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            // Р”Р»СЏ РіРѕР»РѕСЃРѕРІС‹С… РїРѕРґСЃРєР°Р·РѕРє Р»СѓС‡С€Рµ, С‡РµРј NOTIFICATION вЂ” С‡Р°С‰Рµ РєРѕСЂСЂРµРєС‚РЅРµРµ СѓРїСЂР°РІР»СЏРµС‚ РјРµРґРёР°.
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            textToSpeech.stop()
                        }
                    }
                    .build()
            }
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    @SuppressLint("NewApi")
    private fun abandonAudioFocusForTTS() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (_: Exception) {
            }
        }
        sensorManager.unregisterListener(stepListener)
        stopTicking()
        closeGpxLog()
        if (wakeLock.isHeld) wakeLock.release()
        if (::ttsWakeLock.isInitialized && ttsWakeLock.isHeld) ttsWakeLock.release()
        pendingTtsUtterances = 0
        try {
            textToSpeech.shutdown()
        } catch (_: Exception) {
        }
    }


    private fun initGpxLog() {
        try {
            val dir = getExternalFilesDir(null)
            if (dir != null) {
                cleanupOldGpxFiles(dir)
                
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                gpxFile = File(dir, "stayer_track_$timestamp.gpx")
                gpxWriter = FileWriter(gpxFile, false)
                gpxWriter?.let { writer ->
                    writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                    writer.write("<gpx version=\"1.1\" creator=\"Stayer\">\n")
                    writer.write("  <trk>\n")
                    writer.write("    <name>Stayer Workout $timestamp</name>\n")
                    writer.write("    <trkseg>\n")
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            gpxWriter = null
        }
    }

    private fun cleanupOldGpxFiles(dir: File) {
        try {
            val files = dir.listFiles { _, name -> name.startsWith("stayer_track_") && name.endsWith(".gpx") }
            if (files != null && files.size >= 30) {
                // РЎРѕСЂС‚РёСЂСѓРµРј РїРѕ РґР°С‚Рµ РёР·РјРµРЅРµРЅРёСЏ (СЃС‚Р°СЂС‹Рµ РїРµСЂРІС‹РјРё)
                files.sortBy { it.lastModified() }
                // РЈРґР°Р»СЏРµРј СЃР°РјС‹Рµ СЃС‚Р°СЂС‹Рµ, С‡С‚РѕР±С‹ РѕР±С‰РµРµ РєРѕР»РёС‡РµСЃС‚РІРѕ СЃС‚Р°Р»Рѕ 29 (РѕСЃС‚Р°РІР»СЏРµРј РјРµСЃС‚Рѕ РґР»СЏ РЅРѕРІРѕРіРѕ)
                val filesToDelete = files.size - 29
                for (i in 0 until filesToDelete) {
                    files[i].delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun closeGpxLog() {
        try {
            gpxWriter?.let { writer ->
                writer.write("    </trkseg>\n")
                writer.write("  </trk>\n")
                writer.write("</gpx>\n")
                writer.flush()
                writer.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            gpxWriter = null
            gpxFile = null
        }
    }

    private fun logGpxPoint(location: Location, reason: String? = null) {
        if (gpxWriter == null) return
        try {
            val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            df.timeZone = TimeZone.getTimeZone("UTC")
            val timeStr = df.format(Date(location.time))

            val builder = StringBuilder()
            builder.append("      <trkpt lat=\"${location.latitude}\" lon=\"${location.longitude}\">\n")
            if (location.hasAltitude()) builder.append("        <ele>${location.altitude}</ele>\n")
            builder.append("        <time>$timeStr</time>\n")
            if (reason != null) {
                builder.append("        <desc>REJECTED: $reason. Speed: ${location.speed}, Acc: ${location.accuracy}</desc>\n")
            } else {
                builder.append("        <desc>ACCEPTED. Speed: ${location.speed}, Acc: ${location.accuracy}</desc>\n")
            }
            builder.append("      </trkpt>\n")

            gpxWriter?.write(builder.toString())
            gpxWriter?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Р¤РѕСЂРјРёСЂСѓРµС‚ С„РёРЅР°Р»СЊРЅС‹Р№ snapshot С‚СЂРµРЅРёСЂРѕРІРєРё Р”Рћ Р»СЋР±РѕРіРѕ reset'Р°.
     * РСЃС‚РѕС‡РЅРёРє РёСЃС‚РёРЅС‹ РґР»СЏ СЃРѕС…СЂР°РЅРµРЅРёСЏ РёСЃС‚РѕСЂРёРё.
     */
    private fun buildFinalSnapshot(): WorkoutSummarySnapshot {
        writeLog("SERVICE: Building final snapshot...")
        
        // Р¤РёРЅР°Р»СЊРЅС‹Рµ Р·РЅР°С‡РµРЅРёСЏ РёР· СЃРµСЂРІРёСЃР° (РёСЃС‚РѕС‡РЅРёРє РёСЃС‚РёРЅС‹)
        val finalDistanceKm = totalDistanceKm
        val finalElapsedMs = currentElapsedMs()
        
        // Р’С‹С‡РёСЃР»СЏРµРј СЃРєРѕСЂРѕСЃС‚СЊ
        val speedKmh = if (finalDistanceKm > 0 && finalElapsedMs > 0) {
            finalDistanceKm / (finalElapsedMs / 3600000.0f)
        } else 0f
        
        // Р§РёС‚Р°РµРј С†РµР»Рё
        val goal = loadActiveGoal()
        val targetDistanceKm = goal.targetDistanceKm?.takeIf { it > 0f }
        val targetTimeSec = goal.targetTimeSec?.takeIf { it > 0 }
        val targetPaceSecPerKm = goal.targetPaceSecPerKm?.takeIf { it > 0 }
        val workoutModeInt = goal.workoutMode
        val goalLabel = buildGoalLabel(goal)
        
        // Р§РёС‚Р°РµРј РёРЅС‚РµСЂРІР°Р»СЊРЅСѓСЋ СЃС‚Р°С‚РёСЃС‚РёРєСѓ (РµСЃР»Рё РµСЃС‚СЊ)
        val runtimePrefs = getSharedPreferences("WorkoutRuntime", MODE_PRIVATE)
        val avgWork = runtimePrefs.getInt("INTERVAL_AVG_WORK", -1).takeIf { it > 0 }
        val avgRest = runtimePrefs.getInt("INTERVAL_AVG_REST", -1).takeIf { it > 0 }
        val avgNoWarmup = runtimePrefs.getInt("INTERVAL_AVG_NO_WARMUP", -1).takeIf { it > 0 }
        val avgTotal = runtimePrefs.getInt("INTERVAL_AVG_TOTAL", -1).takeIf { it > 0 }
        
        // РћРїСЂРµРґРµР»СЏРµРј СЂРµР¶РёРј
        val mode = when {
            workoutModeInt == 1 -> "interval"
            workoutModeInt == 2 -> "combined"
            avgWork != null || avgRest != null -> "interval"
            else -> "normal"
        }
        
        writeLog("SNAPSHOT: dist=$finalDistanceKm, elapsed=$finalElapsedMs, mode=$mode")
        
        return WorkoutSummarySnapshot(
            distanceKm = finalDistanceKm,
            elapsedMs = finalElapsedMs,
            speedKmh = speedKmh,
            workoutMode = mode,
            normalGoalMode = goal.normalGoalMode,
            goalLabel = goalLabel,
            targetDistanceKm = targetDistanceKm,
            targetTimeSec = targetTimeSec,
            targetPaceSecPerKm = targetPaceSecPerKm,
            avgPaceWorkSec = avgWork,
            avgPaceRestSec = avgRest,
            avgPaceWithoutWarmupSec = avgNoWarmup,
            avgPaceTotalSec = avgTotal,
            segmentDetails = buildSnapshotSegmentDetails(),
            checkpointDetails = buildSnapshotCheckpointDetails()
        )
    }

    private fun buildGoalLabel(goal: ActiveWorkoutGoal): String? {
        return when (goal.workoutMode) {
            1 -> buildIntervalGoalLabel(goal.intervalScenarioJson)
            2 -> buildComboGoalLabel(goal.comboScenarioJson)
            else -> buildNormalGoalLabel(goal)
        }
    }

    private fun buildNormalGoalLabel(goal: ActiveWorkoutGoal): String? {
        val distancePart = goal.targetDistanceKm
            ?.takeIf { it > 0f }
            ?.let { String.format(Locale.getDefault(), "%.2f км", it) }
        val secondary = when (goal.normalGoalMode) {
            1 -> goal.targetPaceSecPerKm?.takeIf { it > 0 }?.let(::formatHistoryPace)
            else -> goal.targetTimeSec?.takeIf { it > 0 }?.let(::formatHistoryClock)
        }
        return listOfNotNull(distancePart, secondary).takeIf { it.isNotEmpty() }?.joinToString(" • ")
    }

    private fun buildIntervalGoalLabel(json: String?): String {
        if (json.isNullOrBlank()) return "Интервальная"
        return try {
            val scenario = Gson().fromJson(json, IntervalScenario::class.java)
            val workSeg = scenario.segments.firstOrNull { it.type == "WORK" }
            val restSeg = scenario.segments.firstOrNull { it.type == "REST" }
            val workCount = scenario.segments.count { it.type == "WORK" }
            when {
                workSeg != null && restSeg != null && workCount > 0 ->
                    "${workCount}×${formatHistoryClock(workSeg.durationSec)} / ${formatHistoryClock(restSeg.durationSec)}"
                workSeg != null && workCount > 0 ->
                    "${workCount}×${formatHistoryClock(workSeg.durationSec)}"
                else -> "Интервальная"
            }
        } catch (_: Exception) {
            "Интервальная"
        }
    }

    private fun buildComboGoalLabel(json: String?): String {
        if (json.isNullOrBlank()) return "Комбо"
        return try {
            val scenario = comboGson().fromJson(json, ComboScenario::class.java)
            "Комбо • ${formatHistoryClock(scenario.estimateTotalTimeSec())}"
        } catch (_: Exception) {
            "Комбо"
        }
    }

    private fun formatHistoryClock(totalSec: Int): String {
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatHistoryPace(secPerKm: Int): String {
        val minutes = secPerKm / 60
        val seconds = secPerKm % 60
        return String.format(Locale.getDefault(), "%d:%02d/км", minutes, seconds)
    }

    /**
     * РћС‚РїСЂР°РІР»СЏРµС‚ С„РёРЅР°Р»СЊРЅС‹Р№ snapshot РІ MainActivity РґР»СЏ СЃРѕС…СЂР°РЅРµРЅРёСЏ РёСЃС‚РѕСЂРёРё.
     */
    private fun broadcastFinalSnapshot(snapshot: WorkoutSummarySnapshot) {
        val intent = Intent(ACTION_BROADCAST_FINAL_SNAPSHOT).apply {
            `package` = packageName
            putExtra(EXTRA_SNAPSHOT_JSON, Gson().toJson(snapshot))
        }
        sendBroadcast(intent)
        writeLog("SERVICE: Final snapshot broadcast sent")
    }
    
    /**
     * РЎРѕС…СЂР°РЅСЏРµС‚ snapshot РІ РЅР°РґС‘Р¶РЅРѕРµ С…СЂР°РЅРёР»РёС‰Рµ Р”Рћ reset'Р°.
     * Р•СЃР»Рё СЃРѕС…СЂР°РЅРµРЅРёРµ РІ РёСЃС‚РѕСЂРёСЋ РЅРµ Р·Р°РІРµСЂС€РёС‚СЃСЏ, snapshot РјРѕР¶РЅРѕ РІРѕСЃСЃС‚Р°РЅРѕРІРёС‚СЊ.
     */
    private fun savePendingSnapshot(snapshot: WorkoutSummarySnapshot) {
        try {
            val prefs = getSharedPreferences(PREFS_PENDING_SNAPSHOT, MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_PENDING_SNAPSHOT_JSON, Gson().toJson(snapshot))
                .putLong(KEY_PENDING_SNAPSHOT_TIMESTAMP, System.currentTimeMillis())
                .apply()
            writeLog("SERVICE: Pending snapshot saved to storage")
        } catch (e: Exception) {
            writeLog("ERROR: Failed to save pending snapshot: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * РћС‡РёС‰Р°РµС‚ pending snapshot РїРѕСЃР»Рµ СѓСЃРїРµС€РЅРѕРіРѕ СЃРѕС…СЂР°РЅРµРЅРёСЏ Рё reset'Р°.
     */
    private fun clearPendingSnapshot() {
        try {
            val prefs = getSharedPreferences(PREFS_PENDING_SNAPSHOT, MODE_PRIVATE)
            prefs.edit().clear().apply()
            writeLog("SERVICE: Pending snapshot cleared")
        } catch (e: Exception) {
            writeLog("ERROR: Failed to clear pending snapshot: ${e.message}")
        }
    }
    
    /**
     * РџСЂРѕРІРµСЂСЏРµС‚ РЅР°Р»РёС‡РёРµ РЅРµСЃРѕС…СЂР°РЅС‘РЅРЅРѕРіРѕ snapshot РїСЂРё СЃС‚Р°СЂС‚Рµ Service.
     * Р•СЃР»Рё РµСЃС‚СЊ - РїС‹С‚Р°РµС‚СЃСЏ РІРѕСЃСЃС‚Р°РЅРѕРІРёС‚СЊ Рё СЃРѕС…СЂР°РЅРёС‚СЊ.
     */
    private fun checkAndRestorePendingSnapshot() {
        try {
            val prefs = getSharedPreferences(PREFS_PENDING_SNAPSHOT, MODE_PRIVATE)
            val snapshotJson = prefs.getString(KEY_PENDING_SNAPSHOT_JSON, null)
            val timestamp = prefs.getLong(KEY_PENDING_SNAPSHOT_TIMESTAMP, 0L)
            
            if (!snapshotJson.isNullOrBlank()) {
                val age = System.currentTimeMillis() - timestamp
                writeLog("SERVICE: Found pending snapshot, age=${age}ms")
                
                // Р’РѕСЃСЃС‚Р°РЅР°РІР»РёРІР°РµРј С‚РѕР»СЊРєРѕ СЃРІРµР¶РёРµ snapshot (РЅРµ СЃС‚Р°СЂС€Рµ 10 РјРёРЅСѓС‚)
                if (age < 10 * 60 * 1000) {
                    val snapshot = Gson().fromJson(snapshotJson, WorkoutSummarySnapshot::class.java)
                    if (snapshot.isValid()) {
                        writeLog("SERVICE: Restoring pending snapshot: distance=${snapshot.distanceKm}km")
                        val saved = saveWorkoutHistoryFromSnapshot(snapshot)
                        if (saved) {
                            writeLog("SERVICE: Pending snapshot successfully restored and saved")
                            clearPendingSnapshot()
                        }
                    } else {
                        writeLog("SERVICE: Pending snapshot invalid, clearing")
                        clearPendingSnapshot()
                    }
                } else {
                    writeLog("SERVICE: Pending snapshot too old, clearing")
                    clearPendingSnapshot()
                }
            }
        } catch (e: Exception) {
            writeLog("ERROR: Failed to restore pending snapshot: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Service РЎРђРњ СЃРѕС…СЂР°РЅСЏРµС‚ РёСЃС‚РѕСЂРёСЋ РёР· snapshot.
     * РќР• Р·Р°РІРёСЃРёС‚ РѕС‚ Activity - РЅР°РґС‘Р¶РЅРѕРµ СЃРѕС…СЂР°РЅРµРЅРёРµ.
     */
    private fun saveWorkoutHistoryFromSnapshot(snapshot: WorkoutSummarySnapshot): Boolean {
        return try {
            writeLog("SERVICE: Saving workout history from snapshot")
            
            val workoutHistory = snapshot.toWorkoutHistory()
            WorkoutHistoryRepository(this).prepend(workoutHistory)
            
            writeLog("SERVICE_HISTORY_SAVED: ${Gson().toJson(workoutHistory)}")
            
            // РћС‡РёС‰Р°РµРј РІСЂРµРјРµРЅРЅСѓСЋ СЃС‚Р°С‚РёСЃС‚РёРєСѓ РёРЅС‚РµСЂРІР°Р»РѕРІ
            getSharedPreferences("WorkoutRuntime", MODE_PRIVATE).edit().clear().apply()
            
            true
        } catch (e: Exception) {
            writeLog("ERROR: Failed to save history from snapshot: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun writeLog(message: String) {
        try {
            val logFile = File(getExternalFilesDir(null), "stayer_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val logMessage = "[$timestamp] $message\n"
            FileWriter(logFile, true).use { writer ->
                writer.append(logMessage)
            }
        } catch (e: Exception) {
            // РРіРЅРѕСЂРёСЂСѓРµРј РѕС€РёР±РєРё Р»РѕРіРёСЂРѕРІР°РЅРёСЏ
        }
    }
}



