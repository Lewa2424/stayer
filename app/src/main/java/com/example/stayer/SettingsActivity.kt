package com.example.stayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.stayer.debug.PacerTestActivity
import com.example.stayer.engine.PaceCadenceProfileRow
import com.example.stayer.engine.PaceCadenceProfileStore
import com.example.stayer.engine.PaceCadenceScale
import com.example.stayer.pathnet.ui.RouteMapActivity
import com.example.stayer.ui.main.SetupChecklistScreen
import com.example.stayer.ui.theme.StayerTheme
import java.util.Locale

private val SettingsBg = Color(0xFFF8F9FA)
private val SettingsCard = Color(0xFFFFFFFF)
private val SettingsBlue = Color(0xFF0052FF)
private val SettingsOrange = Color(0xFFFF6B00)
private val SettingsText = Color(0xFF163A70)
private val SettingsSubtle = Color(0xFF5F6F85)
private val SettingsFont = FontFamily(Font(R.font.arista_pro))

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
                        onOpenRouteMap = {
                            startActivity(Intent(this@SettingsActivity, RouteMapActivity::class.java))
                        },
                        onOpenSetupChecklist = { showSetupChecklist = true },
                        context = this@SettingsActivity,
                    )
                }
            }
        }
    }
}

/**
 * Контент экрана настроек без логики тренировки.
 * Settings content without workout logic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPacerTest: () -> Unit,
    onOpenRouteMap: () -> Unit,
    onOpenSetupChecklist: () -> Unit,
    context: Context,
) {
    var showCadenceTable by remember { mutableStateOf(false) }
    var cadenceRows by remember { mutableStateOf(emptyList<PaceCadenceProfileRow>()) }
    val profileStore = remember { PaceCadenceProfileStore(context) }

    Scaffold(
        containerColor = SettingsBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Настройки",
                        color = SettingsText,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = SettingsFont
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = SettingsBlue,
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .background(SettingsBg)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCardItem(
                title = "Тест пейсера",
                subtitle = "Отладка голосовых подсказок без GPS",
                onClick = onOpenPacerTest
            )

            SettingsCardItem(
                title = "Редактор маршрутной сети",
                subtitle = "Полноэкранная карта, ветки и сохранение локальной сети",
                onClick = onOpenRouteMap
            )

            SettingsCardItem(
                title = "Мой шаг и каденс",
                subtitle = if (showCadenceTable) {
                    "Обучается на тренировках с хорошим GPS и включённым маршрутом"
                } else {
                    "Таблица темпа 7:00–4:00: каденс и шаг на каждом темпе"
                },
                onClick = {
                    cadenceRows = profileStore.loadTable()
                    showCadenceTable = !showCadenceTable
                }
            )

            if (showCadenceTable) {
                PaceCadenceTableCard(cadenceRows)
            }

            Spacer(Modifier.padding(top = 24.dp))

            SettingsCardItem(
                title = "Проверить настройки",
                subtitle = "Разрешения, уведомления, GPS и шагомер",
                accent = SettingsOrange,
                onClick = onOpenSetupChecklist
            )
        }
    }
}

/**
 * Таблица pace-профиля: темп → каденс + шаг.
 * Pace profile table: pace → cadence + stride.
 */
@Composable
private fun PaceCadenceTableCard(rows: List<PaceCadenceProfileRow>) {
    Surface(
        color = SettingsCard,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            PaceCadenceTableHeader()
            rows.forEach { row ->
                PaceCadenceTableRow(row)
            }
        }
    }
}

@Composable
private fun PaceCadenceTableHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text("Темп", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = SettingsText)
        Text("Каденс", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = SettingsText, textAlign = TextAlign.Center)
        Text("Шаг", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = SettingsText, textAlign = TextAlign.Center)
        Text("Обр.", modifier = Modifier.weight(0.6f), fontWeight = FontWeight.Bold, color = SettingsText, textAlign = TextAlign.End)
    }
}

@Composable
private fun PaceCadenceTableRow(row: PaceCadenceProfileRow) {
    val cadenceText = row.avgCadenceSpm?.toString() ?: "—"
    val strideText = row.strideMeters?.let {
        String.format(Locale.getDefault(), "%.2f м", it)
    } ?: "—"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            PaceCadenceScale.formatPace(row.paceSecPerKm),
            modifier = Modifier.weight(1f),
            color = SettingsText,
        )
        Text(cadenceText, modifier = Modifier.weight(1f), color = SettingsSubtle, textAlign = TextAlign.Center)
        Text(strideText, modifier = Modifier.weight(1f), color = SettingsSubtle, textAlign = TextAlign.Center)
        Text(
            row.sampleCount.toString(),
            modifier = Modifier.weight(0.6f),
            color = SettingsSubtle,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Белая карточка действия для экрана настроек.
 * White action card for the settings screen.
 */
@Composable
private fun SettingsCardItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    accent: Color = SettingsBlue,
) {
    Surface(
        color = SettingsCard,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 10.dp,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = title,
                    color = SettingsText,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            supportingContent = {
                Text(
                    text = subtitle,
                    color = SettingsSubtle
                )
            },
            trailingContent = {
                Text(
                    text = "Открыть",
                    color = accent,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
