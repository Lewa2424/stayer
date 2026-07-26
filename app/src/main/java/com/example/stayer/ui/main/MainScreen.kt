package com.example.stayer.ui.main

import com.example.stayer.R
import com.example.stayer.MainActivity
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Calculate
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
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
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

private val LightBg = Color(0xFFF8F9FA)
private val CardWhite = Color(0xFFFFFFFF)
private val ElectricBlue = Color(0xFF0052FF)
private val EnergyOrange = Color(0xFFFF6B00)
private val SolarYellow = Color(0xFFFFD84D)
private val PulseBlue = Color(0xFFB8D9FF)
private val DeepBlue = Color(0xFF163A70)
private val SoftText = Color(0xFF5F6F85)
private val SportDisplayFont = FontFamily(Font(R.font.arista_pro))

/**
 * Рисует едва заметную анимированную линию кардиограммы на фоне.
 * Draws a subtle animated cardiogram line behind the main content.
 */
@Composable
private fun PulseBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse_background")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "pulse_shift"
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.14f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        val baseline = size.height * 0.36f
        val waveWidth = size.width * 0.6f
        val startX = -waveWidth + (size.width + waveWidth) * shift

        fun buildPulsePath(originX: Float): Path {
            return Path().apply {
                moveTo(originX, baseline)
                lineTo(originX + waveWidth * 0.18f, baseline)
                lineTo(originX + waveWidth * 0.26f, baseline - size.height * 0.018f)
                lineTo(originX + waveWidth * 0.34f, baseline + size.height * 0.025f)
                lineTo(originX + waveWidth * 0.40f, baseline - size.height * 0.082f)
                lineTo(originX + waveWidth * 0.47f, baseline + size.height * 0.11f)
                lineTo(originX + waveWidth * 0.56f, baseline - size.height * 0.038f)
                lineTo(originX + waveWidth * 0.68f, baseline)
                lineTo(originX + waveWidth, baseline)
            }
        }

        drawPath(
            path = buildPulsePath(startX),
            color = PulseBlue.copy(alpha = pulseAlpha),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        drawPath(
            path = buildPulsePath(startX - waveWidth - size.width * 0.08f),
            color = PulseBlue.copy(alpha = pulseAlpha * 0.72f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

/**
 * Белая круглая кнопка с анимированным спортивным контуром.
 * White circular action button with an animated sport-inspired border.
 */
@Composable
private fun ActionButtonChrome(
    modifier: Modifier = Modifier,
    holdProgress: Float,
    content: @Composable () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "action_button_ring")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5000, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "action_button_rotation"
    )
    val trackBrush = remember {
        Brush.sweepGradient(
            listOf(ElectricBlue, EnergyOrange, SolarYellow, ElectricBlue)
        )
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokeWidth = 12.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset(
                x = (size.width - diameter) / 2f,
                y = (size.height - diameter) / 2f
            )
            val arcSize = Size(diameter, diameter)

            rotate(rotation) {
                drawArc(
                    brush = trackBrush,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            if (holdProgress > 0f) {
                drawArc(
                    color = EnergyOrange,
                    startAngle = -90f,
                    sweepAngle = holdProgress * 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth + 1.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Surface(
            shape = CircleShape,
            color = CardWhite,
            shadowElevation = 16.dp,
            modifier = Modifier.size(156.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    appTitle: String,
    isRunning: Boolean,
    isPaused: Boolean,
    elapsedMs: Long,
    distanceKm: Float,
    paceText: String,
    goalValueText: String,
    goalSupportingText: String?,
    onHistoryClick: () -> Unit,
    onAnalyticsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onPaceCalculatorClick: () -> Unit,
    onGoalClick: () -> Unit,
    onPrimaryClick: () -> Unit,
    onStopAndReset: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    intervalActive: Boolean = false,
    intervalType: String = "",
    intervalRemainingSec: Int = 0,
    intervalIndex: Int = 0,
    intervalTotal: Int = 0,
    intervalTargetPaceSecPerKm: Int? = null,
    workoutMode: Int = 0,
    scenarioPreview: String = "",
    gpsStatus: MainActivity.GpsStatus = MainActivity.GpsStatus.SEARCHING,
) {
    var showInfoSheet by remember { mutableStateOf(false) }
    val infoSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    @Composable
    fun GpsStatusBadge(gpsStatus: MainActivity.GpsStatus) {
        val (accent, text, iconTint) = when (gpsStatus) {
            MainActivity.GpsStatus.READY -> Triple(
                ElectricBlue,
                "GPS готов",
                ElectricBlue
            )
            MainActivity.GpsStatus.POOR -> Triple(
                SolarYellow,
                "Уточняем сигнал...",
                SolarYellow
            )
            MainActivity.GpsStatus.SEARCHING -> Triple(
                SolarYellow,
                "Поиск спутников...",
                SolarYellow
            )
        }

        Surface(
            color = CardWhite,
            shape = RoundedCornerShape(22.dp),
            shadowElevation = 10.dp,
            modifier = Modifier.height(44.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 18.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.main_screen_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightBg.copy(alpha = 0.5f))
        )

        PulseBackground(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                SportMainTopBar(
                    title = appTitle
                )
            },
            bottomBar = {
                SportStatsPanel(
                    elapsedMs = elapsedMs,
                    distanceKm = distanceKm,
                    paceText = paceText,
                    goalValueText = goalValueText,
                    goalSupportingText = goalSupportingText,
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
                    .padding(inner)
                    .padding(contentPadding)
            ) {

            // Активные кнопки мельче, расположены колонной сверху вниз (с правой стороны)
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 8.dp, start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = onPaceCalculatorClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Calculate,
                        contentDescription = "Калькулятор темпа",
                        modifier = Modifier.size(24.dp),
                        tint = ElectricBlue
                    )
                }
            }

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
                        tint = ElectricBlue
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
                        tint = ElectricBlue
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
                        tint = ElectricBlue
                    )
                }
                IconButton(
                    onClick = onAnalyticsClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.QueryStats,
                        contentDescription = "Аналитика",
                        modifier = Modifier.size(24.dp),
                        tint = ElectricBlue
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 24.dp, end = 24.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // --- Индикатор GPS ---
                if (!isRunning) {
                    GpsStatusBadge(gpsStatus = gpsStatus)
                } else {
                    Spacer(modifier = Modifier.height(32.dp)) // Заглушка, чтобы кнопка не прыгала
                }
                
                Spacer(modifier = Modifier.height(12.dp))

                // --- Главная кнопка Старт / Пауза ---
                Box(modifier = Modifier.offset(y = 20.dp)) {
                    SportBigActionButton(
                        isRunning = isRunning,
                        isPaused = isPaused,
                        onClick = onPrimaryClick,
                        onLongPress = onStopAndReset
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            Text(
                text = "Удерживай для стоп и сохранения",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 12.sp,
                    shadow = Shadow(
                        color = Color(0x99000000),
                        offset = Offset(0f, 2f),
                        blurRadius = 8f
                    )
                ),
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }
            if (showInfoSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showInfoSheet = false },
                    sheetState = infoSheetState
                ) {
                    InfoBottomSheetContent(
                        onDismiss = { showInfoSheet = false }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SportMainTopBar(
    title: String
) {
    androidx.compose.material3.CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = DeepBlue,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = SportDisplayFont
                )
            )
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        )
    )
}

/**
 * Отдельная светлая CTA-кнопка для стартового экрана.
 * Dedicated light CTA button for the main workout screen.
 */
@Composable
private fun SportBigActionButton(
    isRunning: Boolean,
    isPaused: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val label = if (isRunning && !isPaused) "Пауза" else "Старт"

    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        label = "sport_main_btn_scale"
    )
    val holdProgress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 800, easing = LinearEasing),
        label = "sport_main_btn_hold_progress"
    )
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnLongPress by rememberUpdatedState(onLongPress)
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(208.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .size(184.dp)
                .scale(scale)
                .pointerInput(Unit) {
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
            ActionButtonChrome(
                holdProgress = holdProgress,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    RunnerAvatar(
                        isRunning = isRunning,
                        isPaused = isPaused,
                        primaryTint = DeepBlue
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = SportDisplayFont
                        ),
                        color = DeepBlue
                    )
                }
            }
        }
        Text(
            text = if (pressed) "Удерживай для завершения" else "Нажми или удерживай",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 8.dp),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                shadow = Shadow(
                    color = Color(0x99000000),
                    offset = Offset(0f, 2f),
                    blurRadius = 8f
                )
            ),
            color = Color.White
        )
    }
}

@Composable
private fun SportStatsPanel(
    elapsedMs: Long,
    distanceKm: Float,
    paceText: String,
    goalValueText: String,
    goalSupportingText: String?,
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
    Card(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            if (!intervalActive) {
                val showPreview = !isRunning && workoutMode > 0 && scenarioPreview.isNotBlank()

                if (showPreview) {
                    val previewTransition = rememberInfiniteTransition(label = "sport_preview_gradient")
                    val previewOffset by previewTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1000f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(4000, easing = LinearEasing),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                        ),
                        label = "sport_preview_gradient_offset"
                    )
                    val previewBrush = Brush.linearGradient(
                        colors = listOf(ElectricBlue, EnergyOrange, SolarYellow, ElectricBlue),
                        start = Offset(previewOffset, 0f),
                        end = Offset(previewOffset + 600f, 300f),
                        tileMode = TileMode.Repeated
                    )
                    val modeLabel = when (workoutMode) {
                        1 -> "\u26A1 Интервальная"
                        2 -> "\uD83C\uDFAF Комбинированная"
                        4 -> "\uD83C\uDFC1 Забег"
                        else -> ""
                    }
                    val scrollState = rememberScrollState()

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = CardWhite,
                        shadowElevation = 10.dp,
                        modifier = Modifier.fillMaxWidth()
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
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 20.sp
                                    ),
                                    color = DeepBlue
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SportStatTile(
                            icon = Icons.Outlined.Timer,
                            value = formatHms(elapsedMs),
                            label = "Время",
                            valueColor = EnergyOrange,
                            modifier = Modifier.weight(1f)
                        )
                        SportStatTile(
                            icon = Icons.AutoMirrored.Outlined.DirectionsRun,
                            value = formatKm(distanceKm),
                            label = "Дистанция",
                            valueColor = ElectricBlue,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SportStatTile(
                        icon = Icons.Outlined.Speed,
                        value = paceText,
                        label = "Темп",
                        valueColor = DeepBlue,
                        modifier = Modifier.weight(1f)
                    )
                    SportStatTile(
                        icon = Icons.Outlined.Flag,
                        value = goalValueText,
                        label = "Цель",
                        supporting = goalSupportingText,
                        valueColor = DeepBlue,
                        showChevron = true,
                        actionLabel = "Изменить",
                        onClick = onGoalClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                val phase = intervalTypeRu(intervalType)
                val remain = formatMmSs(intervalRemainingSec)
                val series = if (intervalTotal > 0) "$intervalIndex/$intervalTotal" else "—"
                val targetPace = intervalTargetPaceSecPerKm?.let { formatPaceOnly(it) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SportStatTile(
                        icon = Icons.Outlined.Timer,
                        value = formatHms(elapsedMs),
                        label = "Время",
                        valueColor = EnergyOrange,
                        modifier = Modifier.weight(1f)
                    )
                    SportStatTile(
                        icon = Icons.AutoMirrored.Outlined.DirectionsRun,
                        value = phase,
                        label = "Фаза",
                        supporting = "Серия $series",
                        valueColor = ElectricBlue,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SportStatTile(
                        icon = Icons.Outlined.Speed,
                        value = remain,
                        label = "Осталось",
                        supporting = if (targetPace != null) "Цель $targetPace" else null,
                        valueColor = EnergyOrange,
                        modifier = Modifier.weight(1f)
                    )
                    SportStatTile(
                        icon = Icons.Outlined.Flag,
                        value = "Интервалы",
                        label = "Цель",
                        valueColor = DeepBlue,
                        showChevron = true,
                        actionLabel = "Изменить",
                        onClick = onGoalClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Светлая карточка метрики для спортивного дашборда.
 * Light metric card for the sports dashboard.
 */
@Composable
private fun SportStatTile(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    valueColor: Color = DeepBlue,
    showChevron: Boolean = false,
    actionLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val compactValue = value.length > 8
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = CardWhite,
        shadowElevation = 10.dp,
        modifier = modifier.then(
            if (onClick != null) {
                Modifier.clickable { onClick() }
            } else {
                Modifier
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = ElectricBlue
                )
                if (showChevron) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = SoftText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = SportDisplayFont,
                    fontSize = if (compactValue) 15.sp else 24.sp,
                    lineHeight = if (compactValue) 19.sp else 28.sp
                ),
                color = valueColor
            )

            if (!supporting.isNullOrBlank()) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = SoftText
                )
            }

            if (!actionLabel.isNullOrBlank()) {
                val gradientTransition = rememberInfiniteTransition(label = "sport_goal_gradient")
                val gradientOffset by gradientTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1000f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(3000, easing = LinearEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                    ),
                    label = "sport_goal_gradient_offset"
                )
                val gradientBrush = Brush.linearGradient(
                    colors = listOf(ElectricBlue, EnergyOrange, SolarYellow, ElectricBlue),
                    start = Offset(gradientOffset, gradientOffset),
                    end = Offset(gradientOffset + 500f, gradientOffset + 500f),
                    tileMode = TileMode.Repeated
                )

                Text(
                    text = "$label • $actionLabel",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        brush = gradientBrush
                    )
                )
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = SoftText
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
    androidx.compose.material3.CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = DeepBlue,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = SportDisplayFont
                )
            )
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = LightBg,
            scrolledContainerColor = LightBg
        )
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
    val pedometerGranted = remember(refreshTick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
        } else true
    }
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

    val requiredOk = locationGranted && systemLocationEnabled && notificationsGranted && notificationsEnabled && pedometerGranted

    val requestLocation = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }
    val requestPedometer = rememberLauncherForActivityResult(
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
                title = "Шагомер",
                subtitle = "Нужен для страховки от потери сигнала GPS",
                ok = pedometerGranted,
                required = true,
                actionLabel = if (pedometerGranted) "Ок" else "Разрешить",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !pedometerGranted) {
                        requestPedometer.launch(Manifest.permission.ACTIVITY_RECOGNITION)
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
            .height(208.dp), // Место для лент + кнопка
        contentAlignment = Alignment.BottomCenter
    ) {
        // Ленточки (сверху кнопки, смещены вверх)
        Image(
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_medal_ribbons),
            contentDescription = null,
            modifier = Modifier
                .size(112.dp, 64.dp)
                .align(Alignment.TopCenter)
                .offset(y = 8.dp) // немного заходят за кнопку
        )

        // Сама круглая кнопка
        Box(
            modifier = Modifier
                .size(160.dp)
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
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
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
    goalValueText: String,
    goalSupportingText: String?,
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
                        4 -> "\uD83C\uDFC1 \u0417\u0430\u0431\u0435\u0433"
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
                        value = goalValueText,
                        label = "\u0426\u0435\u043B\u044C",
                        supporting = goalSupportingText,
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
                val compactGoalTitle = value.length > 12
                // Action-layout (только для Goal tile): "value     >" / supporting / "label · action"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = value,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (compactGoalTitle) 18.sp else 24.sp,
                            lineHeight = if (compactGoalTitle) 20.sp else 28.sp
                        ),
                        color = Color(0xFF2F243D), // soft text primary
                        maxLines = 2
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
