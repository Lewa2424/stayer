package com.example.stayer.pathnet.model

/**
 * Состояние экрана редактора сети.
 * State exposed by the route editor screen.
 */
data class RouteMapUiState(
    val graph: PathGraph = PathGraph(),
    val importedGraph: ImportedPathGraph = ImportedPathGraph(),
    val loadedAreas: List<LoadedArea> = emptyList(),
    val mode: PathEditorMode = PathEditorMode.EXTEND,
    val pendingStartNodeId: String? = null,
    val selectedEdgeId: String? = null,
    val totalLengthMeters: Double = 0.0,
    val isLoadingPaths: Boolean = false,
    val infoMessage: String? = null,
    val fitGraphRequestVersion: Long = 0L,
)
