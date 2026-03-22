package com.example.stayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.stayer.debug.PacerTestActivity
import com.example.stayer.ui.theme.StayerTheme

class SettingsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StayerTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Настройки (Сервис)") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                                }
                            }
                        )
                    }
                ) { inner ->
                    Column(modifier = Modifier.padding(inner).fillMaxSize()) {
                        ListItem(
                            headlineContent = { Text("Тест пейсера (симуляция)") },
                            supportingContent = { Text("Инструмент отладки голосовых подсказок без GPS") },
                            modifier = Modifier.clickable {
                                startActivity(Intent(this@SettingsActivity, PacerTestActivity::class.java))
                            }
                        )
                        
                        HorizontalDivider()

                        var showCadence by remember { mutableStateOf(false) }
                        var cadenceText by remember { mutableStateOf("") }
                        
                        ListItem(
                            headlineContent = { Text("Мой шаг и Каденс") },
                            supportingContent = { 
                                if (showCadence) {
                                  Text(cadenceText)
                                } else {
                                  Text("Нажмите, чтобы загрузить свежие данные калибровки (применяются при потере GPS)")
                                }
                            },
                            modifier = Modifier.clickable {
                                val prefs = getSharedPreferences("StepCalibrationProfile", Context.MODE_PRIVATE)
                                val s1 = prefs.getFloat("bucket_under_140", 0.70f)
                                val s2 = prefs.getFloat("bucket_140_150", 0.78f)
                                val s3 = prefs.getFloat("bucket_150_160", 0.85f)
                                val s4 = prefs.getFloat("bucket_over_160", 0.92f)
                                
                                cadenceText = "ВАШ КАЛИБРОВАННЫЙ ШАГ\n\n" +
                                              String.format("Прогулочный (менее 140/мин): %.2f м\n", s1) +
                                              String.format("Легкий бег (140 - 150/мин): %.2f м\n", s2) +
                                              String.format("Средний темп (150 - 160/мин): %.2f м\n", s3) +
                                              String.format("Быстрый бег (более 160/мин): %.2f м", s4)
                                showCadence = true
                            }
                        )
                        
                        HorizontalDivider()

                        // --- Настройки пульсометра ---
                        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                        var greenMax by remember { mutableFloatStateOf(settingsPrefs.getInt("PREF_HR_GREEN_MAX", 140).toFloat()) }
                        var yellowMax by remember { mutableFloatStateOf(settingsPrefs.getInt("PREF_HR_YELLOW_MAX", 160).toFloat()) }

                        ListItem(
                            headlineContent = { Text("Зоны пульса (Health Connect)") },
                            supportingContent = {
                                Column(modifier = Modifier.padding(top = 8.dp)) {
                                    Text("Верхняя граница зеленой зоны: ${greenMax.toInt()} BPM")
                                    Slider(
                                        value = greenMax,
                                        onValueChange = { newValue ->
                                            if (newValue < yellowMax) { // greenMax cannot exceed yellowMax
                                                greenMax = newValue
                                                settingsPrefs.edit().putInt("PREF_HR_GREEN_MAX", newValue.toInt()).apply()
                                            }
                                        },
                                        valueRange = 100f..180f,
                                        steps = 80
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text("Верхняя граница желтой зоны: ${yellowMax.toInt()} BPM")
                                    Slider(
                                        value = yellowMax,
                                        onValueChange = { newValue ->
                                            if (newValue > greenMax) { // yellowMax must be above greenMax
                                                yellowMax = newValue
                                                settingsPrefs.edit().putInt("PREF_HR_YELLOW_MAX", newValue.toInt()).apply()
                                            }
                                        },
                                        valueRange = 120f..200f,
                                        steps = 80
                                    )
                                    Text(
                                        text = "Выше ${yellowMax.toInt()} BPM — Красная зона",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
