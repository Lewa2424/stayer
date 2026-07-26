package com.example.stayer.pathnet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stayer.pathnet.model.GeoPoint
import com.example.stayer.pathnet.model.PathEditorMode
import com.example.stayer.pathnet.model.RouteMapUiState

/**
 * Compose-экран полноэкранной карты с overlay-управлением.
 * Compose screen for the full-screen route editor with overlay controls.
 */
@Composable
fun RouteMapScreen(
    state: RouteMapUiState,
    onBack: () -> Unit,
    onMapTap: (GeoPoint) -> Unit,
    onAddControlPoint: (GeoPoint) -> Unit,
    onMoveControlPoint: (String, Int, GeoPoint) -> Unit,
    onViewportChanged: (Double, Double, Double, Double) -> Unit,
    onSetMode: (PathEditorMode) -> Unit,
    onClearPending: () -> Unit,
    onStartBranch: () -> Unit,
    onRefreshPaths: () -> Unit,
    onCheckOverpass: () -> Unit,
    onSave: () -> Unit,
    onClearAll: () -> Unit,
    onFitGraph: () -> Unit,
) {
    var showActionsMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        RouteMapView(
            modifier = Modifier.fillMaxSize(),
            state = state,
            onMapTap = onMapTap,
            onAddControlPoint = onAddControlPoint,
            onMoveControlPoint = onMoveControlPoint,
            onViewportChanged = onViewportChanged,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OverlayCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        CompactButton(text = "Назад", onClick = onBack)
                        CompactButton(text = "Сохранить", onClick = onSave)
                        Box {
                            CompactButton(text = "Действия", onClick = { showActionsMenu = true })
                            DropdownMenu(
                                expanded = showActionsMenu,
                                onDismissRequest = { showActionsMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Обновить тропинки") },
                                    onClick = {
                                        onRefreshPaths()
                                        showActionsMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Проверить Overpass") },
                                    onClick = {
                                        onCheckOverpass()
                                        showActionsMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Продолжить") },
                                    onClick = {
                                        onSetMode(PathEditorMode.EXTEND)
                                        showActionsMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Добавить ветку") },
                                    onClick = {
                                        onStartBranch()
                                        showActionsMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Изгиб") },
                                    onClick = {
                                        onSetMode(PathEditorMode.BEND)
                                        showActionsMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Удалить сегмент") },
                                    onClick = {
                                        onSetMode(PathEditorMode.DELETE)
                                        showActionsMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Сброс старта") },
                                    onClick = {
                                        onClearPending()
                                        showActionsMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Показать всю сеть") },
                                    onClick = {
                                        onFitGraph()
                                        showActionsMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Очистить всё") },
                                    onClick = {
                                        onClearAll()
                                        showActionsMenu = false
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        text = "Режим: ${state.mode.label()}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }

        OverlayCard(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Сегментов: ${state.graph.edges.size}")
                Text("Длина сети: ${"%.0f".format(state.totalLengthMeters)} м")
                Text(
                    text = when {
                        state.isLoadingPaths -> "Загрузка тропинок..."
                        state.infoMessage != null -> state.infoMessage
                        else -> "Остановите карту и нажмите «Обновить тропинки»"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Компактная служебная кнопка overlay-панели.
 * Compact utility button for the overlay panel.
 */
@Composable
private fun CompactButton(
    text: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            maxLines = 2,
        )
    }
}

/**
 * Карточка overlay-панели поверх карты.
 * Shared overlay card style drawn over the map.
 */
@Composable
private fun OverlayCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.92f),
        shadowElevation = 8.dp,
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

/**
 * Русское имя режима редактора.
 * Localized label for an editor mode.
 */
private fun PathEditorMode.label(): String {
    return when (this) {
        PathEditorMode.EXTEND -> "Продолжение"
        PathEditorMode.ADD_BRANCH -> "Добавление ветки"
        PathEditorMode.BEND -> "Изгиб"
        PathEditorMode.DELETE -> "Удаление"
    }
}
