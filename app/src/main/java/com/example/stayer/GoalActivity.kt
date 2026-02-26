package com.example.stayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class Segment(
    val type: String,          // "WARMUP","WORK","REST","COOLDOWN","PACE"
    val durationSec: Int,
    val distanceKm: Double? = null,  // for PACE segments (distance-based transition)
    val targetPaceSecPerKm: Int? // null = без контроля
)

data class IntervalScenario(
    val segments: List<Segment>
)

class GoalActivity : AppCompatActivity() {
    private lateinit var distanceInput: EditText
    private lateinit var timeInput: EditText
    private lateinit var confirmDistanceButton: Button
    private lateinit var confirmTimeButton: Button
    private lateinit var backButton: Button

    companion object {
        private const val PREFS_GOALS = "Goals"
        
        private const val WORKOUT_MODE = "WORKOUT_MODE" // 0=normal, 1=interval, 2=combo
        
        // Normal goal mode
        private const val NORMAL_GOAL_MODE = "NORMAL_GOAL_MODE" // 0=time, 1=pace
        
        private const val TARGET_DISTANCE_KM = "TARGET_DISTANCE_KM" // Float
        private const val TARGET_TIME_SEC = "TARGET_TIME_SEC" // Int
        private const val TARGET_PACE_SEC_PER_KM = "TARGET_PACE_SEC_PER_KM" // Int
        
        // Interval scenario stored as JSON
        private const val INTERVAL_SCENARIO_JSON = "INTERVAL_SCENARIO_JSON"
        private const val COMBO_SCENARIO_JSON = "COMBO_SCENARIO_JSON"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_goal)

        // Инициализация элементов
        distanceInput = findViewById(R.id.distanceInput)
        timeInput = findViewById(R.id.timeInput)
        confirmDistanceButton = findViewById(R.id.confirmDistanceButton)
        confirmTimeButton = findViewById(R.id.confirmTimeButton)
        backButton = findViewById(R.id.backButton)

        // Анимированный градиентный текст для "Режим тренировки"
        val cvWorkoutModeLabel = findViewById<ComposeView>(R.id.cvWorkoutModeLabel)
        cvWorkoutModeLabel.setContent {
            val primaryPurple = Color(0xFF6E4BAE)
            val accentOrange = Color(0xFFFF8600)
            
            val infiniteTransition = rememberInfiniteTransition(label = "GradientText")
            val offset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1000f,
                animationSpec = infiniteRepeatable(
                    animation = tween(3000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "gradient_offset"
            )

            val brush = Brush.linearGradient(
                colors = listOf(primaryPurple, accentOrange, primaryPurple),
                start = Offset(offset, offset),
                end = Offset(offset + 500f, offset + 500f),
                tileMode = TileMode.Repeated
            )

            Text(
                text = "Режим тренировки",
                style = TextStyle(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    brush = brush
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Настройка Spinner для режима тренировки
        val prefs = getSharedPreferences("Goals", MODE_PRIVATE)
        val spWorkoutMode = findViewById<Spinner>(R.id.spWorkoutMode)

        // Ссылки на блоки режимов
        val blockNormal = findViewById<View>(R.id.blockNormal)
        val blockInterval = findViewById<View>(R.id.blockInterval)
        val blockCombo = findViewById<View>(R.id.blockCombo)

        // Функция для применения режима
        fun applyMode(mode: Int) {
            blockNormal.visibility = if (mode == 0) View.VISIBLE else View.GONE
            blockInterval.visibility = if (mode == 1) View.VISIBLE else View.GONE
            blockCombo.visibility = if (mode == 2) View.VISIBLE else View.GONE
        }

        // Восстановить сохранённый выбор
        val savedMode = prefs.getInt(WORKOUT_MODE, 0)
        spWorkoutMode.setSelection(savedMode)
        applyMode(savedMode)

        // Сохранять при изменении
        spWorkoutMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                prefs.edit().putInt(WORKOUT_MODE, position).apply()
                applyMode(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // === Логика для Normal mode: время/темп ===
        val rgNormalGoalMode = findViewById<RadioGroup>(R.id.rgNormalGoalMode)
        val blockGoalTime = findViewById<View>(R.id.blockGoalTime)
        val blockGoalPace = findViewById<View>(R.id.blockGoalPace)
        val tvNormalDerived = findViewById<TextView>(R.id.tvNormalDerived)
        val etTargetPace = findViewById<EditText>(R.id.etTargetPace)
        val btnConfirmPace = findViewById<Button>(R.id.btnConfirmPace)

        fun applyNormalGoalMode(mode: Int) {
            // 0 = time, 1 = pace
            blockGoalTime.visibility = if (mode == 0) View.VISIBLE else View.GONE
            blockGoalPace.visibility = if (mode == 1) View.VISIBLE else View.GONE
            prefs.edit().putInt(NORMAL_GOAL_MODE, mode).apply()
        }

        val savedGoalMode = prefs.getInt(NORMAL_GOAL_MODE, 0)
        if (savedGoalMode == 0) {
            rgNormalGoalMode.check(R.id.rbGoalTime)
        } else {
            rgNormalGoalMode.check(R.id.rbGoalPace)
        }
        applyNormalGoalMode(savedGoalMode)

        rgNormalGoalMode.setOnCheckedChangeListener { _, checkedId ->
            applyNormalGoalMode(if (checkedId == R.id.rbGoalPace) 1 else 0)
        }

        // Обработчик нажатия на кнопку подтверждения дистанции
        confirmDistanceButton.setOnClickListener {
            val distance = distanceInput.text.toString()
            if (distance.isNotEmpty()) {
                // Сохраняем дистанцию в SharedPreferences
                getSharedPreferences("Goals", MODE_PRIVATE).edit {
                    putString("TARGET_DISTANCE", distance)
                }
                Toast.makeText(this, "Дистанция сохранена", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Введите целевую дистанцию", Toast.LENGTH_SHORT).show()
            }
        }

        // === Обработчик подтверждения ВРЕМЕНИ (расчет темпа) ===
        confirmTimeButton.setOnClickListener {
            val dist = distanceInput.text.toString().trim().replace(',', '.').toDoubleOrNull()
            val timeSec = parseTimeToSec(timeInput.text.toString())

            if (dist == null || dist <= 0.0) {
                tvNormalDerived.text = "Ошибка: введите дистанцию"
                return@setOnClickListener
            }
            if (timeSec == null || timeSec <= 0) {
                tvNormalDerived.text = "Ошибка: неверный формат времени"
                return@setOnClickListener
            }

            val paceSec = (timeSec.toDouble() / dist).toInt()

            prefs.edit()
                .putFloat(TARGET_DISTANCE_KM, dist.toFloat())
                .putInt(TARGET_TIME_SEC, timeSec)
                .putInt(TARGET_PACE_SEC_PER_KM, paceSec)
                .putInt(NORMAL_GOAL_MODE, 0)
                .apply()

            tvNormalDerived.text = "✓ Сохранено. Расчётный темп: ${formatPace(paceSec)}/км"
            Toast.makeText(this, "Цель сохранена", Toast.LENGTH_SHORT).show()
        }

        // === Обработчик подтверждения ТЕМПА (расчет времени) ===
        btnConfirmPace.setOnClickListener {
            val dist = distanceInput.text.toString().trim().replace(',', '.').toDoubleOrNull()
            val paceSec = parsePaceToSecPerKm(etTargetPace.text.toString())

            if (dist == null || dist <= 0.0) {
                tvNormalDerived.text = "Ошибка: введите дистанцию"
                return@setOnClickListener
            }
            if (paceSec == null || paceSec <= 0) {
                tvNormalDerived.text = "Ошибка: неверный формат темпа (MM:SS)"
                return@setOnClickListener
            }

            val timeSec = (paceSec.toDouble() * dist).toInt()

            prefs.edit()
                .putFloat(TARGET_DISTANCE_KM, dist.toFloat())
                .putInt(TARGET_TIME_SEC, timeSec)
                .putInt(TARGET_PACE_SEC_PER_KM, paceSec)
                .putInt(NORMAL_GOAL_MODE, 1)
                .apply()

            tvNormalDerived.text = "✓ Сохранено. Расчётное время: ${formatTime(timeSec)}"
            Toast.makeText(this, "Цель сохранена", Toast.LENGTH_SHORT).show()
        }
        // === Логика для Interval mode ===
        val btnSaveInterval = findViewById<Button>(R.id.btnSaveInterval)
        val tvIntervalSummary = findViewById<TextView>(R.id.tvIntervalSummary)
        val gson = Gson()

        btnSaveInterval.setOnClickListener {
            val scenario = buildIntervalScenarioFromUi()
            if (scenario == null) {
                tvIntervalSummary.text = "Ошибка: проверь поля (время/темп/повторы)."
                tvIntervalSummary.setTextColor(0xFFF44336.toInt()) // red
                return@setOnClickListener
            }

            val json = gson.toJson(scenario)
            prefs.edit()
                .putInt(WORKOUT_MODE, 1)
                .putString(INTERVAL_SCENARIO_JSON, json)
                .apply()

            val totalSec = scenario.segments.sumOf { it.durationSec }
            tvIntervalSummary.setTextColor(0xFF4CAF50.toInt()) // green
            tvIntervalSummary.text = "✓ Сохранено. Сегментов: ${scenario.segments.size}, длительность: ${formatTime(totalSec)}"
            Toast.makeText(this, "Интервальная сохранена", Toast.LENGTH_SHORT).show()
        }

        // === Логика для Combo mode ===
        val rvComboBlocks = findViewById<RecyclerView>(R.id.rvComboBlocks)
        val spComboBlockType = findViewById<Spinner>(R.id.spComboBlockType)
        val btnAddComboBlock = findViewById<Button>(R.id.btnAddComboBlock)
        val btnSaveCombo = findViewById<Button>(R.id.btnSaveCombo)
        val tvComboSummary = findViewById<TextView>(R.id.tvComboSummary)

        val comboBlocks = mutableListOf<ComboBlock>()
        val comboAdapter = ComboBlockAdapter(comboBlocks) { updateComboSummary(tvComboSummary, comboBlocks) }
        rvComboBlocks.layoutManager = LinearLayoutManager(this)
        rvComboBlocks.adapter = comboAdapter

        btnAddComboBlock.setOnClickListener {
            val newBlock = when (spComboBlockType.selectedItemPosition) {
                0 -> ComboBlock.WarmupBlock(300, null)     // 5:00 default
                1 -> ComboBlock.PaceBlock(null, 330)        // 5:30 default
                2 -> ComboBlock.IntervalBlock(180, 270, 120, null, 3) // 3min/4:30, 2min rest, 3x
                3 -> ComboBlock.CooldownBlock(300, null)   // 5:00 default
                else -> return@setOnClickListener
            }
            comboAdapter.addBlock(newBlock)
        }

        btnSaveCombo.setOnClickListener {
            val blocks = comboAdapter.getBlocks()
            if (blocks.isEmpty()) {
                tvComboSummary.text = "Ошибка: добавьте хотя бы один блок."
                tvComboSummary.setTextColor(0xFFF44336.toInt())
                return@setOnClickListener
            }
            val scenario = ComboScenario(blocks)
            val cJson = comboGson().toJson(scenario)
            prefs.edit()
                .putInt(WORKOUT_MODE, 2)
                .putString(COMBO_SCENARIO_JSON, cJson)
                .apply()

            tvComboSummary.setTextColor(0xFF4CAF50.toInt())
            updateComboSummary(tvComboSummary, blocks, saved = true)
            Toast.makeText(this, "Комбинированная сохранена", Toast.LENGTH_SHORT).show()
        }

        // Обработчик нажатия на кнопку "Назад"
        backButton.setOnClickListener {
            finish()
        }

        // === Восстановить сохранённые значения ===
        restoreSavedFields(prefs)
    }

    private fun restoreSavedFields(prefs: SharedPreferences) {
        // --- Normal mode fields ---
        val savedDist = prefs.getFloat(TARGET_DISTANCE_KM, 0f)
        val savedTimeSec = prefs.getInt(TARGET_TIME_SEC, 0)
        val savedPaceSec = prefs.getInt(TARGET_PACE_SEC_PER_KM, 0)

        if (savedDist > 0f) {
            distanceInput.setText(String.format("%.2f", savedDist))
        }
        if (savedTimeSec > 0) {
            timeInput.setText(formatTime(savedTimeSec))
        }
        if (savedPaceSec > 0) {
            findViewById<EditText>(R.id.etTargetPace).setText(formatPace(savedPaceSec))
        }

        // Show derived info for normal mode
        val savedGoalMode = prefs.getInt(NORMAL_GOAL_MODE, 0)
        val tvNormalDerived = findViewById<TextView>(R.id.tvNormalDerived)
        if (savedDist > 0f && savedTimeSec > 0 && savedPaceSec > 0) {
            if (savedGoalMode == 0) {
                tvNormalDerived.text = "✓ Сохранено. Расчётный темп: ${formatPace(savedPaceSec)}/км"
            } else {
                tvNormalDerived.text = "✓ Сохранено. Расчётное время: ${formatTime(savedTimeSec)}"
            }
        }

        // --- Interval mode fields ---
        val json = prefs.getString(INTERVAL_SCENARIO_JSON, null)
        if (!json.isNullOrBlank()) {
            try {
                val scenario = Gson().fromJson(json, IntervalScenario::class.java)
                if (scenario != null && scenario.segments.isNotEmpty()) {
                    restoreIntervalFields(scenario)
                }
            } catch (_: Exception) { /* ignore corrupted data */ }
        }

        // --- Combo mode fields ---
        val comboJson = prefs.getString(COMBO_SCENARIO_JSON, null)
        if (!comboJson.isNullOrBlank()) {
            try {
                val combo = comboGson().fromJson(comboJson, ComboScenario::class.java)
                if (combo != null && combo.blocks.isNotEmpty()) {
                    val rv = findViewById<RecyclerView>(R.id.rvComboBlocks)
                    val adapter = rv.adapter as? ComboBlockAdapter
                    if (adapter != null) {
                        for (b in combo.blocks) adapter.addBlock(b)
                    }
                    val tvSummary = findViewById<TextView>(R.id.tvComboSummary)
                    tvSummary.setTextColor(0xFF4CAF50.toInt())
                    updateComboSummary(tvSummary, combo.blocks, saved = true)
                }
            } catch (_: Exception) { /* ignore corrupted data */ }
        }
    }

    private fun restoreIntervalFields(scenario: IntervalScenario) {
        val segs = scenario.segments

        // Find warmup (first WARMUP segment)
        val warmup = segs.firstOrNull { it.type == "WARMUP" }
        if (warmup != null) {
            findViewById<EditText>(R.id.etWarmupTime).setText(formatTime(warmup.durationSec))
            warmup.targetPaceSecPerKm?.let {
                findViewById<EditText>(R.id.etWarmupPace).setText(formatPace(it))
            }
        }

        // Find first WORK segment
        val work = segs.firstOrNull { it.type == "WORK" }
        if (work != null) {
            findViewById<EditText>(R.id.etWorkTime).setText(formatTime(work.durationSec))
            work.targetPaceSecPerKm?.let {
                findViewById<EditText>(R.id.etWorkPace).setText(formatPace(it))
            }
        }

        // Find first REST segment
        val rest = segs.firstOrNull { it.type == "REST" }
        if (rest != null) {
            findViewById<EditText>(R.id.etRestTime).setText(formatTime(rest.durationSec))
            rest.targetPaceSecPerKm?.let {
                findViewById<EditText>(R.id.etRestPace).setText(formatPace(it))
            }
        }

        // Calculate repeats = number of WORK segments
        val repeats = segs.count { it.type == "WORK" }
        if (repeats > 0) {
            findViewById<EditText>(R.id.etRepeats).setText(repeats.toString())
        }

        // Find cooldown (first COOLDOWN segment)
        val cooldown = segs.firstOrNull { it.type == "COOLDOWN" }
        if (cooldown != null) {
            findViewById<EditText>(R.id.etCooldownTime).setText(formatTime(cooldown.durationSec))
            cooldown.targetPaceSecPerKm?.let {
                findViewById<EditText>(R.id.etCooldownPace).setText(formatPace(it))
            }
        }

        // Show summary
        val totalSec = segs.sumOf { it.durationSec }
        val tvSummary = findViewById<TextView>(R.id.tvIntervalSummary)
        tvSummary.setTextColor(0xFF4CAF50.toInt())
        tvSummary.text = "✓ Сохранено. Сегментов: ${segs.size}, длительность: ${formatTime(totalSec)}"
    }

    // === Сборка сценария из UI ===
    private fun buildIntervalScenarioFromUi(): IntervalScenario? {
        val warmTimeText = findViewById<EditText>(R.id.etWarmupTime).text.toString().trim()
        val warmPaceText = findViewById<EditText>(R.id.etWarmupPace).text.toString().trim()

        val workTime = parseTimeToSec(findViewById<EditText>(R.id.etWorkTime).text.toString())
        val workPace = parsePaceToSecPerKm(findViewById<EditText>(R.id.etWorkPace).text.toString())

        val restTime = parseTimeToSec(findViewById<EditText>(R.id.etRestTime).text.toString())
        val restPaceText = findViewById<EditText>(R.id.etRestPace).text.toString().trim()
        val restPace = if (restPaceText.isEmpty()) null else parsePaceToSecPerKm(restPaceText)

        val repeats = findViewById<EditText>(R.id.etRepeats).text.toString().trim().toIntOrNull()

        val coolTimeText = findViewById<EditText>(R.id.etCooldownTime).text.toString().trim()
        val coolPaceText = findViewById<EditText>(R.id.etCooldownPace).text.toString().trim()

        // Валидация
        if (workTime == null || workTime <= 0) return null
        if (workPace == null || workPace <= 0) return null
        if (restTime == null || restTime <= 0) return null
        if (repeats == null || repeats <= 0 || repeats > 100) return null
        if (restPaceText.isNotEmpty() && restPace == null) return null

        val warmTime = if (warmTimeText.isEmpty()) null else parseTimeToSec(warmTimeText)
        val warmPace = if (warmPaceText.isEmpty()) null else parsePaceToSecPerKm(warmPaceText)
        if (warmTimeText.isNotEmpty() && warmTime == null) return null
        if (warmPaceText.isNotEmpty() && warmPace == null) return null

        val coolTime = if (coolTimeText.isEmpty()) null else parseTimeToSec(coolTimeText)
        val coolPace = if (coolPaceText.isEmpty()) null else parsePaceToSecPerKm(coolPaceText)
        if (coolTimeText.isNotEmpty() && coolTime == null) return null
        if (coolPaceText.isNotEmpty() && coolPace == null) return null

        val segs = mutableListOf<Segment>()

        if (warmTime != null && warmTime > 0) {
            segs.add(Segment("WARMUP", warmTime, targetPaceSecPerKm = warmPace))
        }

        repeat(repeats) {
            segs.add(Segment("WORK", workTime, targetPaceSecPerKm = workPace))
            segs.add(Segment("REST", restTime, targetPaceSecPerKm = restPace))
        }

        if (coolTime != null && coolTime > 0) {
            segs.add(Segment("COOLDOWN", coolTime, targetPaceSecPerKm = coolPace))
        }

        return IntervalScenario(segs)
    }
    
    // Parsing and formatting helper functions
    private fun parseTimeToSec(text: String): Int? {
        val t = text.trim()
        // Accept HH:MM:SS or MM:SS
        val parts = t.split(":")
        if (parts.size !in 2..3) return null
        return try {
            val nums = parts.map { it.toInt() }
            val sec = if (nums.size == 2) {
                val m = nums[0]; val s = nums[1]
                if (m < 0 || s !in 0..59) return null
                m * 60 + s
            } else {
                val h = nums[0]; val m = nums[1]; val s = nums[2]
                if (h < 0 || m !in 0..59 || s !in 0..59) return null
                h * 3600 + m * 60 + s
            }
            sec
        } catch (_: Exception) { null }
    }
    
    private fun parsePaceToSecPerKm(text: String): Int? {
        val t = text.trim()
        val parts = t.split(":")
        if (parts.size != 2) return null
        return try {
            val m = parts[0].toInt()
            val s = parts[1].toInt()
            if (m < 0 || s !in 0..59) return null
            m * 60 + s
        } catch (_: Exception) { null }
    }
    
    private fun formatTime(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
    
    private fun formatPace(secPerKm: Int): String {
        val m = secPerKm / 60
        val s = secPerKm % 60
        return "%02d:%02d".format(m, s)
    }

    private fun updateComboSummary(
        tv: TextView,
        blocks: List<ComboBlock>,
        saved: Boolean = false
    ) {
        if (blocks.isEmpty()) {
            tv.text = ""
            return
        }
        val scenario = ComboScenario(blocks)
        val totalDist = scenario.estimateTotalDistanceKm()
        val totalSec = scenario.estimateTotalTimeSec()
        val prefix = if (saved) "\u2713 \u0421\u043e\u0445\u0440\u0430\u043d\u0435\u043d\u043e. " else ""

        val parts = blocks.map { block ->
            when (block) {
                is ComboBlock.WarmupBlock -> "\u0420\u0430\u0437\u043c(${block.durationSec / 60}\u043c)"
                is ComboBlock.PaceBlock -> {
                    val d = block.distanceKm?.let { "${String.format("%.1f", it)}\u043a\u043c" } ?: "*"
                    "$d(${formatPace(block.paceSecPerKm)})"
                }
                is ComboBlock.IntervalBlock -> "${block.repeats}\u00d7(${block.workSec / 60}\u043c+${block.restSec / 60}\u043c)"
                is ComboBlock.CooldownBlock -> "\u0417\u0430\u043c(${block.durationSec / 60}\u043c)"
            }
        }

        val timeStr = formatTime(totalSec)
        tv.text = "${prefix}Блоков: ${blocks.size}, ~${String.format("%.1f", totalDist)} км, ~$timeStr\n${parts.joinToString(" → ")}"
    }
}