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
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

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
        private const val NORMAL_GOAL_MODE = "NORMAL_GOAL_MODE" // 0=time, 1=pace
        private const val TARGET_DISTANCE_KM = "TARGET_DISTANCE_KM" // Float
        private const val TARGET_TIME_SEC = "TARGET_TIME_SEC" // Int
        private const val TARGET_PACE_SEC_PER_KM = "TARGET_PACE_SEC_PER_KM" // Int

        const val ACTION_START_OR_RESUME = "com.example.stayer.action.START_OR_RESUME"
        const val ACTION_PAUSE = "com.example.stayer.action.PAUSE"
        const val ACTION_STOP_AND_RESET = "com.example.stayer.action.STOP_AND_RESET"

        const val ACTION_BROADCAST_UPDATE = "com.example.stayer.action.WORKOUT_UPDATE"
        const val EXTRA_DISTANCE_KM = "extra_distance_km"
        const val EXTRA_ELAPSED_MS = "extra_elapsed_ms"
        const val EXTRA_IS_RUNNING = "extra_is_running"
        const val EXTRA_IS_PAUSED = "extra_is_paused"

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
    }

    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var locationCallback: LocationCallback? = null
    private var lastAcceptedRawLocation: Location? = null
    private var lastSmoothedLocation: Location? = null
    // Уменьшено окно сглаживания с 7 до 3 для снижения эффекта "срезания углов" на поворотах
    private val smoother = LocationSmoother(windowSize = 3)

    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    private var totalDistanceKm: Float = 0f
    private var lastPaceCheckDistance: Float = 0f
    private var goalReached: Boolean = false

    private var isRunning: Boolean = false
    private var isPaused: Boolean = false
    private var startTimeMs: Long = 0L
    private var pausedAtMs: Long = 0L
    private var totalPausedMs: Long = 0L

    private lateinit var wakeLock: PowerManager.WakeLock

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private lateinit var textToSpeech: TextToSpeech

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

    // Normal mode Pacer state
    private var lastPacerCheckpointDistanceM = 0.0
    private var lastPacerCheckpointElapsedSec = 0
    private var pacerPraiseAlternate = false

    // Emergency monitor state (250m check)
    private var lastEmergencyCheckDistKm = 0.0
    private var lastCheckpointProgress = com.example.stayer.debug.GlobalProgress.ON_TRACK
    private var emergencyCooldownUntilDistKm = 0.0

    // GPX Logging state
    private var gpxWriter: FileWriter? = null
    private var gpxFile: File? = null

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

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        restoreState()

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = Locale("ru")
                if (textToSpeech.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                    textToSpeech.language = locale
                    setupFemaleVoice()
                }
            }
        }

        // Важно: для TTS поверх музыки
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
        }
        return START_STICKY
    }

    private fun handleStartOrResume() {
        // Старт foreground сразу, чтобы Android не убил сервис за таймаут
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!wakeLock.isHeld) {
            // На время тренировки держим CPU живым (иначе Doze может жёстко резать апдейты)
            wakeLock.acquire(10 * 60 * 60 * 1000L) // 10 часов максимум (manual stop/reset)
        }

        val now = System.currentTimeMillis()
        if (!isRunning) {
            isRunning = true
            isPaused = false
            startTimeMs = if (startTimeMs == 0L) now else startTimeMs
            pausedAtMs = 0L
            goalReached = false
            // При новом старте точку инициализируем с нуля, чтобы не было скачка
            resetTrackState()
            
            // Начинаем новый GPX лог
            initGpxLog()

            // Load interval scenario on fresh start
            loadWorkoutModeAndScenario()
        } else if (isPaused) {
            isPaused = false
            if (pausedAtMs > 0L) {
                totalPausedMs += (now - pausedAtMs)
            }
            pausedAtMs = 0L
            // Важно: после паузы сбрасываем lastLocation, иначе будет скачок
            resetTrackState()
        }

        persistState()
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

        stopLocationUpdates()
        sensorManager.unregisterListener(stepListener)
        stopTicking()
        persistState()
        broadcastUpdate()
        updateNotification()
    }

    private fun handleStopAndReset() {
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
        
        // Reset Pacer state
        lastPacerCheckpointDistanceM = 0.0
        lastPacerCheckpointElapsedSec = 0
        pacerPraiseAlternate = false

        resetTrackState()
        closeGpxLog()

        sensorManager.unregisterListener(stepListener)
        lastTotalSteps = -1
        stepsSinceLastTick = 0

        persistState()
        broadcastUpdate()

        if (wakeLock.isHeld) wakeLock.release()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun resetTrackState() {
        lastAcceptedRawLocation = null
        lastSmoothedLocation = null
        smoother.reset()
        // Reset emergency monitor
        lastEmergencyCheckDistKm = 0.0
        lastCheckpointProgress = com.example.stayer.debug.GlobalProgress.ON_TRACK
        emergencyCooldownUntilDistKm = 0.0
    }

    private fun startTicking() {
        if (tickRunnable != null) return
        tickRunnable = object : Runnable {
            override fun run() {
                if (isRunning && !isPaused) {
                    // 1. Process Cadence Fallback (Intelligent Steps)
                    val stepsToProcess = stepsSinceLastTick
                    stepsSinceLastTick = 0
                    val fallbackDistM = fallbackEngine.processTick(stepsToProcess)
                    if (fallbackDistM > 0.0) {
                        totalDistanceKm += (fallbackDistM.toFloat() / 1000f)
                    }

                    // 2. Таймер и нотификация должны обновляться независимо от частоты GPS-точек
                    broadcastUpdate()
                    updateNotification()
                    handleIntervalTick()
                    tickHandler.postDelayed(this, 1000L)
                } else if (isRunning) {
                    // Paused — still tick for timer display but skip interval logic
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

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            // Нет прав — не падаем, просто остаёмся в foreground без трекинга
            return
        }

        if (locationCallback != null) return

        // Максимум качества со стороны Fused:
        // - частые точки
        // - без пакетирования (maxDelay=0)
        // - minDistance=0 (фильтруем ниже сами)
        // - waitForAccurateLocation=true (просим дождаться точности)
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
            return
        }

        val rejectReason = acceptPointReason(prev, location)
        logGpxPoint(location, rejectReason)

        if (rejectReason != null) {
            fallbackEngine.processGpsRejected(rejectReason)
            return
        }

        lastAcceptedRawLocation = location
        val smoothed = smoother.addAndGetSmoothed(location)
        val prevSmoothed = lastSmoothedLocation
        if (prevSmoothed != null) {
            val rawDeltaM = prevSmoothed.distanceTo(smoothed).toDouble()
            if (rawDeltaM > 0.0) {
                val acceptedDistM = fallbackEngine.processGpsAccepted(rawDeltaM)
                if (acceptedDistM > 0.0) {
                    totalDistanceKm += (acceptedDistM.toFloat() / 1000f)
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
     * Авто-пауза по достижению цели дистанции.
     * ВАЖНО: сейчас считаем по GPS-дистанции сервиса (источник истины). Шаги Activity сюда не входят.
     */
    private fun maybeAutoPauseOnTarget(currentDistanceKm: Float) {
        if (!isRunning || isPaused || goalReached) return

        val goals = getSharedPreferences("Goals", MODE_PRIVATE)
        
        // 1) Try new float key
        val targetDistanceKm = if (goals.contains(TARGET_DISTANCE_KM)) {
            goals.getFloat(TARGET_DISTANCE_KM, 0f)
        } else {
            // 2) Fallback to old string key
            val targetDistanceStr = goals.getString("TARGET_DISTANCE", "0") ?: "0"
            targetDistanceStr.replace(',', '.').toFloatOrNull() ?: 0f
        }
        
        if (targetDistanceKm <= 0f) return

        // Небольшая дельта, чтобы не зависеть от плавающей точки и округлений.
        val epsilon = 0.001f
        if (currentDistanceKm + epsilon >= targetDistanceKm) {
            goalReached = true
            speak("Цель достигнута. Тренировка поставлена на паузу.")
            handlePause()
        }
    }

    private fun acceptPointReason(prev: Location, cur: Location): String? {
        val dtSec = ((cur.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000_000.0).toFloat()
        if (dtSec <= 0.2f) return "Too frequent (${String.format("%.2f", dtSec)}s)"

        // 1) точность
        val acc = if (cur.hasAccuracy()) cur.accuracy else Float.MAX_VALUE
        if (acc > 15f) return "Bad accuracy (${String.format("%.1f", acc)}m)" // 10–15м на практике; 15м мягче, меньше "провалов"

        // 2) дистанция
        val d = prev.distanceTo(cur)
        if (d < 0.8f) return "Too close (${String.format("%.1f", d)}m)" // отсекаем дрожание

        // 3) скорость
        val v = d / dtSec // m/s
        if (v < 0.3f) return "Too slow (${String.format("%.1f", v)}m/s)" // "стою/шум"
        if (v > 12.0f) return "Too fast (${String.format("%.1f", v)}m/s) (Teleport)" // "телепорт/глюк" (увеличено с 7.5 до 12.0 м/с для быстрых рывков GPS)

        // 4) дополнительный стоп-кран от больших прыжков
        if (d > 120f && dtSec < 10f) return "Jump (${String.format("%.1f", d)}m in ${String.format("%.1f", dtSec)}s)"

        return null
    }

    private fun acceptPoint(prev: Location, cur: Location): Boolean {
        return acceptPointReason(prev, cur) == null
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
            window.addLast(location)

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
        val goals = getSharedPreferences("Goals", MODE_PRIVATE)
        val workoutMode = goals.getInt("WORKOUT_MODE", 0)
        if (workoutMode != 0) return  // Only for normal mode

        val targetDistance = if (goals.contains(TARGET_DISTANCE_KM))
            goals.getFloat(TARGET_DISTANCE_KM, 0f)
        else goals.getString("TARGET_DISTANCE", "0")?.replace(',', '.')?.toFloatOrNull() ?: 0f
        val targetTotalSeconds = goals.getInt(TARGET_TIME_SEC, 0)
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
            speak(alert)
            emergencyCooldownUntilDistKm = nextCheckpointKm.toDouble()
        }
    }

    private fun maybeNotifyPace(currentDistanceKm: Float) {
        val step = calculatePaceNotificationStep(currentDistanceKm)
        if (step == Float.MAX_VALUE) return

        val distanceSinceLast = currentDistanceKm - lastPaceCheckDistance
        if (distanceSinceLast >= step && currentDistanceKm > 0.1f) {
            lastPaceCheckDistance = currentDistanceKm
            checkAndCorrectPace(currentDistanceKm)
        }
    }

    private fun calculatePaceNotificationStep(currentDistanceKm: Float): Float {
        val goals = getSharedPreferences("Goals", MODE_PRIVATE)
        
        // Try new key first
        val targetDistance = if (goals.contains(TARGET_DISTANCE_KM)) {
            goals.getFloat(TARGET_DISTANCE_KM, 0f)
        } else {
            val targetDistanceStr = goals.getString("TARGET_DISTANCE", "0") ?: "0"
            targetDistanceStr.toFloatOrNull() ?: 0f
        }
        
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
        val goals = getSharedPreferences("Goals", MODE_PRIVATE)

        // === Reading Goals with Fallback ===
        val targetDistance = if (goals.contains(TARGET_DISTANCE_KM)) {
            goals.getFloat(TARGET_DISTANCE_KM, 0f)
        } else {
            goals.getString("TARGET_DISTANCE", "0")?.toFloatOrNull() ?: 0f
        }

        val targetTotalSeconds = if (goals.contains(TARGET_TIME_SEC)) {
            goals.getInt(TARGET_TIME_SEC, 0)
        } else {
            // Fallback to parsing "HH:MM:SS" string
            val targetTimeStr = goals.getString("TARGET_TIME", "0") ?: "0"
            val parts = targetTimeStr.split(":")
            if (parts.size == 3) {
                val h = parts[0].toIntOrNull() ?: 0
                val m = parts[1].toIntOrNull() ?: 0
                val s = parts[2].toIntOrNull() ?: 0
                h * 3600 + m * 60 + s
            } else 0
        }

        if (targetDistance <= 0f || targetTotalSeconds <= 0) return

        val targetPaceSecPerKm = targetTotalSeconds.toFloat() / targetDistance

        val currentDistM = currentDistanceKm * 1000.0
        val currentElapsedSec = (currentElapsedMs() / 1000).toInt()

        val deltaDistM = currentDistM - lastPacerCheckpointDistanceM
        val deltaTimeSec = currentElapsedSec - lastPacerCheckpointElapsedSec

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

        speak(message)
    }

    // === Interval Execution Logic ===

    private fun getTotalDistanceMeters(): Double {
        return totalDistanceKm.toDouble() * 1000.0
    }

    private fun loadWorkoutModeAndScenario() {
        val prefs = getSharedPreferences("Goals", MODE_PRIVATE)
        val mode = prefs.getInt(WORKOUT_MODE, 0)

        intervalScenario = when (mode) {
            1 -> {
                val json = prefs.getString("INTERVAL_SCENARIO_JSON", null)
                if (json.isNullOrBlank()) null
                else try {
                    Gson().fromJson(json, IntervalScenario::class.java)
                } catch (_: Exception) { null }
            }
            2 -> {
                val json = prefs.getString("COMBO_SCENARIO_JSON", null)
                if (json.isNullOrBlank()) null
                else try {
                    val combo = comboGson().fromJson(json, ComboScenario::class.java)
                    IntervalScenario(combo.flatten())
                } catch (_: Exception) { null }
            }
            else -> null
        }

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

        // Reset Pacer state
        lastPacerCheckpointDistanceM = 0.0
        lastPacerCheckpointElapsedSec = 0
        pacerPraiseAlternate = false
    }

    private fun handleIntervalTick() {
        val scenario = intervalScenario ?: return
        if (scenario.segments.isEmpty()) return
        if (segmentIndex !in scenario.segments.indices) return

        val seg = scenario.segments[segmentIndex]
        val elapsedSec = (currentElapsedMs() / 1000).toInt()
        val inSegSec = elapsedSec - segmentStartElapsedSec

        // PACE segments use distance-based remaining; others use time-based
        val totalDistM = getTotalDistanceMeters()
        val segDistCoveredKm = (totalDistM - segmentStartDistanceM) / 1000.0
        val isPaceSegment = seg.type == "PACE" && seg.distanceKm != null && seg.distanceKm > 0
        val remainingSec = if (isPaceSegment) Int.MAX_VALUE else (seg.durationSec - inSegSec)
        val deltaM = (totalDistM - lastTickDistanceM).coerceAtLeast(0.0)
        lastTickDistanceM = totalDistM

        // 1) Announce segment start (once per segment)
        if (segmentIndex != lastAnnouncedSegmentIndex) {
            lastAnnouncedSegmentIndex = segmentIndex

            // Reset stable tracking for new segment
            stableStarted = false
            stableDeltasM.clear()

            speakSegmentStart(seg)
            broadcastIntervalState(seg, remainingSec.coerceAtLeast(0), segmentIndex + 1, scenario.segments.size)
        }

        // 2) Warning 10 seconds before segment ends
        if (remainingSec == 10 && warned10sIndex != segmentIndex) {
            warned10sIndex = segmentIndex
            speak("Смена через 10 секунд")
        }

        // 3) Start "stable" tracking for WORK after ignoring first 3 seconds
        if (seg.type == "WORK" && !stableStarted && inSegSec >= workIgnoreSec) {
            stableStarted = true
            stableStartElapsedSec = elapsedSec
            stableStartDistanceM = totalDistM
            stableDeltasM.clear()
        }

        // 4) Collect rolling speed data during stable phase
        if (stableStarted && (seg.type == "WORK" || seg.type == "REST")) {
            stableDeltasM.addLast(deltaM)
            while (stableDeltasM.size > speedWindowSec) stableDeltasM.removeFirst()
        }

        // 5) Adaptive pace hints for WORK segments with target pace
        if (seg.type == "WORK" && seg.targetPaceSecPerKm != null && stableStarted && stableDeltasM.size >= 5) {
            val stableInSegSec = inSegSec - workIgnoreSec  // seconds since stable phase began
            val timing = com.example.stayer.debug.PacerLogicHelper.intervalHintTiming(seg.durationSec)

            val shouldHint = if (timing != null) {
                // Periodic hints for segments >= 2 min
                val (firstAt, repeatEvery) = timing
                if (lastIntervalHintInSegSec < 0) {
                    stableInSegSec >= firstAt
                } else {
                    stableInSegSec - lastIntervalHintInSegSec >= repeatEvery
                }
            } else if (seg.durationSec in 30..119) {
                // Single mid-hint for 30s-2min segments
                val midPoint = seg.durationSec / 2
                inSegSec >= midPoint && midHintIndex != segmentIndex
            } else {
                false  // <30s segments: no mid-hints at all
            }

            // Don't hint in the last 15 seconds (too close to transition)
            if (shouldHint && remainingSec > 15) {
                val curPace = estimatePaceFromWindow()
                if (curPace != null) {
                    // Layer 2: average pace over the stable phase
                    val stableDistM = (totalDistM - stableStartDistanceM).coerceAtLeast(1.0)
                    val stableTimeSec = (elapsedSec - stableStartElapsedSec).coerceAtLeast(1)
                    val avgPace = if (stableDistM > 25.0) (stableTimeSec / (stableDistM / 1000.0)).toInt() else null

                    val (hint, nextAlt) = com.example.stayer.debug.PacerLogicHelper.buildIntervalHint(
                        currentPaceSecPerKm = curPace,
                        targetPaceSecPerKm = seg.targetPaceSecPerKm,
                        alternate = intervalHintAlternate,
                        avgPaceSecPerKm = avgPace
                    )
                    intervalHintAlternate = nextAlt
                    speak(hint)
                    lastIntervalHintInSegSec = stableInSegSec
                    if (timing == null) midHintIndex = segmentIndex  // mark single hint as done
                }
            }
        }

        // 6) REST: message at 40th second (if REST >= 40s)
        if (seg.type == "REST" && seg.durationSec >= 40 && rest40Index != segmentIndex && inSegSec == 40) {
            rest40Index = segmentIndex
            val pace = estimatePaceFromWindow()
            if (pace != null) speak("Отдых. Темп примерно ${formatPaceShort(pace)}")
            else speak("Отдых")
        }

        // 6.5) PACE segment: normal pacer hints every 500m
        if (isPaceSegment && seg.targetPaceSecPerKm != null) {
            val segDistM = totalDistM - segmentStartDistanceM
            val targetDistM = seg.distanceKm!! * 1000.0
            // Checkpoint every 500m (or 10% of total, whichever is smaller)
            val checkpointStep = minOf(500.0, targetDistM * 0.1).coerceAtLeast(100.0)
            val lastCheckpointN = ((segDistM - deltaM) / checkpointStep).toInt()
            val curCheckpointN = (segDistM / checkpointStep).toInt()
            if (curCheckpointN > lastCheckpointN && segDistM < targetDistM - 50) {
                val curPace = estimatePaceFromWindow()
                if (curPace != null && seg.targetPaceSecPerKm > 0) {
                    val diff = curPace - seg.targetPaceSecPerKm
                    val prompt = when {
                        kotlin.math.abs(diff) <= 15 -> "Темп хороший. Осталось ${String.format("%.1f", (targetDistM - segDistM) / 1000.0)} км."
                        diff > 15 -> "Темп ${formatPaceShort(curPace)}. Нужен ${formatPaceShort(seg.targetPaceSecPerKm)}. Ускорьтесь."
                        else -> "Не гони. Темп ${formatPaceShort(curPace)}."
                    }
                    speak(prompt)
                }
            }
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
                // Interval workout complete — persist stats for history
                persistIntervalStats()
                speak("Тренировка завершена")
                handlePause()
                intervalScenario = null
                return
            }

            // Next segment starts at current second
            segmentStartElapsedSec = elapsedSec
            segmentStartDistanceM = totalDistM
            lastIntervalHintInSegSec = -1  // reset hint timer for new segment
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
    }

    /** Compute 4 average paces and write to SharedPreferences for MainActivity to read. */
    private fun persistIntervalStats() {
        fun paceOrNull(distM: Double, timeSec: Int): Int? {
            if (distM < 10.0 || timeSec < 5) return null
            val speed = distM / timeSec  // m/s
            if (speed <= 0.1) return null
            return (1000.0 / speed).toInt()  // sec/km
        }

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
        if (!stableStarted || target == null) {
            speak("Фаза работы завершена")
            return
        }

        val stableTimeSec = (elapsedSec - stableStartElapsedSec).coerceAtLeast(0)
        val stableDistM = (totalDistM - stableStartDistanceM).coerceAtLeast(0.0)

        if (stableTimeSec < 10 || stableDistM < 25.0) {
            speak("Работа завершена. Темп оценить точно не удалось")
            return
        }

        val speed = stableDistM / stableTimeSec
        val factPace = (1000.0 / speed).toInt()

        val report = com.example.stayer.debug.PacerLogicHelper.buildIntervalEndReport(
            factPaceSecPerKm = factPace,
            targetPaceSecPerKm = target
        )
        speak(report)
    }

    private fun speakSegmentStart(seg: Segment) {
        val label = when (seg.type) {
            "WARMUP" -> "Разминка"
            "WORK" -> "Работа"
            "REST" -> "Отдых"
            "COOLDOWN" -> "Заминка"
            "PACE" -> "Свободный бег"
            else -> "Сегмент"
        }

        // PACE segments announce distance instead of time
        if (seg.type == "PACE" && seg.distanceKm != null) {
            val distText = String.format("%.1f километра", seg.distanceKm)
            val pacePart = seg.targetPaceSecPerKm?.let { " Темп ${formatPaceShort(it)}." } ?: ""
            speak("$label. $distText.$pacePart")
            return
        }

        val dur = seg.durationSec
        val durText = if (dur >= 60) {
            val m = dur / 60
            val s = dur % 60
            if (s == 0) "$m минут" else "$m минут $s секунд"
        } else "$dur секунд"

        val pacePart = seg.targetPaceSecPerKm?.let { " Темп ${formatPaceShort(it)}." } ?: ""
        speak("$label. $durText.$pacePart")
    }

    private fun formatPaceShort(secPerKm: Int): String {
        val m = secPerKm / 60
        val s = secPerKm % 60
        return if (s == 0) "$m минут" else "$m минут $s секунд"
    }

    private fun broadcastIntervalState(seg: Segment, remainingSec: Int, idx: Int, total: Int) {
        val intent = Intent(ACTION_BROADCAST_UPDATE).apply {
            `package` = packageName
            putExtra("interval_active", true)
            putExtra("interval_type", seg.type)
            putExtra("interval_remaining_sec", remainingSec)
            putExtra("interval_index", idx)
            putExtra("interval_total", total)
            seg.targetPaceSecPerKm?.let { putExtra("interval_target_pace_sec_per_km", it) }
        }
        sendBroadcast(intent)
    }

    private fun speak(text: String) {
        requestAudioFocusForTTS()
        // отдельный короткий wakelock на TTS фразу (чтобы не резало на экране-off)
        val ttsWake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WorkoutForegroundService::TTSWake")
        ttsWake.acquire(15_000L)

        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                abandonAudioFocusForTTS()
                if (ttsWake.isHeld) ttsWake.release()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                abandonAudioFocusForTTS()
                if (ttsWake.isHeld) ttsWake.release()
            }
        })

        textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, "pace_${System.currentTimeMillis()}")
    }

    private fun formatPace(secondsPerKm: Float): String {
        if (secondsPerKm <= 0f || secondsPerKm == Float.MAX_VALUE) return "0 минут 0 секунд"
        val totalSeconds = secondsPerKm.toInt()
        val minutes = totalSeconds / 60
        val seconds = (totalSeconds % 60)
        val roundedSeconds = ((seconds / 5) * 5)
        return when {
            minutes == 0 -> "$roundedSeconds секунд"
            roundedSeconds == 0 -> "$minutes минут"
            else -> "$minutes минут $roundedSeconds секунд"
        }
    }

    private fun formatRemainingDistance(remainingKm: Float): String {
        if (remainingKm <= 0f) return "0 километров 0 метров"
        val kilometers = remainingKm.toInt()
        val meters = ((remainingKm - kilometers) * 1000).toInt()
        return when {
            kilometers == 0 -> "$meters метров"
            meters == 0 -> when (kilometers) {
                1 -> "1 километр"
                in 2..4 -> "$kilometers километра"
                else -> "$kilometers километров"
            }
            else -> {
                val kmText = when (kilometers) {
                    1 -> "1 километр"
                    in 2..4 -> "$kilometers километра"
                    else -> "$kilometers километров"
                }
                val mText = when (meters) {
                    1 -> "1 метр"
                    in 2..4 -> "$meters метра"
                    else -> "$meters метров"
                }
                "$kmText $mText"
            }
        }
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
                        voiceName.contains("женск") ||
                        voiceName.contains("женский") ||
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
                // Хотим, чтобы стороннее медиа реально уступало (а не "может приглушиться").
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            // Для голосовых подсказок лучше, чем NOTIFICATION — чаще корректнее управляет медиа.
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
        stopLocationUpdates()
        sensorManager.unregisterListener(stepListener)
        stopTicking()
        closeGpxLog()
        if (wakeLock.isHeld) wakeLock.release()
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
                // Сортируем по дате изменения (старые первыми)
                files.sortBy { it.lastModified() }
                // Удаляем самые старые, чтобы общее количество стало 29 (оставляем место для нового)
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
}


