package com.example.stayer.pathnet.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.stayer.pathnet.diagnostics.PathNetLogger
import com.example.stayer.pathnet.model.PathEditorMode
import com.example.stayer.ui.theme.StayerTheme

/**
 * Полноэкранная Activity редактора маршрутной сети.
 * Full-screen activity hosting the route network editor.
 */
class RouteMapActivity : ComponentActivity() {
    private val viewModel: RouteMapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PathNetLogger.info("RouteMapActivity created")
        setContent {
            StayerTheme {
                val state by viewModel.state.collectAsState()
                RouteMapScreen(
                    state = state,
                    onBack = {
                        PathNetLogger.info("UI action: back")
                        finish()
                    },
                    onMapTap = viewModel::onMapTap,
                    onAddControlPoint = viewModel::addControlPoint,
                    onMoveControlPoint = viewModel::moveControlPoint,
                    onViewportChanged = viewModel::onViewportChanged,
                    onSetMode = { mode ->
                        logModeAction(mode)
                        viewModel.setMode(mode)
                    },
                    onClearPending = {
                        PathNetLogger.info("UI action: clear pending start")
                        viewModel.clearPendingStart()
                    },
                    onStartBranch = {
                        PathNetLogger.info("UI action: start branch")
                        viewModel.startBranch()
                    },
                    onRefreshPaths = {
                        PathNetLogger.info("UI action: refresh paths")
                        viewModel.refreshVisiblePaths()
                    },
                    onCheckOverpass = {
                        PathNetLogger.info("UI action: check overpass")
                        viewModel.checkOverpass()
                    },
                    onSave = {
                        PathNetLogger.info("UI action: save graph")
                        viewModel.saveGraph()
                    },
                    onClearAll = {
                        PathNetLogger.warn("UI action: clear all graph")
                        viewModel.clearGraph()
                    },
                    onFitGraph = {
                        PathNetLogger.info("UI action: fit graph")
                        viewModel.requestFitGraph()
                    },
                    onExportNetwork = {
                        PathNetLogger.info("UI action: export network")
                        viewModel.exportNetworkForReplay()
                    },
                )
            }
        }
    }

    /**
     * Логирует выбор режима редактора.
     * Logs editor mode selection.
     */
    private fun logModeAction(mode: PathEditorMode) {
        PathNetLogger.info("UI action: set mode to $mode")
    }
}
