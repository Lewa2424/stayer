package com.example.stayer.pathnet.domain

/**
 * Runtime-сеть маршрута: набор ребер и связность по узлам.
 * Runtime route network: a set of edges with node adjacency.
 *
 * Поддерживает развилки: один узел может соединять несколько сегментов.
 * Supports forks: a single node can connect several edges.
 */
class RailNetwork(
    val edges: List<RailEdge>,
) {
    val isEmpty: Boolean
        get() = edges.isEmpty()

    /** Индекс: узел -> индексы примыкающих ребер. / Index: node -> adjacent edge indices. */
    val nodeToEdges: Map<String, List<Int>> = buildAdjacency(edges)

    private fun buildAdjacency(edges: List<RailEdge>): Map<String, List<Int>> {
        val index = mutableMapOf<String, MutableList<Int>>()
        edges.forEachIndexed { edgeIndex, edge ->
            index.getOrPut(edge.startNodeId) { mutableListOf() } += edgeIndex
            if (edge.endNodeId != edge.startNodeId) {
                index.getOrPut(edge.endNodeId) { mutableListOf() } += edgeIndex
            } else {
                // Кольцевой сегмент примыкает к своему узлу дважды.
                index.getOrPut(edge.endNodeId) { mutableListOf() } += edgeIndex
            }
        }
        return index
    }
}
