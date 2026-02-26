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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import com.example.stayer.ui.main.MainScreen
import com.example.stayer.ui.main.SetupChecklistScreen
import com.example.stayer.ui.theme.StayerTheme
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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


    // Переменные для шагомера и TextToSpeech
    private lateinit var sensorManager: SensorManager
    private var stepCount = 0
    private var stepDistance: Float = 0.0f
    private lateinit var textToSpeech: TextToSpeech

    // Переменные для управления звуком
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest

    // ==== UI state (Compose) ====
    private var uiElapsedMs by mutableLongStateOf(0L)
    private var uiGpsDistanceKm by mutableFloatStateOf(0f)
    private var uiStepDistanceKm by mutableFloatStateOf(0f)
    private var uiIsRunning by mutableStateOf(false)
    private var uiIsPaused by mutableStateOf(false)
    private var uiTargetDistance by mutableStateOf("0")
    private var uiTargetTime by mutableStateOf("0")

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


    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                locationPermissionRequestCode
            )
        }
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
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                writeLog("PERMISSION: Location permission granted")
                // ВАЖНО: НЕ стартуем тренировку автоматически после выдачи разрешения.
                // Тренировку запускает только пользователь кнопкой "Старт".
                Toast.makeText(this, "Разрешение получено. Нажмите «Старт» чтобы начать тренировку.", Toast.LENGTH_SHORT).show()
            } else {
                writeLog("PERMISSION: Location permission denied")
                Toast.makeText(this, "Необходимо разрешение на доступ к местоположению для отслеживания дистанции", Toast.LENGTH_LONG).show()
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
        uiTargetDistance = goalsPrefs.getString("TARGET_DISTANCE", "0") ?: "0"
        uiTargetTime = goalsPrefs.getString("TARGET_TIME", "0") ?: "0"

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
                        targetDistanceText = uiTargetDistance,
                        targetTimeText = uiTargetTime,
                        onHistoryClick = {
                            writeLog("USER_ACTION: History icon pressed")
                            startActivity(Intent(this, HistoryActivity::class.java))
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
                        onOpenSetup = { showSetup = true },
                        intervalActive = uiIntervalActive,
                        intervalType = uiIntervalType,
                        intervalRemainingSec = uiIntervalRemainingSec,
                        intervalIndex = uiIntervalIndex,
                        intervalTotal = uiIntervalTotal,
                        intervalTargetPaceSecPerKm = uiIntervalTargetPaceSecPerKm,
                        workoutMode = uiWorkoutMode,
                        scenarioPreview = uiScenarioPreview,
                    )
                }
            }
        }
    }

    private fun handlePrimaryAction() {
        if (!isTimerRunning || isPaused) {
            // Запуск тренировки или возобновление после паузы
            writeLog("USER_ACTION: Start pressed - ${if (isPaused) "resuming" else "starting"} workout")
            isTimerRunning = true
            isPaused = false
            WorkoutForegroundService.startOrResume(this)

            // Если это возобновление после паузы, корректируем startTime (фолбек для истории)
            if (pausedTime > 0) {
                totalPausedDuration += System.currentTimeMillis() - pausedTime
                pausedTime = 0
                writeLog("WORKOUT: Resumed after pause, totalPausedDuration=${totalPausedDuration}ms")
            } else {
                // Первый запуск
                startTime = System.currentTimeMillis()
                totalPausedDuration = 0
                lastPaceCheckDistance = 0f
                writeLog("=== Workout started ===")
                writeLog("Start time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(startTime))}")
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
        isLongPress = true
        isActive = false
        isTimerRunning = false
        isPaused = false
        pausedTime = 0
        totalPausedDuration = 0
        handler.removeCallbacksAndMessages(null)
        timerRunnable?.let { handler.removeCallbacks(it) }

        // Останавливаем сервис трекинга
        WorkoutForegroundService.stopAndReset(this@MainActivity)

        // Снимем "снимок" результатов ДО обнуления переменных,
        // иначе сохранение истории будет неверным (особенно при работе в фоне)
        val workoutStartTime = startTime
        val workoutTotalPaused = totalPausedDuration
        val workoutStepDistance = stepDistance
        val workoutElapsedMsFromService = lastElapsedMsFromService
        val workoutGpsDistance = getSharedPreferences("WorkoutRuntime", MODE_PRIVATE)
            .getFloat("CURRENT_DISTANCE_KM", totalDistance)

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
        // Важно: startTime обнулим после запуска сохранения (см. ниже)

        // Очистка лог-файла при сбросе
        clearLogFile()

        // НЕ сбрасываем цели при остановке - они должны сохраняться

        // Используем Coroutine для выполнения длительных операций
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Учитываем паузы — берем время из сервиса (источник истины).
                // Фолбек: если вдруг оно 0, используем старый расчёт.
                val actualElapsedTime = when {
                    workoutElapsedMsFromService > 0L -> workoutElapsedMsFromService
                    workoutStartTime > 0L -> (System.currentTimeMillis() - workoutStartTime - workoutTotalPaused).coerceAtLeast(0L)
                    else -> 0L
                }
                val seconds = (actualElapsedTime / 1000) % 60
                val minutes = (actualElapsedTime / (1000 * 60)) % 60
                val hours = (actualElapsedTime / (1000 * 60 * 60)) % 24
                val timeString = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)

                // Вычисляем общую дистанцию (GPS + шаги)
                val finalTotalDistance = workoutGpsDistance + workoutStepDistance

                // Вычисляем скорость (без учета пауз)
                val speed = if (finalTotalDistance > 0 && actualElapsedTime > 0) {
                    finalTotalDistance / (actualElapsedTime / 3600000.0f)
                } else 0f // км/ч

                // Получаем текущую дату
                val currentDate = SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date())

                // Считываем сохранённую статистику интервалов (если есть)
                val runtimePrefs = getSharedPreferences("WorkoutRuntime", MODE_PRIVATE)
                val avgWork = runtimePrefs.getInt("INTERVAL_AVG_WORK", -1).takeIf { it > 0 }
                val avgRest = runtimePrefs.getInt("INTERVAL_AVG_REST", -1).takeIf { it > 0 }
                val avgNoWarmup = runtimePrefs.getInt("INTERVAL_AVG_NO_WARMUP", -1).takeIf { it > 0 }
                val avgTotal = runtimePrefs.getInt("INTERVAL_AVG_TOTAL", -1).takeIf { it > 0 }

                // Определяем режим: если есть статистика, считаем интервальной
                val mode = if (avgWork != null || avgRest != null) "interval" else "normal"

                // Создаем запись в истории
                val workoutHistory = WorkoutHistory(
                    date = currentDate,
                    distance = finalTotalDistance,
                    time = timeString,
                    speed = speed,
                    elapsedMs = actualElapsedTime,
                    workoutMode = mode,
                    avgPaceWorkSec = avgWork,
                    avgPaceRestSec = avgRest,
                    avgPaceWithoutWarmupSec = avgNoWarmup,
                    avgPaceTotalSec = avgTotal
                )

                // Сохраняем данные о тренировке
                writeLog("WORKOUT_END: mode=$mode, distance=${String.format(Locale.getDefault(), "%.3f", finalTotalDistance)}km, time=$timeString")
                saveWorkoutHistory(workoutHistory)
                writeLog("=== Workout saved ===")

                // Очищаем временную статистику интервалов
                runtimePrefs.edit().clear().apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Теперь можно обнулять startTime
        startTime = 0
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
        // Загружаем существующий список тренировок
        val sharedPreferences = getSharedPreferences("WorkoutHistory", MODE_PRIVATE)
        val existingJson = sharedPreferences.getString("workoutHistoryList", null)
        
        val workoutList = if (existingJson != null) {
            try {
                val type = object : TypeToken<List<WorkoutHistory>>() {}.type
                Gson().fromJson<List<WorkoutHistory>>(existingJson, type).toMutableList()
            } catch (e: Exception) {
                Log.e("WorkoutHistory", "Error loading history: ${e.message}")
                mutableListOf()
            }
        } else {
            mutableListOf()
        }
        
        // Добавляем новую тренировку в начало списка
        workoutList.add(0, workoutHistory)
        
        // Сохраняем обновленный список
        sharedPreferences.edit {
            putString("workoutHistoryList", Gson().toJson(workoutList))
        }
        Log.d("WorkoutHistory", "Saved workout: ${Gson().toJson(workoutHistory)}")
    }

    private fun setupLocationRequest() {
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(1000)
            .build()
    }

    override fun onPause() {
        super.onPause()
        // ВАЖНО: не останавливаем трекинг тут.
        // На экране-off Activity уходит в onPause, и раньше это полностью убивало GPS.
    }

    override fun onResume() {
        super.onResume()
        val sharedPreferences: SharedPreferences = getSharedPreferences("Goals", MODE_PRIVATE)
        val distance = sharedPreferences.getString("TARGET_DISTANCE", "0")
        val time = sharedPreferences.getString("TARGET_TIME", "0")
        uiTargetDistance = distance ?: "0"
        uiTargetTime = time ?: "0"

        // Read workout mode & build scenario preview
        val mode = sharedPreferences.getInt("WORKOUT_MODE", 0)
        uiWorkoutMode = mode
        uiScenarioPreview = when (mode) {
            1 -> buildIntervalPreview(sharedPreferences)
            2 -> buildComboPreview(sharedPreferences)
            else -> ""
        }
        writeLog("SCREEN: MainActivity resumed, goals: distance=$distance, time=$time, mode=$mode")
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
            var restPace: Int? = null
            for (seg in scenario.segments) {
                when (seg.type) {
                    "WARMUP" -> {
                        val pace = seg.targetPaceSecPerKm?.let { fmtPace(it) } ?: ""
                        lines.add("\u0420\u0430\u0437\u043c.  ${fmtTime(seg.durationSec)}  $pace")
                    }
                    "WORK" -> { workCount++; workSec = seg.durationSec; workPace = seg.targetPaceSecPerKm }
                    "REST" -> { restSec = seg.durationSec; restPace = seg.targetPaceSecPerKm }
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

    private fun fmtTime(sec: Int): String {
        val m = sec / 60; val s = sec % 60
        return "%d:%02d".format(m, s)
    }

    private fun fmtPace(secPerKm: Int): String {
        val m = secPerKm / 60; val s = secPerKm % 60
        return "%d:%02d/\u043a\u043c".format(m, s)
    }

    private fun fmtHms(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    override fun onStart() {
        super.onStart()
        if (workoutUpdateReceiver == null) {
            workoutUpdateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != WorkoutForegroundService.ACTION_BROADCAST_UPDATE) return
                    val gpsDistanceKm = intent.getFloatExtra(WorkoutForegroundService.EXTRA_DISTANCE_KM, 0f)
                    val elapsedMs = intent.getLongExtra(WorkoutForegroundService.EXTRA_ELAPSED_MS, 0L)
                    val running = intent.getBooleanExtra(WorkoutForegroundService.EXTRA_IS_RUNNING, false)
                    val paused = intent.getBooleanExtra(WorkoutForegroundService.EXTRA_IS_PAUSED, false)

                    lastElapsedMsFromService = elapsedMs
                    totalDistance = gpsDistanceKm
                    uiGpsDistanceKm = gpsDistanceKm
                    uiElapsedMs = elapsedMs

                    // Состояние кнопки — тоже из сервиса, чтобы не было рассинхрона
                    isTimerRunning = running
                    isPaused = paused
                    uiIsRunning = running
                    uiIsPaused = paused

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
        val filter = IntentFilter(WorkoutForegroundService.ACTION_BROADCAST_UPDATE)
        // Один код-путь для всех API, плюс lint перестаёт ругаться на "missing flag"
        ContextCompat.registerReceiver(this, workoutUpdateReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        workoutUpdateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        textToSpeech.shutdown()
        val intent = Intent(this, TTSBackgroundService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
    }



}
