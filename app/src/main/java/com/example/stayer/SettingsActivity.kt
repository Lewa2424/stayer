package com.example.stayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.stayer.debug.PacerTestActivity
import com.example.stayer.ui.main.SetupChecklistScreen
import com.example.stayer.ui.theme.StayerTheme
import java.util.Locale

/**
 * Экран сервисных настроек приложения.
 * Service settings screen for the application.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StayerTheme {
                var showSetupChecklist by remember { mutableStateOf(false) }

                if (showSetupChecklist) {
                    SetupChecklistScreen(onDone = { showSetupChecklist = false })
                } else {
                    SettingsScreen(
                        onBack = { finish() },
                        onOpenPacerTest = {
                            startActivity(Intent(this@SettingsActivity, PacerTestActivity::class.java))
                        },
                        onOpenSetupChecklist = { showSetupChecklist = true },
                        context = this@SettingsActivity
                    )
                }
            }
        }
    }
}

/**
 * Контент экрана настроек без логики пульсометра.
 * Settings content without heart-rate logic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPacerTest: () -> Unit,
    onOpenSetupChecklist: () -> Unit,
    context: Context
) {
    var showCadence by remember { mutableStateOf(false) }
    var cadenceText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки (Сервис)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
        ) {
            ListItem(
                headlineContent = { Text("Тест пейсера (симуляция)") },
                supportingContent = { Text("Инструмент отладки голосовых подсказок без GPS") },
                modifier = Modifier.clickable(onClick = onOpenPacerTest)
            )

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Мой шаг и каденс") },
                supportingContent = {
                    Text(
                        if (showCadence) cadenceText
                        else "Нажмите, чтобы загрузить свежие данные калибровки, которые используются при потере GPS"
                    )
                },
                modifier = Modifier.clickable {
                    val prefs = context.getSharedPreferences("StepCalibrationProfile", Context.MODE_PRIVATE)
                    val s1 = prefs.getFloat("bucket_under_140", 0.70f)
                    val s2 = prefs.getFloat("bucket_140_150", 0.78f)
                    val s3 = prefs.getFloat("bucket_150_160", 0.85f)
                    val s4 = prefs.getFloat("bucket_over_160", 0.92f)
                    cadenceText = buildString {
                        append("Ваш калиброванный шаг\n\n")
                        append(String.format(Locale.getDefault(), "Прогулочный (менее 140/мин): %.2f м\n", s1))
                        append(String.format(Locale.getDefault(), "Легкий бег (140 - 150/мин): %.2f м\n", s2))
                        append(String.format(Locale.getDefault(), "Средний темп (150 - 160/мин): %.2f м\n", s3))
                        append(String.format(Locale.getDefault(), "Быстрый бег (более 160/мин): %.2f м", s4))
                    }
                    showCadence = true
                }
            )

            HorizontalDivider()
            Spacer(Modifier.weight(1f))

            ListItem(
                headlineContent = { Text("Проверить настройки") },
                supportingContent = { Text("Разрешения, уведомления, GPS и шагомер") },
                modifier = Modifier.clickable(onClick = onOpenSetupChecklist)
            )
        }
    }
}
