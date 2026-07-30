package com.example.stayer.pathnet.domain

import com.example.stayer.pathnet.model.GeoPoint
import com.example.stayer.pathnet.model.PathEdge
import com.example.stayer.pathnet.model.PathGraph
import java.security.MessageDigest

/**
 * Топологические проверки и безопасный merge совпадающих endpoints.
 * Topology checks and safe merge of coincident endpoints.
 */
object PathGraphTopology {
    /**
     * Пары узлов ближе [thresholdMeters], но с разными id.
     * Node pairs closer than [thresholdMeters] with different ids.
     */
    fun findCoincidentNodePairs(
        graph: PathGraph,
        thresholdMeters: Double = 2.0,
    ): List<Pair<String, String>> {
        val nodes = graph.nodes.values.toList()
        val pairs = mutableListOf<Pair<String, String>>()
        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val a = nodes[i]
                val b = nodes[j]
                if (distanceMeters(a.point, b.point) <= thresholdMeters) {
                    pairs += a.id to b.id
                }
            }
        }
        return pairs
    }

    /**
     * Совпадающие endpoints без общего ребра между ними.
     * Coincident endpoints that do not share an edge.
     */
    fun findCoincidentDisconnectedEndpoints(
        graph: PathGraph,
        thresholdMeters: Double = 2.0,
    ): List<Pair<String, String>> {
        return findCoincidentNodePairs(graph, thresholdMeters).filter { (a, b) ->
            !nodesShareEdge(graph, a, b)
        }
    }

    /**
     * Сливает degree≤1 пары совпадающих узлов в один (оставляет keepId).
     * Merges degree≤1 coincident node pairs into one (keeps keepId).
     */
    fun mergeCoincidentDegree1Endpoints(
        graph: PathGraph,
        thresholdMeters: Double = 2.0,
    ): Pair<PathGraph, Int> {
        var working = graph
        var merges = 0
        var guard = 0
        while (guard < graph.nodes.size) {
            guard++
            val pair = findCoincidentDisconnectedEndpoints(working, thresholdMeters)
                .firstOrNull { (a, b) ->
                    nodeDegree(working, a) <= 1 && nodeDegree(working, b) <= 1
                } ?: break
            val (keepId, dropId) = pair
            working = mergeNodes(working, keepId = keepId, dropId = dropId)
            merges++
        }
        return working to merges
    }

    /**
     * Статистика сохранённого графа и runtime-сети.
     * Stats for the stored graph and runtime network.
     */
    fun analyze(
        storedGraph: PathGraph,
        runtimeNetwork: RailNetwork,
        coincidentThresholdMeters: Double = 2.0,
    ): NetworkTopologyStats {
        val lengths = runtimeNetwork.edges.map { it.lengthMeters }.sorted()
        val coincident = findCoincidentDisconnectedEndpoints(storedGraph, coincidentThresholdMeters)
        return NetworkTopologyStats(
            storedEdges = storedGraph.edges.size,
            storedNodes = storedGraph.nodes.size,
            runtimeEdges = runtimeNetwork.edges.size,
            components = countComponents(runtimeNetwork),
            coincidentDisconnectedEndpoints = coincident.size,
            edgeLengthMin = lengths.firstOrNull() ?: 0.0,
            edgeLengthP50 = percentile(lengths, 0.50),
            edgeLengthP95 = percentile(lengths, 0.95),
            edgeLengthMax = lengths.lastOrNull() ?: 0.0,
            graphHash = graphHash(storedGraph),
        )
    }

    /**
     * Короткий hash геометрии/топологии графа.
     * Short hash of graph geometry/topology.
     */
    fun graphHash(graph: PathGraph): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val edges = graph.edges.values.sortedBy { it.id }
        for (edge in edges) {
            digest.update(edge.id.toByteArray())
            digest.update(edge.startNodeId.toByteArray())
            digest.update(edge.endNodeId.toByteArray())
            for (point in edge.geometry) {
                digest.update("%.6f,%.6f;".format(point.lat, point.lon).toByteArray())
            }
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    private fun mergeNodes(graph: PathGraph, keepId: String, dropId: String): PathGraph {
        if (keepId == dropId) return graph
        val keep = graph.nodes[keepId] ?: return graph
        if (!graph.nodes.containsKey(dropId)) return graph

        val remappedEdges = graph.edges.mapValues { (_, edge) ->
            edge.copy(
                startNodeId = if (edge.startNodeId == dropId) keepId else edge.startNodeId,
                endNodeId = if (edge.endNodeId == dropId) keepId else edge.endNodeId,
                geometry = remapGeometryEndpoints(edge, dropId, keep.point),
            )
        }.filterValues { it.startNodeId != it.endNodeId || it.geometry.size >= 3 }

        val nodes = (graph.nodes - dropId).toMutableMap()
        // Удаляем осиротевшие узлы.
        val used = remappedEdges.values.flatMap { listOf(it.startNodeId, it.endNodeId) }.toSet()
        val cleanedNodes = nodes.filterKeys { it in used || it == keepId }
        return PathGraph(nodes = cleanedNodes, edges = remappedEdges)
    }

    private fun remapGeometryEndpoints(edge: PathEdge, dropId: String, keepPoint: GeoPoint): List<GeoPoint> {
        if (edge.geometry.isEmpty()) return edge.geometry
        val geo = edge.geometry.toMutableList()
        if (edge.startNodeId == dropId) geo[0] = keepPoint
        if (edge.endNodeId == dropId) geo[geo.lastIndex] = keepPoint
        return geo
    }

    private fun nodesShareEdge(graph: PathGraph, a: String, b: String): Boolean {
        return graph.edges.values.any { edge ->
            (edge.startNodeId == a && edge.endNodeId == b) ||
                (edge.startNodeId == b && edge.endNodeId == a)
        }
    }

    private fun nodeDegree(graph: PathGraph, nodeId: String): Int {
        return graph.edges.values.count { it.startNodeId == nodeId || it.endNodeId == nodeId }
    }

    private fun countComponents(network: RailNetwork): Int {
        if (network.edges.isEmpty()) return 0
        val visited = BooleanArray(network.edges.size)
        var components = 0
        for (start in network.edges.indices) {
            if (visited[start]) continue
            components++
            val queue = ArrayDeque<Int>()
            queue.add(start)
            visited[start] = true
            while (queue.isNotEmpty()) {
                val edgeIndex = queue.removeFirst()
                val edge = network.edges[edgeIndex]
                for (nodeId in listOf(edge.startNodeId, edge.endNodeId)) {
                    for (next in network.nodeToEdges[nodeId].orEmpty()) {
                        if (!visited[next]) {
                            visited[next] = true
                            queue.add(next)
                        }
                    }
                }
            }
        }
        return components
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double = GeoDistance.meters(a, b)
}

/**
 * Сводка топологии сети маршрута.
 * Route network topology summary.
 */
data class NetworkTopologyStats(
    val storedEdges: Int,
    val storedNodes: Int,
    val runtimeEdges: Int,
    val components: Int,
    val coincidentDisconnectedEndpoints: Int,
    val edgeLengthMin: Double,
    val edgeLengthP50: Double,
    val edgeLengthP95: Double,
    val edgeLengthMax: Double,
    val graphHash: String,
) {
    fun toLogLine(): String {
        return "storedEdges=$storedEdges, runtimeEdges=$runtimeEdges, nodes=$storedNodes, " +
            "components=$components, coincidentDisconnected=$coincidentDisconnectedEndpoints, " +
            "lenMin=${"%.1f".format(edgeLengthMin)}, lenP50=${"%.1f".format(edgeLengthP50)}, " +
            "lenP95=${"%.1f".format(edgeLengthP95)}, lenMax=${"%.1f".format(edgeLengthMax)}, " +
            "graphHash=$graphHash"
    }
}
