package com.example.stayer.pathnet.domain

import android.location.Location
import com.example.stayer.pathnet.model.GeoPoint
import com.example.stayer.pathnet.model.ImportedPathGraph
import com.example.stayer.pathnet.model.PathEdge
import com.example.stayer.pathnet.model.PathEdgeSource
import com.example.stayer.pathnet.model.PathGraph
import com.example.stayer.pathnet.model.PathNode
import java.util.PriorityQueue
import java.util.UUID

/**
 * Оркестратор редактирования локального графа.
 * Orchestrates graph edits without leaking logic into the UI.
 */
class PathGraphEditor {
    private val attachThresholdMeters = 20.0
    private val routeNodeThresholdMeters = 24.0
    private val zeroLengthThresholdMeters = 1.5
    private val maxRouteLengthRatio = 1.75
    private val geometryAnchorThresholdMeters = 1.0
    private val maxRouteCorridorDeviationMeters = 12.0

    /**
     * Подготавливает стартовый якорь для нового сегмента или ветки.
     * Prepares a start anchor for a new segment or branch.
     */
    fun prepareStartAnchor(
        graph: PathGraph,
        point: GeoPoint,
        preferAttachToGraph: Boolean,
    ): GraphAnchorResult {
        val attachment = attachOrCreateNode(
            graph = graph,
            point = point,
            preferExistingGraph = preferAttachToGraph,
        )
        return GraphAnchorResult(
            graph = attachment.graph,
            nodeId = attachment.nodeId,
            point = attachment.point,
            message = if (preferAttachToGraph) "Старт ветки выбран" else "Старт сегмента выбран",
        )
    }

    /**
     * Добавляет сегмент между двумя точками с попыткой привязки к импортированным путям.
     * Adds a segment between two points with optional imported-path snapping.
     */
    fun addSegment(
        graph: PathGraph,
        startPoint: GeoPoint,
        endPoint: GeoPoint,
        importedGraph: ImportedPathGraph,
        preferAttachToGraph: Boolean,
    ): GraphEditResult {
        val anchor = prepareStartAnchor(graph, startPoint, preferAttachToGraph)
        return addSegmentFromAnchor(
            graph = anchor.graph,
            startNodeId = anchor.nodeId,
            endPoint = endPoint,
            importedGraph = importedGraph,
        )
    }

    /**
     * Добавляет сегмент от существующего узла к новой точке.
     * Adds a segment from an existing graph node to a new target point.
     */
    fun addSegmentFromAnchor(
        graph: PathGraph,
        startNodeId: String,
        endPoint: GeoPoint,
        importedGraph: ImportedPathGraph,
        preferAttachEndToGraph: Boolean = true,
    ): GraphEditResult {
        val startNode = graph.nodes[startNodeId]
            ?: return GraphEditResult(graph, null, "Стартовый узел не найден")

        var workingGraph = graph
        val endAttachment = attachOrCreateNode(
            graph = workingGraph,
            point = endPoint,
            preferExistingGraph = preferAttachEndToGraph,
        )
        workingGraph = endAttachment.graph

        if (endAttachment.nodeId == startNodeId ||
            distanceMeters(startNode.point, endAttachment.point) < zeroLengthThresholdMeters
        ) {
            return GraphEditResult(
                graph = graph,
                anchorNodeId = startNodeId,
                message = "Выберите следующую точку дальше",
            )
        }

        val snappedGeometry = findSnappedGeometry(
            startPoint = startNode.point,
            endPoint = endAttachment.point,
            importedGraph = importedGraph,
        )

        val canMoveStart = nodeDegree(graph, startNodeId) == 0
        val canMoveEnd = nodeDegree(workingGraph, endAttachment.nodeId) == 0
        if (snappedGeometry != null) {
            if (canMoveStart) {
                workingGraph = moveNode(workingGraph, startNodeId, snappedGeometry.first())
            }
            if (canMoveEnd) {
                workingGraph = moveNode(workingGraph, endAttachment.nodeId, snappedGeometry.last())
            }
        }

        val startNodePoint = workingGraph.nodes[startNodeId]?.point ?: startNode.point
        val endNodePoint = workingGraph.nodes[endAttachment.nodeId]?.point ?: endAttachment.point
        val geometry = if (snappedGeometry == null) {
            listOf(startNodePoint, endNodePoint)
        } else {
            anchorGeometryToNodes(
                geometry = snappedGeometry,
                startPoint = startNodePoint,
                endPoint = endNodePoint,
            )
        }
        val source = if (snappedGeometry == null) {
            PathEdgeSource.MANUAL_FREEFORM
        } else {
            PathEdgeSource.AUTO_SNAPPED
        }

        val edge = PathEdge(
            id = UUID.randomUUID().toString(),
            startNodeId = startNodeId,
            endNodeId = endAttachment.nodeId,
            geometry = geometry,
            lengthMeters = PathLengthCalculator.calculatePolylineLength(geometry),
            source = source,
        )
        val updatedGraph = workingGraph.copy(edges = workingGraph.edges + (edge.id to edge))
        return GraphEditResult(
            graph = cleanupOrphanNodes(updatedGraph),
            anchorNodeId = endAttachment.nodeId,
            message = if (source == PathEdgeSource.AUTO_SNAPPED) {
                "Сегмент привязан к тропинке"
            } else {
                "Создан прямой сегмент"
            },
        )
    }

    /**
     * Удаляет ближайший сегмент.
     * Deletes the nearest edge to the provided point.
     */
    fun deleteNearestEdge(
        graph: PathGraph,
        point: GeoPoint,
        maxDistanceMeters: Double = 24.0,
    ): GraphEditResult {
        val nearest = findNearestEdge(graph, point, maxDistanceMeters)
            ?: return GraphEditResult(graph, null, "Сегмент рядом не найден")
        val updated = cleanupOrphanNodes(graph.copy(edges = graph.edges - nearest.id))
        return GraphEditResult(
            graph = updated,
            anchorNodeId = null,
            message = "Сегмент удален",
        )
    }

    /**
     * Очищает весь граф.
     * Clears the entire graph.
     */
    fun clearGraph(): GraphEditResult {
        return GraphEditResult(
            graph = PathGraph(),
            anchorNodeId = null,
            message = "Сеть очищена",
        )
    }

    /**
     * Выбирает ближайший сегмент для режима изгиба.
     * Finds the nearest edge for bend mode selection.
     */
    fun selectNearestEdge(
        graph: PathGraph,
        point: GeoPoint,
        maxDistanceMeters: Double = 24.0,
    ): PathEdge? = findNearestEdge(graph, point, maxDistanceMeters)

    /**
     * Добавляет контрольную точку на выбранный сегмент.
     * Inserts a control point into the selected edge geometry.
     */
    fun addControlPoint(
        graph: PathGraph,
        edgeId: String,
        point: GeoPoint,
    ): GraphEditResult {
        val edge = graph.edges[edgeId] ?: return GraphEditResult(graph, null, "Сегмент не найден")
        val projection = PathGeometryProjector.nearestPointOnPolyline(point, edge.geometry)
            ?: return GraphEditResult(graph, null, "Не удалось добавить точку")
        val updatedGeometry = edge.geometry.toMutableList().apply {
            add(projection.segmentIndex + 1, point)
        }
        val updatedEdge = edge.copy(
            geometry = updatedGeometry,
            lengthMeters = PathLengthCalculator.calculatePolylineLength(updatedGeometry),
            source = PathEdgeSource.MANUAL_ADJUSTED,
        )
        return GraphEditResult(
            graph = graph.copy(edges = graph.edges + (edgeId to updatedEdge)),
            anchorNodeId = null,
            message = "Добавлена точка изгиба",
        )
    }

    /**
     * Перемещает контрольную точку выбранного сегмента.
     * Moves an interior control point on the selected edge.
     */
    fun moveControlPoint(
        graph: PathGraph,
        edgeId: String,
        pointIndex: Int,
        newPoint: GeoPoint,
    ): GraphEditResult {
        val edge = graph.edges[edgeId] ?: return GraphEditResult(graph, null, "Сегмент не найден")
        if (pointIndex <= 0 || pointIndex >= edge.geometry.lastIndex) {
            return GraphEditResult(graph, null, "Конечные точки не редактируются")
        }
        val updatedGeometry = edge.geometry.toMutableList().apply {
            this[pointIndex] = newPoint
        }
        val updatedEdge = edge.copy(
            geometry = updatedGeometry,
            lengthMeters = PathLengthCalculator.calculatePolylineLength(updatedGeometry),
            source = PathEdgeSource.MANUAL_ADJUSTED,
        )
        return GraphEditResult(
            graph = graph.copy(edges = graph.edges + (edgeId to updatedEdge)),
            anchorNodeId = null,
            message = "Сегмент скорректирован",
        )
    }

    /**
     * Находит ближайший сегмент к точке.
     * Finds the nearest edge near a point.
     */
    private fun findNearestEdge(
        graph: PathGraph,
        point: GeoPoint,
        maxDistanceMeters: Double,
    ): PathEdge? {
        return graph.edges.values
            .mapNotNull { edge ->
                val projection = PathGeometryProjector.nearestPointOnPolyline(point, edge.geometry)
                    ?: return@mapNotNull null
                if (projection.distanceMeters <= maxDistanceMeters) edge to projection.distanceMeters else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    /**
     * Прикрепляет точку к существующему графу или создает новый узел.
     * Attaches a point to the existing graph or creates a new node.
     */
    private fun attachOrCreateNode(
        graph: PathGraph,
        point: GeoPoint,
        preferExistingGraph: Boolean,
    ): AttachmentResult {
        if (graph.edges.isNotEmpty() && preferExistingGraph) {
            graph.nodes.values
                .map { node -> node to distanceMeters(point, node.point) }
                .filter { it.second <= attachThresholdMeters }
                .minByOrNull { it.second }
                ?.let { (node, _) ->
                    return AttachmentResult(graph, node.id, node.point)
                }

            findNearestEdge(graph, point, attachThresholdMeters)?.let { edge ->
                val projection = PathGeometryProjector.nearestPointOnPolyline(point, edge.geometry)
                    ?: return@let
                val splitResult = splitEdge(graph, edge, projection.point, projection.segmentIndex, projection.segmentFraction)
                return AttachmentResult(splitResult.graph, splitResult.insertedNodeId, projection.point)
            }
        }

        val newNode = PathNode(id = UUID.randomUUID().toString(), point = point)
        return AttachmentResult(
            graph = graph.copy(nodes = graph.nodes + (newNode.id to newNode)),
            nodeId = newNode.id,
            point = newNode.point,
        )
    }

    /**
     * Разрезает сегмент в точке привязки.
     * Splits an existing edge at the attachment point.
     */
    private fun splitEdge(
        graph: PathGraph,
        edge: PathEdge,
        splitPoint: GeoPoint,
        segmentIndex: Int,
        segmentFraction: Double,
    ): SplitResult {
        if (segmentFraction == 0.0) {
            return SplitResult(graph, edge.startNodeId)
        }
        if (segmentFraction == 1.0) {
            return SplitResult(graph, edge.endNodeId)
        }

        val insertedNode = PathNode(UUID.randomUUID().toString(), splitPoint)
        val before = mutableListOf<GeoPoint>()
        val after = mutableListOf<GeoPoint>()

        for (index in 0..segmentIndex) {
            before += edge.geometry[index]
        }
        before += splitPoint

        after += splitPoint
        for (index in segmentIndex + 1 until edge.geometry.size) {
            after += edge.geometry[index]
        }

        val firstEdge = edge.copy(
            id = UUID.randomUUID().toString(),
            endNodeId = insertedNode.id,
            geometry = before,
            lengthMeters = PathLengthCalculator.calculatePolylineLength(before),
        )
        val secondEdge = edge.copy(
            id = UUID.randomUUID().toString(),
            startNodeId = insertedNode.id,
            geometry = after,
            lengthMeters = PathLengthCalculator.calculatePolylineLength(after),
        )
        val updatedEdges = graph.edges - edge.id + mapOf(
            firstEdge.id to firstEdge,
            secondEdge.id to secondEdge,
        )
        val updatedNodes = graph.nodes + (insertedNode.id to insertedNode)
        return SplitResult(
            graph = graph.copy(nodes = updatedNodes, edges = updatedEdges),
            insertedNodeId = insertedNode.id,
        )
    }

    /**
     * Удаляет узлы без привязанных сегментов.
     * Removes nodes that are no longer referenced by edges.
     */
    private fun cleanupOrphanNodes(graph: PathGraph): PathGraph {
        val usedNodeIds = buildSet {
            graph.edges.values.forEach { edge ->
                add(edge.startNodeId)
                add(edge.endNodeId)
            }
        }
        return graph.copy(nodes = graph.nodes.filterKeys { it in usedNodeIds })
    }

    /**
     * Возвращает число сегментов, связанных с узлом.
     * Returns the number of edges connected to a node.
     */
    private fun nodeDegree(graph: PathGraph, nodeId: String): Int {
        return graph.edges.values.count { it.startNodeId == nodeId || it.endNodeId == nodeId }
    }

    /**
     * Перемещает узел без изменения остальных узлов графа.
     * Moves a node without touching the rest of the graph.
     */
    private fun moveNode(
        graph: PathGraph,
        nodeId: String,
        point: GeoPoint,
    ): PathGraph {
        val node = graph.nodes[nodeId] ?: return graph
        return graph.copy(nodes = graph.nodes + (nodeId to node.copy(point = point)))
    }

    /**
     * Ищет маршрут по импортированным тропинкам.
     * Attempts to build snapped geometry from imported ways.
     */
    private fun findSnappedGeometry(
        startPoint: GeoPoint,
        endPoint: GeoPoint,
        importedGraph: ImportedPathGraph,
    ): List<GeoPoint>? {
        if (importedGraph.nodes.isEmpty() || importedGraph.ways.isEmpty()) return null

        val startNode = nearestImportedNode(startPoint, importedGraph.nodes) ?: return null
        val endNode = nearestImportedNode(endPoint, importedGraph.nodes) ?: return null
        if (startNode.second > routeNodeThresholdMeters || endNode.second > routeNodeThresholdMeters) {
            return null
        }

        val adjacency = mutableMapOf<String, MutableList<Pair<String, Double>>>()
        for (way in importedGraph.ways) {
            for (index in 0 until way.nodeIds.lastIndex) {
                val fromId = way.nodeIds[index]
                val toId = way.nodeIds[index + 1]
                val from = importedGraph.nodes[fromId] ?: continue
                val to = importedGraph.nodes[toId] ?: continue
                val distance = distanceMeters(from, to)
                adjacency.getOrPut(fromId) { mutableListOf() }.add(toId to distance)
                adjacency.getOrPut(toId) { mutableListOf() }.add(fromId to distance)
            }
        }

        val routeNodeIds = dijkstra(startNode.first, endNode.first, adjacency) ?: return null
        val routeGeometry = routeNodeIds.mapNotNull(importedGraph.nodes::get)
        if (routeGeometry.size < 2) return null

        val snappedLength = PathLengthCalculator.calculatePolylineLength(routeGeometry)
        val directLength = distanceMeters(startPoint, endPoint)
        if (directLength > 0.0 && snappedLength > directLength * maxRouteLengthRatio) {
            return null
        }
        if (!staysNearDirectSegment(routeGeometry, startPoint, endPoint)) {
            return null
        }

        return routeGeometry
    }

    /**
     * Отсекает автопривязку, если найденный маршрут слишком уходит в сторону от линии между тапами.
     * Rejects snapped routes that drift too far away from the user's direct intent line.
     */
    private fun staysNearDirectSegment(
        geometry: List<GeoPoint>,
        startPoint: GeoPoint,
        endPoint: GeoPoint,
    ): Boolean {
        val directGeometry = listOf(startPoint, endPoint)
        return geometry.all { point ->
            val projection = PathGeometryProjector.nearestPointOnPolyline(point, directGeometry)
                ?: return false
            projection.distanceMeters <= maxRouteCorridorDeviationMeters
        }
    }

    /**
     * Привязывает геометрию авто-сегмента к фактическим узлам сети, чтобы не было разрыва с предыдущим сегментом.
     * Anchors snapped geometry to actual graph nodes to avoid visual gaps with adjacent segments.
     */
    private fun anchorGeometryToNodes(
        geometry: List<GeoPoint>,
        startPoint: GeoPoint,
        endPoint: GeoPoint,
    ): List<GeoPoint> {
        if (geometry.isEmpty()) return listOf(startPoint, endPoint)

        val anchored = geometry.toMutableList()
        if (distanceMeters(anchored.first(), startPoint) > geometryAnchorThresholdMeters) {
            anchored.add(0, startPoint)
        } else {
            anchored[0] = startPoint
        }

        if (distanceMeters(anchored.last(), endPoint) > geometryAnchorThresholdMeters) {
            anchored.add(endPoint)
        } else {
            anchored[anchored.lastIndex] = endPoint
        }

        return anchored
    }

    /**
     * Находит ближайший импортированный узел.
     * Finds the closest imported node.
     */
    private fun nearestImportedNode(
        point: GeoPoint,
        nodes: Map<String, GeoPoint>,
    ): Pair<String, Double>? {
        return nodes.entries
            .map { entry -> entry.key to distanceMeters(point, entry.value) }
            .minByOrNull { it.second }
    }

    /**
     * Ищет кратчайший путь по графу.
     * Runs Dijkstra on the imported graph.
     */
    private fun dijkstra(
        start: String,
        end: String,
        adjacency: Map<String, List<Pair<String, Double>>>,
    ): List<String>? {
        val distances = mutableMapOf(start to 0.0)
        val previous = mutableMapOf<String, String>()
        val queue = PriorityQueue(compareBy<Pair<String, Double>> { it.second })
        queue.add(start to 0.0)

        while (queue.isNotEmpty()) {
            val next = queue.poll() ?: break
            val (nodeId, currentDistance) = next
            if (nodeId == end) break
            if (currentDistance > distances.getOrDefault(nodeId, Double.MAX_VALUE)) continue
            adjacency[nodeId].orEmpty().forEach { (nextId, weight) ->
                val candidate = currentDistance + weight
                if (candidate < distances.getOrDefault(nextId, Double.MAX_VALUE)) {
                    distances[nextId] = candidate
                    previous[nextId] = nodeId
                    queue.add(nextId to candidate)
                }
            }
        }

        if (end !in distances) return null
        val path = mutableListOf<String>()
        var current: String? = end
        while (current != null) {
            path += current
            current = previous[current]
        }
        return path.asReversed()
    }

    /**
     * Считает геодезическое расстояние между двумя точками.
     * Calculates geodesic distance between two points.
     */
    private fun distanceMeters(start: GeoPoint, end: GeoPoint): Double {
        val result = FloatArray(1)
        Location.distanceBetween(start.lat, start.lon, end.lat, end.lon, result)
        return result[0].toDouble()
    }

    /**
     * Находит лучший конец сети для продолжения маршрута.
     * Finds the best route endpoint to continue the current network.
     */
    fun findContinuationAnchorNodeId(
        graph: PathGraph,
        point: GeoPoint,
    ): String? {
        val candidateNodes = graph.nodes.values.filter { nodeDegree(graph, it.id) <= 1 }
            .ifEmpty { graph.nodes.values.toList() }
        return candidateNodes
            .minByOrNull { node -> distanceMeters(point, node.point) }
            ?.id
    }
}

/**
 * Результат изменения графа.
 * Result of a graph mutation in the editor domain.
 */
data class GraphEditResult(
    val graph: PathGraph,
    val anchorNodeId: String?,
    val message: String,
)

/**
 * Результат выбора стартового якоря.
 * Result of preparing a start anchor.
 */
data class GraphAnchorResult(
    val graph: PathGraph,
    val nodeId: String,
    val point: GeoPoint,
    val message: String,
)

private data class AttachmentResult(
    val graph: PathGraph,
    val nodeId: String,
    val point: GeoPoint,
)

private data class SplitResult(
    val graph: PathGraph,
    val insertedNodeId: String,
)
