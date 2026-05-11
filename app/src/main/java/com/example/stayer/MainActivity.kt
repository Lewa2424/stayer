// MainActivity.kt
package com.example.stayer

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.stayer.history.WorkoutHistoryRepository
import com.example.stayer.ui.main.MainScreen
import com.example.stayer.ui.main.SetupChecklistScreen
import com.example.stayer.ui.theme.StayerTheme
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.gson.Gson
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val locationPermissionRequestCode = 100
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var isActive = false
    private val handler = Handler(Looper.getMainLooper())
    private var isLongPress = false
    private var startTime: Long = 0
    private var pausedTime: Long = 0 // Время, когда была поставлена пауза
    private var totalPausedDuration: Long = 0 // Общая длительность всех пауз
    private var isTimerRunning = false
    private var isPaused = false
    private var timerRunnable: Runnable? = null
    // Источник истины для времени — сервис. Храним последнее значение для сохранения истории/отладки.
    private var lastElapsedMsFromService: Long = 0L
    private var previousLatitude: Double? = null
    private var previousLongitude: Double? = null
    private var totalDistance: Float = 0f
    private var lastPaceCheckDistance: Float = 0f // Дистанция с последнего уведомления о темпе
    private lateinit var wakeLock: PowerManager.WakeLock
    @Suppress("unused")
    private var locationCallback: LocationCallback? = null

    private var workoutUpdateReceiver: BroadcastReceiver? = null
    private var finalSnapshotReceiver: BroadcastReceiver? = null
    private var pendingSnapshot: WorkoutSummarySnapshot? = null


    // Переменные для шагомера и TextToSpeech
    private lateinit var sensorManager: SensorManager
    private var stepCount = 0
    private var stepDistance: Float = 0.0f
    private lateinit var textToSpeech: TextToSpeech

    // Переменные для управления звуком
    private lateinit var audioManager: AudioManager

    // ==== UI state (Compose) ====
    private var uiElapsedMs by mutableLongStateOf(0L)
    private var uiGpsDistanceKm by mutableFloatStateOf(0f)
    private var uiStepDistanceKm by mutableFloatStateOf(0f)
    private var uiIsRunning by mutableStateOf(false)
    private var uiIsPaused by mutableStateOf(false)
    private var uiGoalValue by mutableStateOf("—")
    private var uiGoalSupporting by mutableStateOf<String?>(null)
    private var uiCurrentPaceText by mutableStateOf("—")

    // Interval UI state (from service broadcast)
    private var uiIntervalActive by mutableStateOf(false)
    private var uiIntervalType by mutableStateOf("")
    private var uiIntervalRemainingSec by mutableIntStateOf(0)
    private var uiIntervalIndex by mutableIntStateOf(0)
    private var uiIntervalTotal by mutableIntStateOf(0)
    private var uiIntervalTargetPaceSecPerKm by mutableStateOf<Int?>(null)

    // Scenario preview (before workout starts)
    private var uiWorkoutMode by mutableIntStateOf(0)
    private var uiScenarioPreview by mutableStateOf("")

    // GPS Status Indicator
    enum class GpsStatus { SEARCHING, POOR, READY }
    private var uiGpsStatus by mutableStateOf(GpsStatus.SEARCHING)
    private var preStartLocationCallback: LocationCallback? = null

    private fun checkLocationPermission() {
        val permissionsToRequest = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionsToRequest.add(android.Manifest.permission.ACTIVITY_RECOGNITION)
        }
        
        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                locationPermissionRequestCode
            )
        }
    }

    private fun startWorkoutService() {
        stopPreStartLocationUpdates()
        WorkoutForegroundService.startOrResume(this)
    }

    private fun checkNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1010
                )
            }
        }
    }
    //отсечка
    // Функция для проверки доступности языка
    private fun checkTTSSupportForLanguage(locale: Locale): Boolean {
        return textToSpeech.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
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
                // Проверяем русский язык и женский пол (если указан)
                if (voiceLocale.language == "ru") {
                    // Проверяем имя голоса на наличие женских признаков
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
                    // Если не нашли по имени, берем первый русский голос и настраиваем параметры
                    if (femaleVoice == null) {
                        femaleVoice = voice
                    }
                }
            }
            
            // Устанавливаем найденный голос или используем настройки по умолчанию
            if (femaleVoice != null) {
                val result = textToSpeech.setVoice(femaleVoice)
                if (result == TextToSpeech.SUCCESS) {
                    Log.d("TTS", "Установлен голос: ${femaleVoice.name}")
                }
            }
            
            // Настраиваем параметры для более человечного звучания
            // Pitch: 1.0 = нормальный, >1.0 = выше (женский), <1.0 = ниже (мужской)
            textToSpeech.setPitch(1.1f) // Немного выше для женского голоса
            
            // Speech rate: 1.0 = нормальный, можно немного замедлить для естественности
            textToSpeech.setSpeechRate(0.95f) // Немного медленнее для более естественного звучания
            
        } catch (e: Exception) {
            Log.e("TTS", "Ошибка настройки голоса: ${e.message}")
            // Устанавливаем базовые параметры даже при ошибке
            textToSpeech.setPitch(1.1f)
            textToSpeech.setSpeechRate(0.95f)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermissionRequestCode) {
            val locationIndex = permissions.indexOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
            val isLocationGranted = locationIndex >= 0 && grantResults.isNotEmpty() && grantResults[locationIndex] == PackageManager.PERMISSION_GRANTED
            if (isLocationGranted) {
                writeLog("PERMISSION: Location permission granted")
                // ВАЖНО: НЕ стартуем тренировку автоматически после выдачи разрешения.
                // Тренировку запускает только пользователь кнопкой "Старт".
                Toast.makeText(this, "Разрешение получено. Нажмите «Старт» чтобы начать тренировку.", Toast.LENGTH_SHORT).show()
                startPreStartLocationUpdates() // Начинаем слушать GPS после получения разрешения
            } else {
                writeLog("PERMISSION: Location permission denied")
                Toast.makeText(this, "Необходимо разрешение на доступ к местоположению для отслеживания дистанции", Toast.LENGTH_LONG).show()
                uiGpsStatus = GpsStatus.SEARCHING // Сброс статуса, если разрешение отозвано
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        writeLog("=== App started ===")
        // Разрешения/настройки запрашиваем через продовый чеклист на первом запуске,
        // чтобы не ловить внезапные системные диалоги "в лоб".
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationRequest()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        Log.d("MainActivity", "onCreate called")

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag")

        // Начальные цели (UI)
        val goalsPrefs: SharedPreferences = getSharedPreferences("Goals", MODE_PRIVATE)
        refreshGoalUi(WorkoutGoalStore.load(goalsPrefs))

        // Инициализация TTS с лямбда-коллбэком
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.d("TTS", "TTS инициализирован успешно")
                val locale = Locale("ru")

                if (checkTTSSupportForLanguage(locale)) {
                    textToSpeech.language = locale
                    
                    // Настройка женского голоса
                    setupFemaleVoice()
                } else {
                    writeLog("ERROR: TTS language not available")
                    Log.e("TTS", "Язык недоступен для TTS")
                    Toast.makeText(this, "Язык TTS недоступен", Toast.LENGTH_SHORT).show()
                }
            } else {
                writeLog("ERROR: TTS initialization failed - status=$status")
                Log.e("TTS", "Ошибка инициализации TTS: $status")
                Toast.makeText(this, "Ошибка инициализации TTS. Пожалуйста, проверьте настройки.", Toast.LENGTH_LONG).show()
            }
        }


        // Настройка шагомера
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (stepSensor != null) {
            sensorManager.registerListener(object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (isTimerRunning) {
                        stepCount++
                        // ВРЕМЕННО ОТКЛЮЧЕНО: Тестируем чистый GPS
                        // stepDistance = (stepCount * 0.78f) / 1000 // Средняя длина шага 0.78 м, переводим в км
                        stepDistance = 0.0f
                        uiStepDistanceKm = stepDistance
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            }, stepSensor, SensorManager.SENSOR_DELAY_UI)
        }

        // Compose UI
        setContent {
            StayerTheme {
                val setupPrefs = remember {
                    getSharedPreferences("StayerSetup", MODE_PRIVATE)
                }
                var showSetup by remember {
                    mutableStateOf(!setupPrefs.getBoolean("setup_done", false))
                }

                if (showSetup) {
                    SetupChecklistScreen(
                        onDone = {
                            setupPrefs.edit { putBoolean("setup_done", true) }
                            showSetup = false
                            // После завершения чеклиста — аккуратно запросим критичные разрешения, если всё ещё не выданы
                            checkLocationPermission()
                            checkNotificationPermissionIfNeeded()
                        }
                    )
                } else {
                    MainScreen(
                        appTitle = getString(R.string.app_name),
                        isRunning = uiIsRunning,
                        isPaused = uiIsPaused,
                        elapsedMs = uiElapsedMs,
                        distanceKm = (uiGpsDistanceKm + uiStepDistanceKm),
                        paceText = uiCurrentPaceText,
                        goalValueText = uiGoalValue,
                        goalSupportingText = uiGoalSupporting,
                        onHistoryClick = {
                            writeLog("USER_ACTION: History icon pressed")
                            startActivity(Intent(this, HistoryActivity::class.java))
                        },
                        onAnalyticsClick = {
                            writeLog("USER_ACTION: Analytics icon pressed")
                            startActivity(Intent(this, AnalyticsActivity::class.java))
                        },
                        onSettingsClick = {
                            writeLog("USER_ACTION: Settings icon pressed")
                            startActivity(Intent(this, SettingsActivity::class.java))
                        },
                        onGoalClick = {
                            writeLog("USER_ACTION: Goal tile pressed")
                            startActivity(Intent(this, GoalActivity::class.java))
                        },
                        onPrimaryClick = { handlePrimaryAction() },
                        onStopAndReset = { stopAndResetWorkout() },
                        intervalActive = uiIntervalActive,
                        intervalType = uiIntervalType,
                        intervalRemainingSec = uiIntervalRemainingSec,
                        intervalIndex = uiIntervalIndex,
                        intervalTotal = uiIntervalTotal,
                        intervalTargetPaceSecPerKm = uiIntervalTargetPaceSecPerKm,
                        workoutMode = uiWorkoutMode,
                        scenarioPreview = uiScenarioPreview,
                        gpsStatus = uiGpsStatus
                    )
                }
            }
        }
    }


    @SuppressLint("MissingPermission")
    private fun startPreStartLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {

            if (preStartLocationCallback == null) {
                preStartLocationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: com.google.android.gms.location.LocationResult) {
                        locationResult.lastLocation?.let { location ->
                            val accuracy = location.accuracy // in meters
                            Log.d("GPS_STATUS", "Pre-start GPS accuracy: $accuracy m")
                            uiGpsStatus = when {
                                accuracy <= 10 -> GpsStatus.READY // Good accuracy
                                accuracy <= 30 -> GpsStatus.POOR // Acceptable, but not great
                                else -> GpsStatus.SEARCHING // Poor or no fix
                            }
                        } ?: run {
                            uiGpsStatus = GpsStatus.SEARCHING
                        }
                    }
                }
            }
            fusedLocationClient.requestLocationUpdates(locationRequest, preStartLocationCallback!!, Looper.getMainLooper())
            writeLog("GPS_STATUS: Started pre-start location updates")
        } else {
            uiGpsStatus = GpsStatus.SEARCHING
            writeLog("GPS_STATUS: Cannot start pre-start location updates, permission denied.")
        }
    }

    private fun stopPreStartLocationUpdates() {
        preStartLocationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            preStartLocationCallback = null
            writeLog("GPS_STATUS: Stopped pre-start location updates")
        }
    }

    private fun handlePrimaryAction() {
        if (!isTimerRunning || isPaused) {
            // Запуск тренировки или возобновление после паузы
            writeLog("USER_ACTION: Start pressed - ${if (isPaused) "resuming" else "starting"} workout")
            
            // ВАЖНО: Не полагаемся на локальный pausedTime для определения режима
            // Источник истины - состояние Service (isPaused из broadcast)
            // Service сам управляет totalPausedMs и правильно считает elapsed time
            
            isTimerRunning = true
            isPaused = false
            startWorkoutService() // Service сам разберётся: resume или new start

            // Локальные переменные Activity используются ТОЛЬКО для UI-фолбека
            // Если это возобновление после паузы (ручной или авто), корректируем
            if (pausedTime > 0) {
                totalPausedDuration += System.currentTimeMillis() - pausedTime
                pausedTime = 0
                writeLog("WORKOUT: Resumed after pause, totalPausedDuration=${totalPausedDuration}ms")
            } else if (startTime == 0L) {
                // Первый запуск - инициализируем локальные переменные
                startTime = System.currentTimeMillis()
                totalPausedDuration = 0
                lastPaceCheckDistance = 0f
                writeLog("=== Workout started ===")
                writeLog("Start time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(startTime))}")
            } else {
                // Возобновление после автопаузы: pausedTime=0 но startTime!=0
                // Service сам посчитает правильное elapsed time
                writeLog("WORKOUT: Resuming after autopause (Service manages timing)")
            }
        } else {
            // Пауза
            writeLog("USER_ACTION: Pause pressed")
            isPaused = true
            pausedTime = System.currentTimeMillis()
            WorkoutForegroundService.pause(this)
            timerRunnable?.let { handler.removeCallbacks(it) }
            writeLog("WORKOUT: Paused at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(pausedTime))}")
        }
    }

    private fun stopAndResetWorkout() {
        writeLog("USER_ACTION: Long press - stopping and resetting workout")
        writeLog("WORKOUT_FINISH_REQUESTED")
        
        // НОВАЯ НАДЁЖНАЯ АРХИТЕКТУРА:
        // 1. Service САМ формирует snapshot
        // 2. Service САМ сохраняет snapshot в надёжное хранилище
        // 3. Service САМ сохраняет историю (не зависит от Activity!)
        // 4. Service отправляет snapshot в Activity (для UI)
        // 5. Activity получает snapshot и отправляет ACK
        // 6. Service получает ACK и делает окончательный reset
        // 7. Если ACK не придёт (Activity убита) - Service сделает reset сам через 3 сек
        
        // Отправляем команду сервису
        WorkoutForegroundService.stopAndReset(this@MainActivity)
        
        // Ждём snapshot через finalSnapshotReceiver
        // После получения отправим ACK и сбросим UI
    }

    // Запись в лог-файл
    private fun writeLog(message: String) {
        try {
            val logFile = File(getExternalFilesDir(null), "stayer_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val logMessage = "[$timestamp] $message\n"
            
            FileWriter(logFile, true).use { writer ->
                writer.append(logMessage)
            }
        } catch (e: IOException) {
            Log.e("StayerLog", "Error writing to log file: ${e.message}")
        }
    }
    
    // Очистка лог-файла
    private fun clearLogFile() {
        try {
            val logFile = File(getExternalFilesDir(null), "stayer_log.txt")
            if (logFile.exists()) {
                logFile.delete()
            }
        } catch (e: Exception) {
            Log.e("StayerLog", "Error clearing log file: ${e.message}")
        }
    }







    private fun saveWorkoutHistory(workoutHistory: WorkoutHistory) {
        WorkoutHistoryRepository(this).prepend(workoutHistory)
        Log.d("WorkoutHistory", "Saved workout: ${Gson().toJson(workoutHistory)}")
    }

    private fun setupLocationRequest() {
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(1000)
            .build()
    }

    override fun onPause() {
        super.onPause()
        stopPreStartLocationUpdates()
        // ВАЖНО: не останавливаем трекинг тут.
        // На экране-off Activity уходит в onPause, и раньше это полностью убивало GPS.
    }

    override fun onResume() {
        super.onResume()
        if (!uiIsRunning) {
            startPreStartLocationUpdates()
        }
        val sharedPreferences: SharedPreferences = getSharedPreferences("Goals", MODE_PRIVATE)
        val goal = WorkoutGoalStore.load(sharedPreferences)
        refreshGoalUi(goal)
        val mode = goal.workoutMode
        uiWorkoutMode = mode
        uiScenarioPreview = WorkoutGoalText.buildScenarioPreview(goal)
        writeLog("SCREEN: MainActivity resumed, goalValue=$uiGoalValue, goalSupporting=$uiGoalSupporting, mode=$mode")
    }


    private fun buildIntervalPreview(prefs: SharedPreferences): String {
        val json = prefs.getString("INTERVAL_SCENARIO_JSON", null) ?: return ""
        return try {
            val scenario = Gson().fromJson(json, IntervalScenario::class.java)
            val lines = mutableListOf<String>()
            var workCount = 0
            var workSec = 0
            var restSec = 0
            var workPace: Int? = null
            for (seg in scenario.segments) {
                when (seg.type) {
                    "WARMUP" -> {
                        val pace = seg.targetPaceSecPerKm?.let { fmtPace(it) } ?: ""
                        lines.add("\u0420\u0430\u0437\u043c.  ${fmtTime(seg.durationSec)}  $pace")
                    }
                    "WORK" -> { workCount++; workSec = seg.durationSec; workPace = seg.targetPaceSecPerKm }
                    "REST" -> { restSec = seg.durationSec }
                    "COOLDOWN" -> {
                        val pace = seg.targetPaceSecPerKm?.let { fmtPace(it) } ?: ""
                        lines.add("\u0417\u0430\u043c\u0438\u043d.  ${fmtTime(seg.durationSec)}  $pace")
                    }
                }
            }
            if (workCount > 0) {
                val wp = workPace?.let { "  ${fmtPace(it)}" } ?: ""
                lines.add(lines.size.coerceAtMost(1),
                    "\u0418\u043d\u0442\u0435\u0440\u0432.  ${workCount}\u00d7${fmtTime(workSec)}+${fmtTime(restSec)}$wp")
            }
            val totalSec = scenario.segments.sumOf { it.durationSec }
            lines.add("\u2014\u2014\u2014\u2014\u2014\u2014\u2014\u2014")
            lines.add("\u0418\u0442\u043e\u0433\u043e: \u2248${fmtHms(totalSec)}")
            lines.joinToString("\n")
        } catch (e: Exception) {
            Log.e("Preview", "Error building interval preview", e)
            ""
        }
    }

    private fun buildComboPreview(prefs: SharedPreferences): String {
        val json = prefs.getString("COMBO_SCENARIO_JSON", null) ?: return ""
        return try {
            val scenario = comboGson().fromJson(json, ComboScenario::class.java)
            val lines = scenario.blocks.map { block ->
                when (block) {
                    is ComboBlock.WarmupBlock -> {
                        val pace = block.pace?.let { "  ${fmtPace(it)}" } ?: ""
                        "\u0420\u0430\u0437\u043c.  ${fmtTime(block.durationSec)}$pace"
                    }
                    is ComboBlock.PaceBlock -> {
                        val d = block.distanceKm?.let { String.format("%.1f \u043a\u043c", it) } ?: "?"
                        "\u041e\u0431\u044b\u0447.  $d  ${fmtPace(block.paceSecPerKm)}"
                    }
                    is ComboBlock.IntervalBlock -> {
                        "\u0418\u043d\u0442\u0435\u0440\u0432.  ${block.repeats}\u00d7${fmtTime(block.workSec)}+${fmtTime(block.restSec)}  ${fmtPace(block.workPace)}"
                    }
                    is ComboBlock.CooldownBlock -> {
                        val pace = block.pace?.let { "  ${fmtPace(it)}" } ?: ""
                        "\u0417\u0430\u043c\u0438\u043d.  ${fmtTime(block.durationSec)}$pace"
                    }
                }
            }.toMutableList()
            val totalSec = scenario.estimateTotalTimeSec()
            val totalDist = scenario.estimateTotalDistanceKm()
            lines.add("\u2014\u2014\u2014\u2014\u2014\u2014\u2014\u2014")
            lines.add("\u0418\u0442\u043e\u0433\u043e: \u2248${String.format("%.1f", totalDist)} \u043a\u043c, ${fmtHms(totalSec)}")
            lines.joinToString("\n")
        } catch (e: Exception) {
            Log.e("Preview", "Error building combo preview", e)
            ""
        }
    }

    private fun refreshGoalUi(goal: ActiveWorkoutGoal) {
        uiWorkoutMode = goal.workoutMode
        val display = WorkoutGoalText.buildDisplay(goal)
        uiGoalValue = display.value
        uiGoalSupporting = display.supporting
    }

    private fun refreshGoalUi(prefs: SharedPreferences) {
        val goal = WorkoutGoalStore.load(prefs)
        uiWorkoutMode = goal.workoutMode
        when (goal.workoutMode) {
            1 -> {
                uiGoalValue = "Интервалы"
                uiGoalSupporting = buildIntervalGoalSummary(goal.intervalScenarioJson)
            }
            2 -> {
                uiGoalValue = "Комбо"
                uiGoalSupporting = buildComboGoalSummary(goal.comboScenarioJson)
            }
            else -> {
                uiGoalValue = goal.targetDistanceKm
                    ?.takeIf { it > 0f }
                    ?.let { String.format(Locale.getDefault(), "%.2f км", it) }
                    ?: "—"
                uiGoalSupporting = when {
                    goal.targetTimeSec != null && goal.targetTimeSec > 0 -> fmtHms(goal.targetTimeSec)
                    goal.targetPaceSecPerKm != null && goal.targetPaceSecPerKm > 0 -> fmtPace(goal.targetPaceSecPerKm)
                    else -> null
                }
            }
        }
    }

    private fun buildIntervalGoalSummary(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return try {
            val scenario = Gson().fromJson(json, IntervalScenario::class.java)
            val totalSec = scenario.segments.sumOf { it.durationSec }
            val workCount = scenario.segments.count { it.type == "WORK" }
            "≈${fmtHms(totalSec)} • $workCount сер."
        } catch (_: Exception) {
            null
        }
    }

    private fun buildComboGoalSummary(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return try {
            val scenario = comboGson().fromJson(json, ComboScenario::class.java)
            val totalDist = scenario.estimateTotalDistanceKm()
            val totalSec = scenario.estimateTotalTimeSec()
            "≈${String.format(Locale.getDefault(), "%.1f км", totalDist)} • ${fmtHms(totalSec)}"
        } catch (_: Exception) {
            null
        }
    }

    private fun fmtTime(sec: Int): String {
        val m = sec / 60; val s = sec % 60
        return String.format(Locale.getDefault(), "%d:%02d", m, s)
    }

    private fun fmtPace(secPerKm: Int): String {
        val m = secPerKm / 60; val s = secPerKm % 60
        return String.format(Locale.getDefault(), "%d:%02d/\u043a\u043c", m, s)
    }

    private fun formatCurrentPaceForUi(secPerKm: Int): String {
        if (secPerKm <= 0) return "—"
        val m = secPerKm / 60
        val s = secPerKm % 60
        return String.format(Locale.getDefault(), "%d:%02d /\u043a\u043c", m, s)
    }

    private fun fmtHms(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s) else String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }

    override fun onStart() {
        super.onStart()
        writeLog("ACTIVITY_ON_START: registering receivers")
        if (workoutUpdateReceiver == null) {
            workoutUpdateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != WorkoutForegroundService.ACTION_BROADCAST_UPDATE) return
                    val gpsDistanceKm = intent.getFloatExtra(WorkoutForegroundService.EXTRA_DISTANCE_KM, 0f)
                    val elapsedMs = intent.getLongExtra(WorkoutForegroundService.EXTRA_ELAPSED_MS, 0L)
                    val running = intent.getBooleanExtra(WorkoutForegroundService.EXTRA_IS_RUNNING, false)
                    val paused = intent.getBooleanExtra(WorkoutForegroundService.EXTRA_IS_PAUSED, false)
                    val currentPaceSecPerKm = intent.getIntExtra(
                        WorkoutForegroundService.EXTRA_CURRENT_PACE_SEC_PER_KM,
                        -1
                    )

                    lastElapsedMsFromService = elapsedMs
                    totalDistance = gpsDistanceKm
                    uiGpsDistanceKm = gpsDistanceKm
                    uiElapsedMs = elapsedMs
                    uiCurrentPaceText = formatCurrentPaceForUi(currentPaceSecPerKm)

                    // Состояние кнопки — из сервиса (источник истины)
                    isTimerRunning = running
                    isPaused = paused
                    uiIsRunning = running
                    uiIsPaused = paused
                    
                    // ВАЖНО: НЕ синхронизируем pausedTime здесь
                    // Service сам управляет временем паузы через totalPausedMs
                    // Activity использует pausedTime ТОЛЬКО для UI-логики кнопки Start/Pause
                    // При автопаузе просто отображаем состояние isPaused из Service

                    // interval extras (optional)
                    uiIntervalActive = intent.getBooleanExtra("interval_active", false)
                    uiIntervalType = intent.getStringExtra("interval_type") ?: ""
                    uiIntervalRemainingSec = intent.getIntExtra("interval_remaining_sec", 0)
                    uiIntervalIndex = intent.getIntExtra("interval_index", 0)
                    uiIntervalTotal = intent.getIntExtra("interval_total", 0)
                    uiIntervalTargetPaceSecPerKm =
                        if (intent.hasExtra("interval_target_pace_sec_per_km"))
                            intent.getIntExtra("interval_target_pace_sec_per_km", 0)
                        else null

                }
            }
        }
        
        // Регистрируем receiver для финального snapshot
        if (finalSnapshotReceiver == null) {
            finalSnapshotReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != WorkoutForegroundService.ACTION_BROADCAST_FINAL_SNAPSHOT) return
                    
                    val snapshotJson = intent.getStringExtra(WorkoutForegroundService.EXTRA_SNAPSHOT_JSON)
                    if (snapshotJson.isNullOrBlank()) {
                        writeLog("ERROR: Received empty snapshot JSON")
                        return
                    }
                    
                    try {
                        writeLog("FINAL_SNAPSHOT_RECEIVER: snapshot broadcast received")
                        val snapshot = Gson().fromJson(snapshotJson, WorkoutSummarySnapshot::class.java)
                        writeLog("SNAPSHOT_RECEIVED: distance=${snapshot.distanceKm}km, elapsed=${snapshot.elapsedMs}ms")
                        
                        // Проверяем валидность
                        if (!snapshot.isValid()) {
                            writeLog("ACTIVITY: Invalid snapshot received, but Service already saved it")
                        } else {
                            writeLog("ACTIVITY: Valid snapshot received, Service already saved history")
                        }
                        
                        // ВАЖНО: История уже сохранена Service!
                        // Мы только обновляем UI и отправляем ACK
                        
                        // Отправляем ACK в Service для завершения reset'а
                        writeLog("ACTIVITY: sending SAVE_ACK to Service")
                        WorkoutForegroundService.sendSaveAck(this@MainActivity)
                        writeLog("ACTIVITY: ACK sent to Service")
                        
                        // Сбрасываем UI
                        writeLog("ACTIVITY: resetUIAfterWorkout begin")
                        resetUIAfterWorkout()
                        writeLog("ACTIVITY: resetUIAfterWorkout end")
                        
                        // Опционально: показываем уведомление пользователю
                        if (snapshot.isValid()) {
                            val distStr = String.format(Locale.getDefault(), "%.2f км", snapshot.distanceKm)
                            val seconds = (snapshot.elapsedMs / 1000) % 60
                            val minutes = (snapshot.elapsedMs / (1000 * 60)) % 60
                            val hours = (snapshot.elapsedMs / (1000 * 60 * 60))
                            val timeStr = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
                            Toast.makeText(
                                this@MainActivity,
                                "Тренировка сохранена: $distStr за $timeStr",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        writeLog("ERROR parsing snapshot: ${e.message}")
                        e.printStackTrace()
                        // Даже при ошибке парсинга отправляем ACK и сбрасываем UI
                        writeLog("ACTIVITY: sending SAVE_ACK after snapshot parse failure")
                        WorkoutForegroundService.sendSaveAck(this@MainActivity)
                        writeLog("ACTIVITY: resetUIAfterWorkout begin after parse failure")
                        resetUIAfterWorkout()
                        writeLog("ACTIVITY: resetUIAfterWorkout end after parse failure")
                    }
                }
            }
        }
        
        val updateFilter = IntentFilter(WorkoutForegroundService.ACTION_BROADCAST_UPDATE)
        val snapshotFilter = IntentFilter(WorkoutForegroundService.ACTION_BROADCAST_FINAL_SNAPSHOT)
        
        ContextCompat.registerReceiver(this, workoutUpdateReceiver, updateFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, finalSnapshotReceiver, snapshotFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        writeLog("ACTIVITY_ON_STOP: begin unregister receivers")
        workoutUpdateReceiver?.let {
            try {
                unregisterReceiver(it)
                writeLog("ACTIVITY_ON_STOP: workoutUpdateReceiver unregistered")
            } catch (_: Exception) {
                writeLog("ACTIVITY_ON_STOP: workoutUpdateReceiver unregister skipped")
            }
        }
        finalSnapshotReceiver?.let {
            try {
                unregisterReceiver(it)
                writeLog("ACTIVITY_ON_STOP: finalSnapshotReceiver unregistered")
            } catch (_: Exception) {
                writeLog("ACTIVITY_ON_STOP: finalSnapshotReceiver unregister skipped")
            }
        }
        writeLog("ACTIVITY_ON_STOP: end")
        super.onStop()
    }

    /**
     * Сбрасывает UI после завершения тренировки.
     * Вызывается ПОСЛЕ успешного сохранения snapshot.
     */
    private fun resetUIAfterWorkout() {
        writeLog("UI_RESET_BEGIN")
        isLongPress = true
        isActive = false
        isTimerRunning = false
        isPaused = false
        pausedTime = 0
        totalPausedDuration = 0
        handler.removeCallbacksAndMessages(null)
        timerRunnable?.let { handler.removeCallbacks(it) }

        // Сброс значений для дистанции и шагомера
        totalDistance = 0f
        uiGpsDistanceKm = 0f
        previousLatitude = null
        previousLongitude = null
        stepCount = 0
        stepDistance = 0.0f
        uiStepDistanceKm = 0f
        lastPaceCheckDistance = 0f
        uiElapsedMs = 0L
        uiCurrentPaceText = "—"
        startTime = 0

        writeLog("UI_RESET_DONE")
    }

    override fun onDestroy() {
        super.onDestroy()
        writeLog("ACTIVITY_ON_DESTROY: after super")
        writeLog("ACTIVITY_ON_DESTROY: shutting down MainActivity TTS")
        textToSpeech.shutdown()
        val intent = Intent(this, TTSBackgroundService::class.java).apply {
            action = "STOP"
        }
        writeLog("ACTIVITY_ON_DESTROY: sending STOP to TTSBackgroundService")
        try {
            startService(intent)
            writeLog("ACTIVITY_ON_DESTROY: STOP sent to TTSBackgroundService")
        } catch (e: Exception) {
            writeLog("ACTIVITY_ON_DESTROY: failed to stop TTSBackgroundService: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }



}
