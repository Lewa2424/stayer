package com.example.stayer.debug

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.stayer.ComboScenario
import com.example.stayer.IntervalScenario
import com.example.stayer.comboGson
import com.example.stayer.flatten
import com.example.stayer.ui.theme.StayerTheme
import com.google.gson.Gson
import java.util.Locale

class PacerTestActivity : ComponentActivity() {

    private lateinit var ttsHelper: PacerTestTtsHelper
    private var engine: PacerSimulationEngine? = null
    private var intervalEngine: IntervalSimulationEngine? = null
    private var comboEngine: ComboSimulationEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        ttsHelper = PacerTestTtsHelper(this)
        
        val goals = getSharedPreferences("Goals", Context.MODE_PRIVATE)
        val mode = goals.getInt("WORKOUT_MODE", 0) // 0 = normal, 1 = interval
        
        val targetDistanceStr = goals.getString("TARGET_DISTANCE", "0") ?: "0"
        val targetDistanceKm = targetDistanceStr.replace(',', '.').toDoubleOrNull() ?: 0.0
        
        val targetTimeSec = goals.getInt("TARGET_TIME_SEC", 0)
        
        val targetPaceSecPerKm = if (targetDistanceKm > 0) targetTimeSec / targetDistanceKm.toFloat() else 0f
        val targetSpeedKmh = if (targetPaceSecPerKm > 0) 3600.0 / targetPaceSecPerKm else 8.0
        
        if (mode == 0 && targetDistanceKm > 0 && targetPaceSecPerKm > 0) {
            engine = PacerSimulationEngine(targetDistanceKm, targetPaceSecPerKm, ttsHelper, lifecycleScope)
            engine?.simulationSpeedKmh = targetSpeedKmh
            engine?.timeMultiplier = 5
        }

        // Interval mode
        var intervalScenario: IntervalScenario? = null
        if (mode == 1) {
            val json = goals.getString("INTERVAL_SCENARIO_JSON", null)
            if (!json.isNullOrBlank()) {
                try {
                    intervalScenario = Gson().fromJson(json, IntervalScenario::class.java)
                } catch (_: Exception) {}
            }
            if (intervalScenario != null && intervalScenario.segments.isNotEmpty()) {
                intervalEngine = IntervalSimulationEngine(intervalScenario, ttsHelper, lifecycleScope)
                intervalEngine?.simulationSpeedKmh = 8.0
                intervalEngine?.timeMultiplier = 5
            }
        }

        // Combo mode
        var comboScenario: ComboScenario? = null
        if (mode == 2) {
            val json = goals.getString("COMBO_SCENARIO_JSON", null)
            if (!json.isNullOrBlank()) {
                try {
                    comboScenario = comboGson().fromJson(json, ComboScenario::class.java)
                } catch (_: Exception) {}
            }
            if (comboScenario != null && comboScenario.blocks.isNotEmpty()) {
                val flattened = IntervalScenario(comboScenario.flatten())
                comboEngine = ComboSimulationEngine(flattened, ttsHelper, lifecycleScope)
                comboEngine?.simulationSpeedKmh = 8.0
                comboEngine?.timeMultiplier = 5
            }
        }

        val targetTimeStr = String.format(Locale.getDefault(), "%02d:%02d:%02d", targetTimeSec / 3600, (targetTimeSec % 3600) / 60, targetTimeSec % 60)

        setContent {
            StayerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when {
                        mode == 0 && engine != null -> {
                            val targetPaceStr = String.format(Locale.getDefault(), "%d:%02d/км", targetPaceSecPerKm.toInt() / 60, targetPaceSecPerKm.toInt() % 60)
                            TestEngineScreen(engine!!, targetDistanceKm, targetTimeStr, targetSpeedKmh, targetPaceStr)
                        }
                        mode == 1 && intervalEngine != null -> {
                            IntervalTestScreen(intervalEngine!!, intervalScenario!!)
                        }
                        mode == 2 && comboEngine != null -> {
                            ComboTestScreen(comboEngine!!, comboScenario!!)
                        }
                        mode == 2 -> {
                            ErrorScreen("Сначала задайте комбинированный сценарий на экране целей.")
                        }
                        mode == 1 -> {
                            ErrorScreen("Сначала задайте интервальный сценарий на экране целей.")
                        }
                        mode == 0 -> {
                            ErrorScreen("Сначала задайте дистанцию и время/темп на экране целей.")
                        }
                        else -> {
                            ErrorScreen("Тест пейсера пока не поддерживает этот режим тренировки.")
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.stop()
        intervalEngine?.stop()
        comboEngine?.stop()
        ttsHelper.release()
    }
}

@Composable
fun ErrorScreen(message: String) {
    val activity = LocalContext.current as? ComponentActivity
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { activity?.finish() }) {
            Text("Назад")
        }
    }
}

@Composable
fun TestEngineScreen(engine: PacerSimulationEngine, targetDistanceKm: Double, targetTimeStr: String, targetSpeed: Double, targetPaceStr: String) {
    val state by engine.state.collectAsState()
    
    var currentSimSpeed by remember { mutableStateOf(engine.simulationSpeedKmh) }
    var timeMult by remember { mutableStateOf(engine.timeMultiplier) }

    LaunchedEffect(currentSimSpeed) { engine.simulationSpeedKmh = currentSimSpeed }
    LaunchedEffect(timeMult) { engine.timeMultiplier = timeMult }

    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState)) {
        Text("Тест пейсера (симуляция)", style = MaterialTheme.typography.titleLarge)
        Text("Без GPS. Тренировка не сохраняется.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        
        Spacer(Modifier.height(16.dp))
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Цель тренировки", style = MaterialTheme.typography.titleMedium)
                Text("Дистанция: $targetDistanceKm км")
                Text("Время: $targetTimeStr")
                Text("Целевой темп: $targetPaceStr")
                Text(String.format(Locale.getDefault(), "Целевая скорость: %.1f км/ч", targetSpeed))
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        Text("Параметры симуляции", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Ускорение времени:")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            listOf(1, 5, 10).forEach { mul ->
                OutlinedButton(
                    onClick = { timeMult = mul },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (timeMult == mul) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text("x$mul")
                }
            }
        }
        
        Spacer(Modifier.height(8.dp))
        Text(String.format(Locale.getDefault(), "Скорость: %.1f км/ч", currentSimSpeed))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { currentSimSpeed = (currentSimSpeed - 0.1).coerceAtLeast(1.0) }) { Text("-0.1") }
            Button(onClick = { currentSimSpeed = (currentSimSpeed + 0.1).coerceAtMost(30.0) }) { Text("+0.1") }
            Button(onClick = { currentSimSpeed = targetSpeed }) { Text("К цели") }
        }
        
        Spacer(Modifier.height(16.dp))
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Статус: ${state.status}", style = MaterialTheme.typography.titleMedium)
                Text(String.format(Locale.getDefault(), "Пройдено: %.2f км", state.distanceKm))
                Text(String.format(Locale.getDefault(), "Время: %d:%02d:%02d", state.elapsedSec / 3600, (state.elapsedSec % 3600) / 60, state.elapsedSec % 60))
                Text(String.format(Locale.getDefault(), "Текущий темп: %d:%02d/км", state.currentPaceSecPerKm / 60, state.currentPaceSecPerKm % 60))
                Text(String.format(Locale.getDefault(), "Текущая скорость: %.1f км/ч", state.currentSpeedKmh))
                if (state.segmentPaceSecPerKm > 0) {
                    Text(String.format(Locale.getDefault(), "Темп сегмента: %d:%02d/км", state.segmentPaceSecPerKm / 60, state.segmentPaceSecPerKm % 60))
                }
                Text(String.format(Locale.getDefault(), "След. чекпоинт: %.2f км", state.nextCheckpointKm))
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { engine.startOrResume() }, modifier = Modifier.weight(1f)) { Text("Старт") }
            Button(onClick = { engine.pause() }, modifier = Modifier.weight(1f)) { Text("Пауза") }
            Button(onClick = { engine.stop() }, modifier = Modifier.weight(1f)) { Text("Стоп") }
        }
        
        Spacer(Modifier.height(16.dp))
        Text("Последняя подсказка:", style = MaterialTheme.typography.titleMedium)
        Text(state.lastPrompt.ifEmpty { "—" })
        if (state.lastPromptDeviation.isNotEmpty()) {
            Text(state.lastPromptDeviation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ─── Interval Test Screen ─────────────────────────────────────────────────────

@Composable
fun IntervalTestScreen(engine: IntervalSimulationEngine, scenario: IntervalScenario) {
    val state by engine.state.collectAsState()

    var currentSimSpeed by remember { mutableStateOf(engine.simulationSpeedKmh) }
    var timeMult by remember { mutableStateOf(engine.timeMultiplier) }

    LaunchedEffect(currentSimSpeed) { engine.simulationSpeedKmh = currentSimSpeed }
    LaunchedEffect(timeMult) { engine.timeMultiplier = timeMult }

    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState)) {
        Text("Тест интервалов (симуляция)", style = MaterialTheme.typography.titleLarge)
        Text("Без GPS. Тренировка не сохраняется.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(16.dp))

        // Scenario overview card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Сценарий: ${scenario.segments.size} сегментов", style = MaterialTheme.typography.titleMedium)
                scenario.segments.forEachIndexed { idx, seg ->
                    val label = when (seg.type) {
                        "WARMUP" -> "Разминка"
                        "WORK" -> "Работа"
                        "REST" -> "Отдых"
                        "COOLDOWN" -> "Заминка"
                        else -> seg.type
                    }
                    val dur = seg.durationSec
                    val durText = if (dur >= 60) "${dur / 60}м${if (dur % 60 > 0) " ${dur % 60}с" else ""}" else "${dur}с"
                    val paceText = seg.targetPaceSecPerKm?.let { "${it / 60}:${String.format(Locale.getDefault(), "%02d", it % 60)}/км" } ?: "—"
                    val isCurrent = (idx + 1) == state.segmentIndex
                    Text(
                        "${idx + 1}. $label $durText ($paceText)${if (isCurrent) " ◀" else ""}",
                        style = if (isCurrent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Controls
        Text("Параметры симуляции", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Ускорение времени:")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            listOf(1, 5, 10).forEach { mul ->
                OutlinedButton(
                    onClick = { timeMult = mul },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (timeMult == mul) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text("x$mul")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(String.format(Locale.getDefault(), "Скорость: %.1f км/ч", currentSimSpeed))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { currentSimSpeed = (currentSimSpeed - 0.1).coerceAtLeast(1.0) }) { Text("-0.1") }
            Button(onClick = { currentSimSpeed = (currentSimSpeed + 0.1).coerceAtMost(30.0) }) { Text("+0.1") }
        }

        Spacer(Modifier.height(16.dp))

        // Status card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                val segLabel = when (state.segmentType) {
                    "WARMUP" -> "Разминка"
                    "WORK" -> "Работа"
                    "REST" -> "Отдых"
                    "COOLDOWN" -> "Заминка"
                    else -> state.segmentType
                }
                Text(
                    "${segLabel} (${state.segmentIndex}/${state.segmentTotal})",
                    style = MaterialTheme.typography.titleMedium
                )
                val remMin = state.segmentRemainingSec / 60
                val remSec = state.segmentRemainingSec % 60
                Text("Осталось: ${remMin}:${String.format(Locale.getDefault(), "%02d", remSec)}")

                Spacer(Modifier.height(8.dp))

                // Target pace + target speed
                val tp = state.segmentTargetPace
                if (tp != null) {
                    val targetSpeedKmh = 3600.0 / tp
                    Text("Целевой темп: ${tp / 60}:${String.format(Locale.getDefault(), "%02d", tp % 60)}/км")
                    Text(String.format(Locale.getDefault(), "Целевая скорость: %.1f км/ч", targetSpeedKmh))
                } else {
                    Text("Целевой темп: —")
                }

                Spacer(Modifier.height(8.dp))

                // Current pace + speed
                Text(String.format(Locale.getDefault(), "Текущий темп: %d:%02d/км", state.currentPaceSecPerKm / 60, state.currentPaceSecPerKm % 60))
                Text(String.format(Locale.getDefault(), "Текущая скорость: %.1f км/ч", state.currentSpeedKmh))

                // Pace difference
                if (tp != null && state.currentPaceSecPerKm > 0) {
                    val diff = state.currentPaceSecPerKm - tp  // positive = slower
                    val diffAbs = kotlin.math.abs(diff)
                    val diffText = "${diffAbs / 60}:${String.format(Locale.getDefault(), "%02d", diffAbs % 60)}"
                    val sign = when {
                        diff > 5 -> "▼ медленнее на $diffText"
                        diff < -5 -> "▲ быстрее на $diffText"
                        else -> "✓ в коридоре"
                    }
                    Text(
                        sign,
                        color = when {
                            diff > 15 -> MaterialTheme.colorScheme.error
                            diff < -15 -> MaterialTheme.colorScheme.error
                            kotlin.math.abs(diff) <= 5 -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(String.format(Locale.getDefault(), "Пройдено: %.2f км", state.distanceKm))
                Text(String.format(Locale.getDefault(), "Общее время: %d:%02d", state.elapsedSec / 60, state.elapsedSec % 60))
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { engine.startOrResume() }, modifier = Modifier.weight(1f)) { Text("Старт") }
            Button(onClick = { engine.pause() }, modifier = Modifier.weight(1f)) { Text("Пауза") }
            Button(onClick = { engine.stop() }, modifier = Modifier.weight(1f)) { Text("Стоп") }
        }

        Spacer(Modifier.height(16.dp))

        // Prompt log
        Text("Лог озвучки:", style = MaterialTheme.typography.titleMedium)
        if (state.promptLog.isEmpty()) {
            Text("—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.promptLog.asReversed().forEachIndexed { idx, prompt ->
                Text(
                    prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (idx == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── Combo Test Screen ────────────────────────────────────────────────────────
@Composable
fun ComboTestScreen(engine: ComboSimulationEngine, combo: ComboScenario) {
    val state by engine.state.collectAsState()
    val segments = remember(combo) { combo.flatten() }

    var currentSimSpeed by remember { mutableStateOf(engine.simulationSpeedKmh) }
    var timeMult by remember { mutableStateOf(engine.timeMultiplier) }

    LaunchedEffect(currentSimSpeed) { engine.simulationSpeedKmh = currentSimSpeed }
    LaunchedEffect(timeMult) { engine.timeMultiplier = timeMult }

    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState)) {
        Text("Тест комбинированной тренировки", style = MaterialTheme.typography.titleLarge)
        Text("Без GPS. Блоков: ${combo.blocks.size}, Сегментов: ${segments.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(16.dp))

        // Scenario overview card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Список сегментов", style = MaterialTheme.typography.titleMedium)
                segments.forEachIndexed { idx, seg ->
                    val label = when (seg.type) {
                        "WARMUP" -> "Разминка"
                        "WORK" -> "Работа"
                        "REST" -> "Отдых"
                        "COOLDOWN" -> "Заминка"
                        "PACE" -> "Свободный бег"
                        else -> seg.type
                    }
                    val detail = if (seg.type == "PACE" && seg.distanceKm != null) {
                        "${seg.distanceKm} км"
                    } else {
                        val dur = seg.durationSec
                        if (dur >= 60) "${dur / 60}м${if (dur % 60 > 0) " ${dur % 60}с" else ""}" else "${dur}с"
                    }
                    val paceText = seg.targetPaceSecPerKm?.let { "${it / 60}:${String.format(Locale.getDefault(), "%02d", it % 60)}/км" } ?: "—"
                    val isCurrent = (idx + 1) == state.segmentIndex
                    Text(
                        text = "${idx + 1}. $label $detail ($paceText)${if (isCurrent) " ◀" else ""}",
                        style = if (isCurrent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Controls
        Text("Параметры симуляции", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Ускорение времени:")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            listOf(1, 5, 10, 20).forEach { mul ->
                OutlinedButton(
                    onClick = { timeMult = mul },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (timeMult == mul) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text("x$mul")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(String.format(Locale.getDefault(), "Скорость: %.1f км/ч", currentSimSpeed))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { currentSimSpeed = (currentSimSpeed - 0.5).coerceAtLeast(1.0) }) { Text("-0.5") }
            Button(onClick = { currentSimSpeed = (currentSimSpeed + 0.5).coerceAtMost(30.0) }) { Text("+0.5") }
        }

        Spacer(Modifier.height(16.dp))

        // Status card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                val segLabel = when (state.segmentType) {
                    "WARMUP" -> "Разминка"
                    "WORK" -> "Работа"
                    "REST" -> "Отдых"
                    "COOLDOWN" -> "Заминка"
                    "PACE" -> "Свободный бег"
                    else -> state.segmentType
                }
                Text(
                    "${segLabel} (${state.segmentIndex}/${state.segmentTotal})",
                    style = MaterialTheme.typography.titleMedium
                )
                
                if (state.segmentType == "PACE") {
                    Text("Осталось примерно: ~${state.segmentRemainingSec / 60}м ${state.segmentRemainingSec % 60}с (по текущей скорости)")
                } else {
                    val remMin = state.segmentRemainingSec / 60
                    val remSec = state.segmentRemainingSec % 60
                    Text("Осталось: ${remMin}:${String.format(Locale.getDefault(), "%02d", remSec)}")
                }

                Spacer(Modifier.height(8.dp))

                // Target pace + target speed
                val tp = state.segmentTargetPace
                if (tp != null) {
                    val targetSpeedKmh = 3600.0 / tp
                    Text(text = "Целевой темп: ${tp / 60}:${String.format(Locale.getDefault(), "%02d", tp % 60)}/км (%.1f км/ч)".format(targetSpeedKmh))
                } else {
                    Text(text = "Целевой темп: нет")
                }

                Text(text = "Дистанция: %.2f км".format(state.distanceKm))
                val eh = state.elapsedSec / 3600
                val em = (state.elapsedSec % 3600) / 60
                val es = state.elapsedSec % 60
                Text(text = "Время: %02d:%02d:%02d".format(eh, em, es))

                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(onClick = { engine.startOrResume() }, enabled = state.status != PacerTestStatus.RUNNING) {
                        Text("Старт")
                    }
                    Button(onClick = { engine.pause() }, enabled = state.status == PacerTestStatus.RUNNING) {
                        Text("Пауза")
                    }
                    Button(onClick = { engine.stop() }) {
                        Text("Сброс")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Prompt log
        Text("Лог озвучки:", style = MaterialTheme.typography.titleMedium)
        if (state.promptLog.isEmpty()) {
            Text("—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.promptLog.asReversed().forEachIndexed { idx, prompt ->
                Text(
                    prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (idx == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(Modifier.height(32.dp))
    }
}
