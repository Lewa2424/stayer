package com.example.stayer.ui.main

import com.example.stayer.R
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    appTitle: String,
    isRunning: Boolean,
    isPaused: Boolean,
    elapsedMs: Long,
    distanceKm: Float,
    targetDistanceText: String,
    targetTimeText: String,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onGoalClick: () -> Unit,
    onPrimaryClick: () -> Unit,
    onStopAndReset: () -> Unit,
    onOpenSetup: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
    intervalActive: Boolean = false,
    intervalType: String = "",
    intervalRemainingSec: Int = 0,
    intervalIndex: Int = 0,
    intervalTotal: Int = 0,
    intervalTargetPaceSecPerKm: Int? = null,
    workoutMode: Int = 0,
    scenarioPreview: String = "",
) {
    var showInfoSheet by remember { mutableStateOf(false) }
    val infoSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Темп в UI обновляем не на каждый тик, а раз в 15 секунд (чтобы не "мелькал").
    val latestElapsedMs by rememberUpdatedState(elapsedMs)
    val latestDistanceKm by rememberUpdatedState(distanceKm)
    var displayedPaceText by remember { mutableStateOf("—") }
    LaunchedEffect(isRunning, isPaused) {
        if (!isRunning) {
            displayedPaceText = "—"
            return@LaunchedEffect
        }

        // Обновим сразу при старте/возобновлении/паузе
        displayedPaceText = formatPaceMinPerKm(latestElapsedMs, latestDistanceKm)

        // Во время паузы — не обновляем каждые 15 сек, оставляем зафиксированным.
        if (isPaused) return@LaunchedEffect

        while (true) {
            delay(15_000L)
            displayedPaceText = formatPaceMinPerKm(latestElapsedMs, latestDistanceKm)
        }
    }

    // ==== Soft UI Цвета ====
    val softBgTop = Color(0xFFF1ECF8)
    val softBgBottom = Color(0xFFF8F5FC)
    val softAccentMain = Color(0xFF6E4BAE)

    val brush = remember { Brush.verticalGradient(listOf(softBgTop, softBgBottom)) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            MainTopBar(
                title = appTitle
            )
        },
        bottomBar = {
            StatsPanel(
                elapsedMs = elapsedMs,
                distanceKm = distanceKm,
                paceText = displayedPaceText,
                targetDistanceText = targetDistanceText,
                targetTimeText = targetTimeText,
                onGoalClick = onGoalClick,
                intervalActive = intervalActive,
                intervalType = intervalType,
                intervalRemainingSec = intervalRemainingSec,
                intervalIndex = intervalIndex,
                intervalTotal = intervalTotal,
                intervalTargetPaceSecPerKm = intervalTargetPaceSecPerKm,
                isRunning = isRunning,
                workoutMode = workoutMode,
                scenarioPreview = scenarioPreview,
            )
        }
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush)
                .padding(inner)
                .padding(contentPadding)
        ) {
            // Активные кнопки мельче, расположены колонной сверху вниз (с правой стороны)
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = { showInfoSheet = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.info_button_description),
                        modifier = Modifier.size(24.dp),
                        tint = softAccentMain
                    )
                }
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Настройки",
                        modifier = Modifier.size(24.dp),
                        tint = softAccentMain
                    )
                }
                IconButton(
                    onClick = onHistoryClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = stringResource(R.string.history_button_description),
                        modifier = Modifier.size(24.dp),
                        tint = softAccentMain
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BigActionButton(
                    isRunning = isRunning,
                    isPaused = isPaused,
                    onClick = onPrimaryClick,
                    onLongPress = onStopAndReset
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Удерживай для стоп и сохранения",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showInfoSheet) {
            ModalBottomSheet(
                onDismissRequest = { showInfoSheet = false },
                sheetState = infoSheetState
            ) {
                InfoBottomSheetContent(
                    onDismiss = { showInfoSheet = false },
                    onOpenSetup = {
                        showInfoSheet = false
                        onOpenSetup()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    title: String
) {
    val softTextPrimary = Color(0xFF2F243D)
    
    androidx.compose.material3.CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = softTextPrimary,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold)
            )
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        )
    )
}

@Composable
private fun InfoBottomSheetContent(
    onDismiss: () -> Unit,
    onOpenSetup: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // ── Заголовок ──
        Text(
            text = stringResource(R.string.info_instruction_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(16.dp))

        // ── О приложении ──
        Text(
            text = "Stayer \u2014 умное приложение для отслеживания и ведения темпа во время беговых тренировок. " +
                    "Отслеживает дистанцию по GPS (с умной страховкой по шагомеру в слепых зонах) " +
                    "и даёт голосовые подсказки, чтобы вы достигли цели вовремя.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ══════════════════════════════════
        // РЕЖИМЫ ТРЕНИРОВОК
        // ══════════════════════════════════
        SectionTitle("\uD83C\uDFC3 РЕЖИМЫ ТРЕНИРОВОК")
        Spacer(Modifier.height(12.dp))

        // --- Обычная ---
        SubSectionTitle("1. Обычная тренировка")
        InfoText(
            "Классический режим: задаёте целевую дистанцию и время (или темп), " +
                    "и бежите. Приложение рассчитает необходимый темп и будет " +
                    "подсказывать голосом, нужно ли ускориться или замедлиться."
        )
        InfoText(
            "\u2022 Дистанция \u2014 в формате КМ.МЕТРЫ (например, 05.50 = 5 км 500 м)\n" +
                    "\u2022 Время \u2014 в формате ЧЧ:ММ:СС (например, 00:30:00 = 30 минут)\n" +
                    "\u2022 Или задайте целевой темп (ММ:СС на км) \u2014 время рассчитается автоматически"
        )
        Spacer(Modifier.height(12.dp))

        // --- Интервальная ---
        SubSectionTitle("2. Интервальная тренировка")
        InfoText(
            "Структурированная тренировка с чередованием работы и отдыха. " +
                    "Идеальна для развития скорости и выносливости."
        )
        InfoText(
            "\u2022 Разминка \u2014 продолжительность и темп\n" +
                    "\u2022 Рабочий интервал \u2014 длительность и целевой темп\n" +
                    "\u2022 Отдых \u2014 длительность и темп (или свободный)\n" +
                    "\u2022 Количество повторений \u2014 сколько раз повторить цикл работа+отдых\n" +
                    "\u2022 Заминка \u2014 продолжительность и темп\n" +
                    "\nГолосовой помощник объявляет каждую смену фазы и текущую серию."
        )
        Spacer(Modifier.height(12.dp))

        // --- Комбинированная ---
        SubSectionTitle("3. Комбинированная тренировка")
        InfoText(
            "Гибкий конструктор \u2014 добавляйте любые блоки в произвольном порядке:"
        )
        InfoText(
            "\u2022 Разминка (Разм.) \u2014 время + опциональный темп\n" +
                    "\u2022 Обычный бег (Обыч.) \u2014 дистанция + целевой темп\n" +
                    "\u2022 Интервалы (Интерв.) \u2014 работа/отдых \u00d7 повторы + темп\n" +
                    "\u2022 Заминка (Замин.) \u2014 время + опциональный темп\n" +
                    "\nДобавляйте и удаляйте блоки кнопками \u00ab+ Добавить\u00bb и \u00ab\u2212\u00bb. " +
                    "Порядок блоков \u2014 порядок выполнения."
        )
        Spacer(Modifier.height(12.dp))

        InfoText(
            "\uD83D\uDCCB Превью: после сохранения цели на главном экране появится " +
                    "карточка с планом тренировки \u2014 удобно перепроверить перед стартом.",
            bold = true
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ══════════════════════════════════
        // УПРАВЛЕНИЕ ТРЕНИРОВКОЙ
        // ══════════════════════════════════
        SectionTitle("\u25B6\uFE0F УПРАВЛЕНИЕ ТРЕНИРОВКОЙ")
        Spacer(Modifier.height(12.dp))

        InfoText(
            "\u2022 Старт \u2014 нажмите медаль в центре экрана\n" +
                    "\u2022 Пауза \u2014 нажмите медаль во время тренировки\n" +
                    "\u2022 Продолжить \u2014 нажмите медаль повторно\n" +
                    "\u2022 Стоп и сохранение \u2014 удерживайте медаль более 1 секунды\n" +
                    "\nПосле остановки результаты автоматически сохраняются в историю."
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ══════════════════════════════════
        // ГЛАВНЫЙ ЭКРАН
        // ══════════════════════════════════
        SectionTitle("\uD83D\uDCCA ГЛАВНЫЙ ЭКРАН")
        Spacer(Modifier.height(12.dp))

        SubSectionTitle("Карточки статистики")
        InfoText(
            "В нижней панели отображаются 4 карточки:\n" +
                    "\u2022 Время \u2014 текущее время тренировки\n" +
                    "\u2022 Дистанция \u2014 пройденное расстояние (GPS + шагомер)\n" +
                    "\u2022 Темп \u2014 текущий темп в мин/км (обновляется каждые 15 сек)\n" +
                    "\u2022 Цель \u2014 нажмите для настройки целей и режима"
        )
        Spacer(Modifier.height(8.dp))

        SubSectionTitle("Во время интервала / комбо")
        InfoText(
            "Карточки автоматически переключаются:\n" +
                    "\u2022 Время \u2192 Название фазы\n" +
                    "\u2022 Дистанция \u2192 Номер серии (например, 3/8)\n" +
                    "\u2022 Темп \u2192 Осталось (обратный отсчёт до конца фазы)\n\n" +
                    "\uD83D\uDCA1 При потере GPS (туннель, лес) включится умный шагомер: " +
                    "он запоминает длину вашего шага и продолжает точно считать дистанцию."
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ══════════════════════════════════
        // ГОЛОСОВОЙ ПОМОЩНИК
        // ══════════════════════════════════
        SectionTitle("\uD83D\uDD0A ГОЛОСОВОЙ ПОМОЩНИК")
        Spacer(Modifier.height(12.dp))

        InfoText(
            "Stayer озвучивает подсказки во время тренировки:\n\n" +
                    "\u2022 Каждые 10% от целевой дистанции \u2014 текущее время, дистанция, темп и рекомендация\n" +
                    "\u2022 При отклонении от темпа \u2014 \u00abускоряйтесь\u00bb или \u00abзамедляйтесь\u00bb\n" +
                    "\u2022 Смена фазы (интервалы/комбо) \u2014 название фазы, номер серии\n" +
                    "\u2022 Обратный отсчёт \u2014 последние 5 секунд перед сменой фазы\n\n" +
                    "Громкость подсказок зависит от громкости медиа на устройстве."
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ══════════════════════════════════
        // ИСТОРИЯ
        // ══════════════════════════════════
        SectionTitle("\uD83D\uDCDA ИСТОРИЯ ТРЕНИРОВОК")
        Spacer(Modifier.height(12.dp))

        InfoText(
            "\u2022 Нажмите иконку Истории в правом верхнем углу\n" +
                    "\u2022 Появится список красивых карточек-аккордеонов\n" +
                    "\u2022 В шапке указана ваша Цель. Нажмите на карточку, чтобы развернуть детальную статистику (факт, темп, средний темп по фазам работы и отдыха)\n" +
                    "\u2022 Для удаления записи нажмите иконку корзины"
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ══════════════════════════════════
        // НАСТРОЙКИ
        // ══════════════════════════════════
        SectionTitle("\u2699\uFE0F НАСТРОЙКИ")
        Spacer(Modifier.height(12.dp))

        InfoText(
            "\u2022 Иконка шестерёнки в правом верхнем углу\n" +
                    "\u2022 Здесь можно настроить голосовые оповещения и другие параметры"
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ══════════════════════════════════
        // ОБРАТНАЯ СВЯЗЬ
        // ══════════════════════════════════
        SectionTitle("\u2709\uFE0F ОБРАТНАЯ СВЯЗЬ")
        Spacer(Modifier.height(12.dp))

        Text(
            text = "Обо всех ошибках и пожеланиях пишите на почту:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Text(
                text = stringResource(R.string.info_email),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "(удерживайте, чтобы выделить и скопировать)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        // ── Поддержка разработки (закомментировано) ──
        /*
        Text(
            text = "ПОДДЕРЖКА РАЗРАБОТКИ:",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Если есть желание и возможность, поддержите автора и исполнителя:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Text(
                text = stringResource(R.string.info_card_number),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(24.dp))
        */

        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.info_close))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onOpenSetup,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.setup_check_button))
        }
    }
}

// ── Info sheet helper composables ──

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun SubSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun InfoText(text: String, bold: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.let {
            if (bold) it.copy(fontWeight = FontWeight.Medium) else it
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupChecklistScreen(
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var refreshTick by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    val locationGranted = remember(refreshTick) { hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) }
    val notificationsGranted = remember(refreshTick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else true
    }
    val notificationsEnabled = remember(refreshTick) {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val systemLocationEnabled = remember(refreshTick) {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled else true
    }
    val batteryUnrestricted = remember(refreshTick) {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else true
    }

    val requiredOk = locationGranted && systemLocationEnabled && notificationsGranted && notificationsEnabled

    val requestLocation = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }
    val requestNotifications = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }

    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        context.startActivity(intent)
    }

    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        context.startActivity(intent)
    }

    fun openLocationSettings() {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Проверка готовности") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text = "Чтобы тренировка в фоне работала стабильно на любых телефонах, проверь эти пункты один раз.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            SetupRow(
                title = "Местоположение",
                subtitle = "Разрешить доступ к геолокации",
                ok = locationGranted,
                required = true,
                actionLabel = if (locationGranted) "Ок" else "Разрешить",
                onAction = {
                    requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            )
            SetupRow(
                title = "Геолокация включена",
                subtitle = "Включите GPS/местоположение в системе",
                ok = systemLocationEnabled,
                required = true,
                actionLabel = if (systemLocationEnabled) "Ок" else "Открыть",
                onAction = { openLocationSettings() }
            )
            SetupRow(
                title = "Уведомления",
                subtitle = "Нужны для работы фонового сервиса и голосовых подсказок",
                ok = notificationsGranted && notificationsEnabled,
                required = true,
                actionLabel = if (notificationsGranted && notificationsEnabled) "Ок" else "Открыть",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted) {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openNotificationSettings()
                    }
                }
            )
            SetupRow(
                title = "Батарея (рекомендуется)",
                subtitle = "Отключите оптимизацию батареи для стабильной работы в фоне",
                ok = batteryUnrestricted,
                required = false,
                actionLabel = if (batteryUnrestricted) "Ок" else "Открыть",
                onAction = {
                    // Вариант A: системный запрос; если OEM его режет — пользователь сможет зайти в настройки приложения
                    try {
                        requestIgnoreBatteryOptimizations()
                    } catch (_: Exception) {
                        openAppSettings()
                    }
                }
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "На Samsung/MIUI иногда нужно дополнительно: «Батарея → Без ограничений» и убрать приложение из «Сон/Ограничения».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onDone,
                enabled = requiredOk,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (requiredOk) "Готово" else "Завершить (сначала включите обязательные пункты)")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { openAppSettings() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Открыть настройки приложения")
            }
        }
    }
}

@Composable
private fun SetupRow(
    title: String,
    subtitle: String,
    ok: Boolean,
    required: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    val statusColor = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (required) "$title (обязательно)" else title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onAction) {
                Text(actionLabel, color = statusColor)
            }
        }
    }
}

@Composable
private fun BigActionButton(
    isRunning: Boolean,
    isPaused: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val label = if (isRunning && !isPaused) "Пауза" else "Старт"
    val contentColor = Color.White
    
    // Soft UI Цвета медали
    val primaryPurple = Color(0xFF6E4BAE)
    val accentOrange = Color(0xFFFF8600)
    val centerPurple = primaryPurple.copy(alpha = 0.75f)

    var pressed by remember { mutableStateOf(false) }
    // Интерактив: уменьшение до 0.95 при нажатии
    val scale by animateFloatAsState(targetValue = if (pressed) 0.95f else 1f, label = "main_btn_scale")
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnLongPress by rememberUpdatedState(onLongPress)

    val haptic = LocalHapticFeedback.current

    // Контейнер для медали и лент
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp), // Место для лент + кнопка
        contentAlignment = Alignment.BottomCenter
    ) {
        // Ленточки (сверху кнопки, смещены вверх)
        Image(
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_medal_ribbons),
            contentDescription = null,
            modifier = Modifier
                .size(140.dp, 80.dp)
                .align(Alignment.TopCenter)
                .offset(y = 10.dp) // немного заходят за кнопку
        )

        // Сама круглая кнопка
        Box(
            modifier = Modifier
                .size(200.dp)
                .scale(scale)
                .shadow(
                    elevation = if (pressed) 4.dp else 14.dp, // Имитация boxShadow
                    shape = CircleShape,
                    spotColor = Color(0x66000000), // черная тень 
                    ambientColor = Color(0x66000000)
                )
                .background(
                    brush = Brush.radialGradient(
                        0.4f to centerPurple,
                        1.0f to primaryPurple
                    ),
                    shape = CircleShape
                )
                .border(width = 5.dp, color = accentOrange, shape = CircleShape)
                .pointerInput(Unit) {
                    // Сохраняем прежнюю UX-логику: длинное удержание ~800мс = стоп+сброс
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            var longPressFired = false
                            val job = CoroutineScope(Dispatchers.Main).launch {
                                delay(800)
                                longPressFired = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                latestOnLongPress()
                            }
                            try {
                                val released = tryAwaitRelease()
                                if (released && !longPressFired) {
                                    latestOnClick()
                                }
                            } finally {
                                job.cancel()
                                pressed = false
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Содержимое
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.DirectionsRun,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun StatsPanel(
    elapsedMs: Long,
    distanceKm: Float,
    paceText: String,
    targetDistanceText: String,
    targetTimeText: String,
    onGoalClick: () -> Unit,
    intervalActive: Boolean = false,
    intervalType: String = "",
    intervalRemainingSec: Int = 0,
    intervalIndex: Int = 0,
    intervalTotal: Int = 0,
    intervalTargetPaceSecPerKm: Int? = null,
    isRunning: Boolean = false,
    workoutMode: Int = 0,
    scenarioPreview: String = "",
) {

    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            if (!intervalActive) {
                // ====== Обычный / Pre-start режим ======
                val showPreview = !isRunning && workoutMode > 0 && scenarioPreview.isNotBlank()

                if (showPreview) {
                    // Scenario preview card — extends upward toward medal
                    val softAccentMain = Color(0xFF6E4BAE)
                    val accentOrange = Color(0xFFFF8600)
                    val previewTransition = rememberInfiniteTransition(label = "PreviewGrad")
                    val previewOffset by previewTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1000f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(4000, easing = LinearEasing),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                        ),
                        label = "preview_gradient_offset"
                    )
                    val previewBrush = Brush.linearGradient(
                        colors = listOf(softAccentMain, accentOrange, softAccentMain),
                        start = Offset(previewOffset, 0f),
                        end = Offset(previewOffset + 600f, 300f),
                        tileMode = TileMode.Repeated
                    )
                    val modeLabel = when (workoutMode) {
                        1 -> "\u26A1 \u0418\u043D\u0442\u0435\u0440\u0432\u0430\u043B\u044C\u043D\u0430\u044F"
                        2 -> "\uD83C\uDFAF \u041A\u043E\u043C\u0431\u0438\u043D\u0438\u0440\u043E\u0432\u0430\u043D\u043D\u0430\u044F"
                        else -> ""
                    }

                    val scrollState = rememberScrollState()
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFF5F1FA),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .heightIn(max = 160.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = modeLabel,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    brush = previewBrush
                                )
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .verticalScroll(scrollState)
                            ) {
                                Text(
                                    text = scenarioPreview,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        lineHeight = 20.sp
                                    ),
                                    color = Color(0xFF2F243D)
                                )
                            }
                        }
                    }
                } else {
                    // Standard live tiles
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        StatTile(
                            icon = Icons.Outlined.Timer,
                            value = formatHms(elapsedMs),
                            label = "\u0412\u0440\u0435\u043C\u044F",
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            icon = Icons.AutoMirrored.Outlined.DirectionsRun,
                            value = formatKm(distanceKm),
                            label = "\u0414\u0438\u0441\u0442\u0430\u043D\u0446\u0438\u044F",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatTile(
                        icon = Icons.Outlined.Speed,
                        value = paceText,
                        label = "\u0422\u0435\u043C\u043F",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        icon = Icons.Outlined.Flag,
                        value = formatGoalValue(targetDistanceText),
                        label = "\u0426\u0435\u043B\u044C",
                        supporting = targetTimeText,
                        showChevron = true,
                        enablePressedState = false,
                        enableHintAnimation = false,
                        actionLabel = "\u0418\u0437\u043C\u0435\u043D\u0438\u0442\u044C",
                        highlightAction = true,
                        onClick = onGoalClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                // ====== Интервальный режим ======
                val phase = intervalTypeRu(intervalType)
                val remain = formatMmSs(intervalRemainingSec)
                val series = if (intervalTotal > 0) "$intervalIndex/$intervalTotal" else "—"
                val targetPace = intervalTargetPaceSecPerKm?.let { formatPaceOnly(it) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatTile(
                        icon = Icons.Outlined.Timer,
                        value = formatHms(elapsedMs),
                        label = "Время",
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        icon = Icons.AutoMirrored.Outlined.DirectionsRun,
                        value = phase,
                        label = "Фаза",
                        supporting = "Серия $series",
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatTile(
                        icon = Icons.Outlined.Speed,
                        value = remain,
                        label = "Осталось",
                        supporting = if (targetPace != null) "Цель $targetPace" else null,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        icon = Icons.Outlined.Flag,
                        value = "Интервалы",
                        label = "Цель",
                        showChevron = true,
                        enablePressedState = false,
                        enableHintAnimation = false,
                        actionLabel = "Изменить",
                        highlightAction = true,
                        onClick = onGoalClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    showChevron: Boolean = false,
    enablePressedState: Boolean = false,
    enableHintAnimation: Boolean = false,
    actionLabel: String? = null,
    @Suppress("unused") highlightAction: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    // Pressed state для scale анимации
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressed = enablePressedState && isPressed
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        label = "goal_tile_scale"
    )
    val latestOnClick by rememberUpdatedState(onClick)

    // Hint animation для chevron (первые 4 секунды)
    var showHint by remember { 
        mutableStateOf(enableHintAnimation && showChevron) 
    }
    val infiniteTransition = rememberInfiniteTransition(label = "goal_hint")
    val chevronAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, delayMillis = 0),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "chevron_alpha"
    )
    val chevronOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, delayMillis = 0),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "chevron_offset"
    )

    // Останавливаем hint animation через 4 секунды
    LaunchedEffect(Unit) {
        if (enableHintAnimation && showChevron) {
            delay(4000)
            showHint = false
        }
    }

    val softCardBg = Color(0xFFF5F1FA)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = softCardBg,
        shadowElevation = 4.dp, // Мягкая тень
        modifier = modifier
            .scale(scale)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current
                    ) { latestOnClick?.invoke() }
                } else {
                    Modifier
                }
            )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!actionLabel.isNullOrBlank()) {
                // Action-layout (только для Goal tile): "value     >" / supporting / "label · action"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        ),
                        color = Color(0xFF2F243D) // soft text primary
                    )
                    if (showChevron) {
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .then(
                                    if (showHint) {
                                        Modifier
                                            .alpha(chevronAlpha)
                                            .offset(x = chevronOffset.dp)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
                if (!supporting.isNullOrBlank()) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6F6480) // soft text secondary
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Animated gradient for "Цель · Изменить" (same as GoalActivity)
                    val primaryPurple = Color(0xFF6E4BAE)
                    val accentOrange = Color(0xFFFF8600)
                    val gradientTransition = rememberInfiniteTransition(label = "GoalLabelGrad")
                    val gradientOffset by gradientTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1000f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(3000, easing = LinearEasing),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                        ),
                        label = "goal_label_gradient_offset"
                    )
                    val gradientBrush = Brush.linearGradient(
                        colors = listOf(primaryPurple, accentOrange, primaryPurple),
                        start = Offset(gradientOffset, gradientOffset),
                        end = Offset(gradientOffset + 500f, gradientOffset + 500f),
                        tileMode = TileMode.Repeated
                    )

                    Text(
                        text = "$label · $actionLabel",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            brush = gradientBrush
                        )
                    )
                }
            } else {
                // Default layout (для остальных карточек)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (showChevron) {
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .then(
                                    if (showHint) {
                                        Modifier
                                            .alpha(chevronAlpha)
                                            .offset(x = chevronOffset.dp)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    ),
                    color = Color(0xFF2F243D)
                )
                if (!supporting.isNullOrBlank()) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6F6480)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFF6F6480)
                )
            }
        }
    }
}

private fun formatHms(elapsedMs: Long): String {
    val totalSec = (elapsedMs / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private fun formatKm(km: Float): String {
    val v = if (km.isFinite() && km > 0f) km else 0f
    return "%.2f км".format(v)
}

private fun formatPaceMinPerKm(elapsedMs: Long, distanceKm: Float): String {
    if (distanceKm <= 0.05f) return "—"
    val sec = (elapsedMs / 1000f).coerceAtLeast(0f)
    val secPerKm = (sec / distanceKm)
    if (!secPerKm.isFinite() || secPerKm <= 0f) return "—"
    val total = secPerKm.toInt()
    val min = total / 60
    val s = total % 60
    return "%d:%02d /км".format(min, s)
}

private fun formatGoalValue(targetDistanceText: String): String {
    val normalized = targetDistanceText.trim().replace(',', '.')
    val v = normalized.toFloatOrNull() ?: 0f
    return if (v <= 0f) "—" else "%.2f км".format(v)
}

private fun formatMmSs(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    val m = s / 60
    val sec = s % 60
    return "%02d:%02d".format(m, sec)
}

private fun intervalTypeRu(type: String): String = when (type) {
    "WARMUP" -> "Разминка"
    "WORK" -> "Работа"
    "REST" -> "Отдых"
    "COOLDOWN" -> "Заминка"
    else -> "—"
}

private fun formatPaceOnly(secPerKm: Int): String {
    val m = secPerKm / 60
    val s = secPerKm % 60
    return "%02d:%02d".format(m, s)
}
