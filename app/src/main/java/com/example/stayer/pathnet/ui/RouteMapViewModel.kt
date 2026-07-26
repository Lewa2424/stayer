package com.example.stayer.pathnet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.stayer.pathnet.data.PathNetworkRepository
import com.example.stayer.pathnet.data.RoomPathNetworkRepository
import com.example.stayer.pathnet.data.local.PathNetDatabase
import com.example.stayer.pathnet.data.remote.OsmPathLoader
import com.example.stayer.pathnet.data.remote.OsmPathLoader.PathLoadResult
import com.example.stayer.pathnet.data.remote.OsmPathLoader.ViewportCheckBounds
import com.example.stayer.pathnet.diagnostics.PathNetLogger
import com.example.stayer.pathnet.domain.GraphEditResult
import com.example.stayer.pathnet.domain.PathGraphEditor
import com.example.stayer.pathnet.domain.PathLengthCalculator
import com.example.stayer.pathnet.model.GeoPoint
import com.example.stayer.pathnet.model.ImportedPathGraph
import com.example.stayer.pathnet.model.LoadedArea
import com.example.stayer.pathnet.model.PathEditorMode
import com.example.stayer.pathnet.model.RouteMapUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel полноэкранного редактора сети.
 * Screen-level state holder for the full-screen route editor.
 */
class RouteMapViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository: PathNetworkRepository = RoomPathNetworkRepository(
        PathNetDatabase.getInstance(application).pathNetDao(),
    )
    private val pathLoader = OsmPathLoader(application)
    private val editor = PathGraphEditor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val viewportCachePrecision = 3
    private val minViewportReloadShift = 0.00035

    private val _state = MutableStateFlow(RouteMapUiState())
    val state: StateFlow<RouteMapUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var currentViewport: ViewportBounds? = null
    private var currentViewportKey: String? = null

    init {
        scope.launch {
            val graph = repository.loadGraph()
            val areas = repository.loadLoadedAreas()
            val importedGraph = repository.loadImportedGraph()
            _state.value = _state.value.copy(
                graph = graph,
                importedGraph = importedGraph,
                loadedAreas = areas,
                totalLengthMeters = PathLengthCalculator.calculateGraphLength(graph),
            )
        }
    }

    /**
     * Меняет текущий режим редактора.
     * Switches the active editor mode.
     */
    fun setMode(mode: PathEditorMode) {
        PathNetLogger.info("ViewModel setMode: $mode")
        _state.value = _state.value.copy(
            mode = mode,
            selectedEdgeId = if (mode == PathEditorMode.BEND) _state.value.selectedEdgeId else null,
            infoMessage = null,
        )
    }

    /**
     * Обрабатывает тап по карте.
     * Handles a map tap according to the current mode.
     */
    fun onMapTap(point: GeoPoint) {
        PathNetLogger.debug("Map tap: mode=${_state.value.mode}, point=${PathNetLogger.point(point)}")
        when (_state.value.mode) {
            PathEditorMode.DELETE -> {
                applyGraphResult(editor.deleteNearestEdge(_state.value.graph, point))
            }

            PathEditorMode.BEND -> {
                val selected = editor.selectNearestEdge(_state.value.graph, point)
                _state.value = _state.value.copy(
                    selectedEdgeId = selected?.id,
                    infoMessage = if (selected == null) {
                        "Сегмент рядом не найден"
                    } else {
                        "Сегмент выбран для изгиба"
                    },
                )
            }

            PathEditorMode.ADD_BRANCH,
            PathEditorMode.EXTEND -> addOrContinueSegment(point)
        }
    }

    /**
     * Добавляет контрольную точку на выбранный сегмент.
     * Inserts a control point on the selected edge.
     */
    fun addControlPoint(point: GeoPoint) {
        PathNetLogger.info(
            "Add control point: selectedEdge=${_state.value.selectedEdgeId}, point=${PathNetLogger.point(point)}",
        )
        val edgeId = _state.value.selectedEdgeId ?: return
        val result = editor.addControlPoint(_state.value.graph, edgeId, point)
        applyGraphResult(result)
        _state.value = _state.value.copy(selectedEdgeId = edgeId)
    }

    /**
     * Перемещает контрольную точку выбранного сегмента.
     * Moves a selected control point.
     */
    fun moveControlPoint(edgeId: String, pointIndex: Int, point: GeoPoint) {
        PathNetLogger.info(
            "Move control point: edge=$edgeId, index=$pointIndex, point=${PathNetLogger.point(point)}",
        )
        val result = editor.moveControlPoint(_state.value.graph, edgeId, pointIndex, point)
        applyGraphResult(result)
        _state.value = _state.value.copy(selectedEdgeId = edgeId)
    }

    /**
     * Запоминает текущий viewport, но не запускает загрузку автоматически.
     * Stores the current viewport without automatically loading paths.
     */
    fun onViewportChanged(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ) {
        currentViewport = ViewportBounds(
            minLat = minLat,
            minLon = minLon,
            maxLat = maxLat,
            maxLon = maxLon,
        )
        currentViewportKey = buildViewportKey(minLat, minLon, maxLat, maxLon)
        PathNetLogger.debug(
            "Viewport stored: key=$currentViewportKey, bounds=${PathNetLogger.bounds(minLat, minLon, maxLat, maxLon)}",
        )
    }

    /**
     * Явно обновляет тропинки в текущем viewport.
     * Explicitly refreshes paths in the current viewport.
     */
    fun refreshVisiblePaths(force: Boolean = false) {
        val viewport = currentViewport
        val viewportKey = currentViewportKey
        if (viewport == null || viewportKey == null) {
            _state.value = _state.value.copy(infoMessage = "Сначала остановите карту на нужной области")
            PathNetLogger.warn("Refresh skipped: viewport is not ready")
            return
        }
        if (_state.value.isLoadingPaths) {
            PathNetLogger.warn("Refresh skipped: load already in progress")
            return
        }
        if (!force && isAreaAlreadyLoaded(viewport)) {
            _state.value = _state.value.copy(infoMessage = "Эта область уже загружена")
            PathNetLogger.info("Refresh skipped: area already loaded, key=$viewportKey")
            return
        }

        loadJob?.cancel()
        loadJob = scope.launch {
            PathNetLogger.info(
                "Viewport load start: key=$viewportKey, bounds=${viewport.asLogString()}",
            )
            _state.value = _state.value.copy(isLoadingPaths = true, infoMessage = null)
            try {
                val imported = withContext(Dispatchers.IO) {
                    pathLoader.loadVisiblePaths(
                        minLat = viewport.minLat,
                        minLon = viewport.minLon,
                        maxLat = viewport.maxLat,
                        maxLon = viewport.maxLon,
                    )
                }
                val area = LoadedArea(
                    id = viewportKey,
                    minLat = viewport.minLat,
                    minLon = viewport.minLon,
                    maxLat = viewport.maxLat,
                    maxLon = viewport.maxLon,
                    loadedAt = System.currentTimeMillis(),
                )
                val areas = if (imported.warningMessage == null) {
                    (_state.value.loadedAreas + area).distinctBy { it.id }
                } else {
                    _state.value.loadedAreas
                }
                val mergedImportedGraph = mergeImportedGraphs(_state.value.importedGraph, imported.graph)
                withContext(Dispatchers.IO) {
                    repository.saveImportedGraph(mergedImportedGraph)
                    repository.saveLoadedAreas(areas)
                }
                _state.value = _state.value.copy(
                    importedGraph = mergedImportedGraph,
                    loadedAreas = areas,
                    isLoadingPaths = false,
                    infoMessage = buildLoadMessage(imported),
                )
                PathNetLogger.info(
                    "Viewport load success: ways=${imported.graph.ways.size}, nodes=${imported.graph.nodes.size}, cachedAreas=${areas.size}",
                )
            } catch (error: CancellationException) {
                PathNetLogger.warn("Viewport load cancelled: key=$viewportKey")
                _state.value = _state.value.copy(
                    isLoadingPaths = false,
                    infoMessage = "Загрузка отменена",
                )
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    isLoadingPaths = false,
                    infoMessage = "OSM: ${error.message ?: "ошибка загрузки"}",
                )
                PathNetLogger.error(
                    "Viewport load failed: bounds=${viewport.asLogString()}",
                    error,
                )
            }
        }
    }

    private fun buildLoadMessage(result: PathLoadResult): String {
        val base = if (result.graph.ways.isEmpty()) {
                        "В этой области OSM-тропинки не найдены"
                    } else {
                        "Тропинки обновлены: ${result.graph.ways.size}"
                    }
        return result.warningMessage?.let { "$base. $it" } ?: base
    }

    /**
     * Проверяет доступность Overpass без изменения локальной сети.
     * Checks Overpass reachability without changing the local graph.
     */
    fun checkOverpass() {
        if (_state.value.isLoadingPaths) {
            PathNetLogger.warn("Overpass check skipped: load already in progress")
            return
        }

        val viewport = currentViewport
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.value = _state.value.copy(isLoadingPaths = true, infoMessage = "Проверка Overpass...")
            try {
                val result = withContext(Dispatchers.IO) {
                    pathLoader.diagnoseOverpass(
                        bounds = viewport?.toCheckBounds(),
                    )
                }
                _state.value = _state.value.copy(
                    isLoadingPaths = false,
                    infoMessage = result,
                )
                PathNetLogger.info("Overpass check success: $result")
            } catch (error: CancellationException) {
                _state.value = _state.value.copy(
                    isLoadingPaths = false,
                    infoMessage = "Проверка отменена",
                )
                PathNetLogger.warn("Overpass check cancelled")
            } catch (error: Exception) {
                val message = "Overpass check failed: ${error.message ?: "ошибка"}"
                _state.value = _state.value.copy(
                    isLoadingPaths = false,
                    infoMessage = message,
                )
                PathNetLogger.error(message, error)
            }
        }
    }

    /**
     * Сохраняет текущий draft-граф.
     * Persists the current draft graph and loaded areas.
     */
    fun saveGraph() {
        PathNetLogger.info("Save graph requested: edges=${_state.value.graph.edges.size}")
        scope.launch {
            repository.saveGraph(_state.value.graph)
            repository.saveImportedGraph(_state.value.importedGraph)
            repository.saveLoadedAreas(_state.value.loadedAreas)
            _state.value = _state.value.copy(infoMessage = "Сеть сохранена")
            PathNetLogger.info(
                "Graph saved: edges=${_state.value.graph.edges.size}, importedWays=${_state.value.importedGraph.ways.size}, areas=${_state.value.loadedAreas.size}",
            )
        }
    }

    /**
     * Очищает весь draft-граф.
     * Clears the current draft graph.
     */
    fun clearGraph() {
        PathNetLogger.warn("Clear graph requested")
        applyGraphResult(editor.clearGraph())
    }

    /**
     * Просит карту показать всю текущую сеть.
     * Requests the map to fit the whole current graph.
     */
    fun requestFitGraph() {
        PathNetLogger.info("Fit graph requested: edges=${_state.value.graph.edges.size}")
        _state.value = _state.value.copy(
            fitGraphRequestVersion = _state.value.fitGraphRequestVersion + 1L,
            infoMessage = if (_state.value.graph.edges.isEmpty()) {
                "Сеть пока пустая"
            } else {
                "Показана вся сеть"
            },
        )
    }

    /**
     * Переключает экран в режим добавления ветки.
     * Switches the screen into branch mode.
     */
    fun startBranch() {
        PathNetLogger.info("Start branch requested")
        _state.value = _state.value.copy(
            mode = PathEditorMode.ADD_BRANCH,
            pendingStartNodeId = null,
            selectedEdgeId = null,
            infoMessage = "Выберите старт ветки на существующей сети",
        )
    }

    /**
     * Сбрасывает текущее продолжение.
     * Clears the in-progress chain anchor.
     */
    fun clearPendingStart() {
        PathNetLogger.info("Clear pending start requested")
        _state.value = _state.value.copy(
            pendingStartNodeId = null,
            infoMessage = "Старт сброшен",
        )
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    /**
     * Добавляет следующий сегмент в цепочку.
     * Adds the next segment to the current chain.
     */
    private fun addOrContinueSegment(point: GeoPoint) {
        val currentState = _state.value
        val pendingId = currentState.pendingStartNodeId
        if (pendingId == null) {
            if (currentState.mode == PathEditorMode.EXTEND && currentState.graph.edges.isNotEmpty()) {
                val continuationAnchorId = editor.findContinuationAnchorNodeId(
                    graph = currentState.graph,
                    point = point,
                )
                if (continuationAnchorId != null) {
                    PathNetLogger.info(
                        "Auto-continue from nearest endpoint: startNodeId=$continuationAnchorId, endPoint=${PathNetLogger.point(point)}",
                    )
                    applyGraphResult(
                        editor.addSegmentFromAnchor(
                            graph = currentState.graph,
                            startNodeId = continuationAnchorId,
                            endPoint = point,
                            importedGraph = currentState.importedGraph,
                            preferAttachEndToGraph = false,
                        ),
                    )
                    return
                }
            }

            PathNetLogger.info("Prepare start anchor: point=${PathNetLogger.point(point)}, mode=${currentState.mode}")
            val anchor = editor.prepareStartAnchor(
                graph = currentState.graph,
                point = point,
                preferAttachToGraph = currentState.mode == PathEditorMode.ADD_BRANCH ||
                    (currentState.mode == PathEditorMode.EXTEND && currentState.graph.edges.isNotEmpty()),
            )
            _state.value = currentState.copy(
                graph = anchor.graph,
                pendingStartNodeId = anchor.nodeId,
                totalLengthMeters = PathLengthCalculator.calculateGraphLength(anchor.graph),
                infoMessage = anchor.message,
            )
            return
        }

        PathNetLogger.info(
            "Add segment from anchor: startNodeId=$pendingId, endPoint=${PathNetLogger.point(point)}",
        )
        applyGraphResult(
            editor.addSegmentFromAnchor(
                graph = currentState.graph,
                startNodeId = pendingId,
                endPoint = point,
                importedGraph = currentState.importedGraph,
                preferAttachEndToGraph = currentState.mode == PathEditorMode.ADD_BRANCH,
            ),
        )
    }

    /**
     * Применяет результат изменения графа к UI-состоянию.
     * Applies a graph mutation result to the current UI state.
     */
    private fun applyGraphResult(result: GraphEditResult) {
        PathNetLogger.info(
            "Graph result: message='${result.message}', edges=${result.graph.edges.size}, nodes=${result.graph.nodes.size}, anchor=${result.anchorNodeId}",
        )
        _state.value = _state.value.copy(
            graph = result.graph,
            pendingStartNodeId = result.anchorNodeId,
            totalLengthMeters = PathLengthCalculator.calculateGraphLength(result.graph),
            infoMessage = result.message,
        )
    }

    /**
     * Объединяет временные графы видимых областей.
     * Merges imported graphs gathered from multiple viewports.
     */
    private fun mergeImportedGraphs(
        current: ImportedPathGraph,
        incoming: ImportedPathGraph,
    ): ImportedPathGraph {
        return ImportedPathGraph(
            nodes = current.nodes + incoming.nodes,
            ways = (current.ways + incoming.ways).distinctBy { it.id },
        )
    }

    /**
     * Округляет значение для ключа области.
     * Rounds viewport coordinates for a stable cache key.
     */
    private fun formatKey(value: Double): String {
        return String.format("%.${viewportCachePrecision}f", value)
    }

    /**
     * Строит стабильный ключ viewport.
     * Builds a stable viewport key.
     */
    private fun buildViewportKey(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): String {
        return "${formatKey(minLat)}:${formatKey(minLon)}:${formatKey(maxLat)}:${formatKey(maxLon)}"
    }

    /**
     * Проверяет, покрывается ли viewport уже загруженной областью.
     * Checks whether the viewport is already covered by a loaded area.
     */
    private fun isAreaAlreadyLoaded(bounds: ViewportBounds): Boolean {
        if (_state.value.importedGraph.ways.isEmpty()) {
            return false
        }
        return _state.value.loadedAreas.any { area ->
            area.minLat <= bounds.minLat + minViewportReloadShift &&
                area.minLon <= bounds.minLon + minViewportReloadShift &&
                area.maxLat >= bounds.maxLat - minViewportReloadShift &&
                area.maxLon >= bounds.maxLon - minViewportReloadShift
        }
    }

    /**
     * Текущая видимая область карты.
     * Current visible map bounds.
     */
    private data class ViewportBounds(
        val minLat: Double,
        val minLon: Double,
        val maxLat: Double,
        val maxLon: Double,
    ) {
        fun asLogString(): String {
            return PathNetLogger.bounds(minLat, minLon, maxLat, maxLon)
        }

        fun toCheckBounds(): ViewportCheckBounds {
            return ViewportCheckBounds(
                minLat = minLat,
                minLon = minLon,
                maxLat = maxLat,
                maxLon = maxLon,
            )
        }
    }
}
