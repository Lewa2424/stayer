package com.example.stayer.ui.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stayer.R
import com.example.stayer.analytics.AnalyticsMetric
import com.example.stayer.analytics.AnalyticsMode
import com.example.stayer.analytics.AnalyticsPeriod
import com.example.stayer.analytics.WorkoutAnalyticsEngine
import com.example.stayer.analytics.WorkoutAnalyticsReport
import com.example.stayer.history.WorkoutHistoryRepository

private val AnalyticsBg = Color(0xFFF8F9FA)
private val AnalyticsCard = Color(0xFFFFFFFF)
private val AnalyticsBlue = Color(0xFF0052FF)
private val AnalyticsOrange = Color(0xFFFF6B00)
private val AnalyticsYellow = Color(0xFFFFD84D)
private val AnalyticsText = Color(0xFF163A70)
private val AnalyticsSubtle = Color(0xFF5F6F85)
private val AnalyticsFont = FontFamily(Font(R.font.arista_pro))

/**
 * Экран аналитики с выбором режима и периода.
 * Analytics screen with mode and period selectors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    historyRepository: WorkoutHistoryRepository,
    analyticsEngine: WorkoutAnalyticsEngine,
    onBack: () -> Unit
) {
    var selectedMode by remember { mutableStateOf(AnalyticsMode.NORMAL) }
    var selectedPeriod by remember { mutableStateOf(AnalyticsPeriod.DAYS_30) }

    val history = remember(selectedMode, selectedPeriod) { historyRepository.loadAll() }
    val report = remember(selectedMode, selectedPeriod, history.size) {
        analyticsEngine.buildReport(
            history = history,
            mode = selectedMode,
            period = selectedPeriod
        )
    }

    Scaffold(
        containerColor = AnalyticsBg,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Аналитика",
                        color = AnalyticsText,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = AnalyticsFont
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Назад",
                            tint = AnalyticsBlue
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = AnalyticsBg,
                    scrolledContainerColor = AnalyticsBg,
                    navigationIconContentColor = AnalyticsBlue,
                    titleContentColor = AnalyticsText
                )
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AnalyticsBg)
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ChoiceSection(
                    title = "Режим",
                    values = AnalyticsMode.entries,
                    selected = selectedMode,
                    onSelect = { selectedMode = it },
                    label = { it.title }
                )

                ChoiceSection(
                    title = "Период",
                    values = AnalyticsPeriod.entries,
                    selected = selectedPeriod,
                    onSelect = { selectedPeriod = it },
                    label = { it.title }
                )

                AnalyticsSummaryCard(report)

                if (report.insufficientDataMessage != null) {
                    NoticeCard(report.insufficientDataMessage)
                } else {
                    MetricsCard(report.metrics)
                    InsightsCard(
                        title = "Что стало лучше",
                        lines = report.improvements.ifEmpty { listOf("Явных улучшений за выбранный период пока не видно.") }
                    )
                    InsightsCard(
                        title = "Что стало хуже",
                        lines = report.regressions.ifEmpty { listOf("Явных ухудшений за выбранный период не найдено.") }
                    )
                    InsightsCard(
                        title = "Что подтянуть",
                        lines = report.focusPoints.ifEmpty { listOf("Недостаточно данных для узкого фокуса. Накопите ещё записи.") }
                    )
                }
            }
        }
    }
}

/**
 * Блок выбора режима или периода.
 * Selector block for workout mode or time period.
 */
@Composable
private fun <T> ChoiceSection(
    title: String,
    values: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AnalyticsCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = AnalyticsText
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                values.forEach { value ->
                    val isSelected = value == selected
                    if (isSelected) {
                        Button(
                            onClick = { onSelect(value) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AnalyticsOrange,
                                contentColor = Color.White
                            )
                        ) {
                            Text(text = label(value), maxLines = 1)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSelect(value) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AnalyticsBlue)
                        ) {
                            Text(text = label(value), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Сводка по выбранной выборке истории.
 * Summary card for selected analytics slice.
 */
@Composable
private fun AnalyticsSummaryCard(report: WorkoutAnalyticsReport) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AnalyticsCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = report.mode.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = AnalyticsText
            )
            Text(
                text = "Период: ${report.period.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = AnalyticsSubtle
            )
            SummaryLine("Тренировок", report.workoutsCount.toString(), AnalyticsBlue)
            SummaryLine("Тестовых записей", report.testWorkoutsCount.toString(), AnalyticsOrange)
            SummaryLine("Суммарная дистанция", String.format(java.util.Locale.getDefault(), "%.2f км", report.totalDistanceKm), AnalyticsBlue)
            SummaryLine("Суммарное время", formatClock(report.totalTimeSec), AnalyticsOrange)
        }
    }
}

@Composable
private fun SummaryLine(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = AnalyticsSubtle)
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = AnalyticsFont
            )
        )
    }
}

/**
 * Карточка сухих метрик аналитики.
 * Card with raw analytics metrics.
 */
@Composable
private fun MetricsCard(metrics: List<AnalyticsMetric>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AnalyticsCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Сводка",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = AnalyticsText
            )
            metrics.forEach { metric ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = metric.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = AnalyticsSubtle
                    )
                    Text(
                        text = metric.value,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = AnalyticsText
                    )
                }
            }
        }
    }
}

/**
 * Карточка с текстовыми выводами аналитики.
 * Card with textual analytics findings.
 */
@Composable
private fun InsightsCard(
    title: String,
    lines: List<String>
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AnalyticsCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = AnalyticsText
            )
            lines.forEach { line ->
                Text(
                    text = "• $line",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AnalyticsSubtle
                )
            }
        }
    }
}

/**
 * Карточка уведомления о нехватке данных.
 * Notice card for insufficient analytics data.
 */
@Composable
private fun NoticeCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = AnalyticsCard,
        shadowElevation = 10.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Недостаточно данных",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = AnalyticsText
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AnalyticsSubtle
            )
            Surface(
                color = AnalyticsYellow.copy(alpha = 0.35f),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "Добавьте ещё тренировок для стабильных выводов.",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = AnalyticsText,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Форматирует секунды в строку часов.
 * Formats seconds as a clock string.
 */
private fun formatClock(totalSec: Int): String {
    val safe = totalSec.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) {
        String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
