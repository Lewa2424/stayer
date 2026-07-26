package com.example.stayer.pathnet.domain

import android.location.Location
import com.example.stayer.pathnet.model.GeoPoint
import com.example.stayer.pathnet.model.PathGraph

/**
 * Строит runtime-сеть маршрута из сохраненного графа тропинок пользователя.
 * Builds the runtime route network from the user's stored path graph.
 *
 * Уплотняет геометрию, считает длину и схлопывает цепочки без развилок (degree-2),
 * чтобы короткий «трамвайный» стык не дробил одометр. Развилки сохраняются.
 * Densifies geometry, computes length, and collapses degree-2 chains so short tram
 * joints do not fragment the odometer. Forks are preserved.
 */
object RailNetworkBuilder {
    private const val DEFAULT_SAMPLE_STEP_METERS = 2.0

    /**
     * Строит сеть из графа.
     * Builds the network from a graph.
     */
    fun build(graph: PathGraph, sampleStepMeters: Double = DEFAULT_SAMPLE_STEP_METERS): RailNetwork {
        val denseEdges = graph.edges.values.mapNotNull { edge ->
            val densePoints = PathRuntimeSampler.sampleEdge(edge, sampleStepMeters)
            if (densePoints.size < 2) return@mapNotNull null
            RailEdge(
                edgeId = edge.id,
                startNodeId = edge.startNodeId,
                endNodeId = edge.endNodeId,
                points = densePoints,
                cumulativeMeters = buildCumulativeMeters(densePoints),
            )
        }
        return RailNetwork(collapseDegree2Chains(denseEdges))
    }

    /**
     * Склеивает последовательности рёбер без развилок в более длинные сегменты.
     * Merges degree-2 edge sequences into longer segments.
     */
    internal fun collapseDegree2Chains(input: List<RailEdge>): List<RailEdge> {
        if (input.size <= 1) return input
        var edges = input
        var guard = 0
        while (guard < input.size) {
            guard++
            val adjacency = buildEndpointAdjacency(edges)
            val mergeNode = adjacency.entries.firstOrNull { (_, incident) ->
                incident.size == 2 && incident[0].edgeIndex != incident[1].edgeIndex
            }?.key ?: break

            val a = adjacency.getValue(mergeNode)[0]
            val b = adjacency.getValue(mergeNode)[1]
            val merged = mergeEdgesAtNode(edges[a.edgeIndex], edges[b.edgeIndex], mergeNode) ?: break

            edges = edges.filterIndexed { index, _ -> index != a.edgeIndex && index != b.edgeIndex } + merged
        }
        return edges
    }

    private data class EndpointRef(val edgeIndex: Int, val atStart: Boolean)

    private fun buildEndpointAdjacency(edges: List<RailEdge>): Map<String, List<EndpointRef>> {
        val map = mutableMapOf<String, MutableList<EndpointRef>>()
        edges.forEachIndexed { index, edge ->
            if (edge.isClosed) return@forEachIndexed
            map.getOrPut(edge.startNodeId) { mutableListOf() }.add(EndpointRef(index, atStart = true))
            map.getOrPut(edge.endNodeId) { mutableListOf() }.add(EndpointRef(index, atStart = false))
        }
        return map
    }

    private fun mergeEdgesAtNode(left: RailEdge, right: RailEdge, nodeId: String): RailEdge? {
        val leftExitsAtEnd = left.endNodeId == nodeId
        val leftExitsAtStart = left.startNodeId == nodeId
        val rightEntersAtStart = right.startNodeId == nodeId
        val rightEntersAtEnd = right.endNodeId == nodeId
        if (left.isClosed || right.isClosed) return null

        val leftOriented: OrientedEdge = when {
            leftExitsAtEnd -> OrientedEdge(left.points, left.cumulativeMeters, left.startNodeId, left.endNodeId)
            leftExitsAtStart -> OrientedEdge(
                points = left.points.asReversed(),
                cumulativeMeters = reverseCumulative(left.cumulativeMeters),
                startNodeId = left.endNodeId,
                endNodeId = left.startNodeId,
            )
            else -> return null
        }

        val rightOriented: OrientedEdge = when {
            rightEntersAtStart -> OrientedEdge(right.points, right.cumulativeMeters, right.startNodeId, right.endNodeId)
            rightEntersAtEnd -> OrientedEdge(
                points = right.points.asReversed(),
                cumulativeMeters = reverseCumulative(right.cumulativeMeters),
                startNodeId = right.endNodeId,
                endNodeId = right.startNodeId,
            )
            else -> return null
        }

        if (leftOriented.endNodeId != nodeId || rightOriented.startNodeId != nodeId) return null

        val combinedPoints = leftOriented.points + rightOriented.points.drop(1)
        if (combinedPoints.size < 2) return null
        val leftLen = leftOriented.cumulativeMeters.last()
        val combinedCum = leftOriented.cumulativeMeters +
            rightOriented.cumulativeMeters.drop(1).map { it + leftLen }
        return RailEdge(
            edgeId = "${left.edgeId}+${right.edgeId}",
            startNodeId = leftOriented.startNodeId,
            endNodeId = rightOriented.endNodeId,
            points = combinedPoints,
            cumulativeMeters = combinedCum,
        )
    }

    private data class OrientedEdge(
        val points: List<GeoPoint>,
        val cumulativeMeters: List<Double>,
        val startNodeId: String,
        val endNodeId: String,
    )

    private fun reverseCumulative(cumulative: List<Double>): List<Double> {
        val length = cumulative.lastOrNull() ?: 0.0
        return cumulative.asReversed().map { length - it }
    }

    private fun buildCumulativeMeters(points: List<GeoPoint>): List<Double> {
        val cumulative = mutableListOf(0.0)
        val result = FloatArray(1)
        for (index in 0 until points.lastIndex) {
            Location.distanceBetween(
                points[index].lat, points[index].lon,
                points[index + 1].lat, points[index + 1].lon,
                result,
            )
            cumulative += cumulative.last() + result[0].toDouble()
        }
        return cumulative
    }
}
