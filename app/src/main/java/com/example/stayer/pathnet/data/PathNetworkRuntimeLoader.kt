package com.example.stayer.pathnet.data

import com.example.stayer.pathnet.domain.NetworkTopologyStats
import com.example.stayer.pathnet.domain.PathGraphTopology
import com.example.stayer.pathnet.domain.RailNetwork
import com.example.stayer.pathnet.domain.RailNetworkBuilder
import com.example.stayer.pathnet.model.PathGraph

/**
 * Результат загрузки runtime-сети вместе со статистикой топологии.
 * Runtime network load result with topology stats.
 */
data class LoadedRailNetwork(
    val graph: PathGraph,
    val network: RailNetwork,
    val stats: NetworkTopologyStats,
)

/**
 * Загружает runtime-сеть маршрута из сохраненной пользовательской сети.
 * Loads the runtime route network from the persisted user network.
 */
class PathNetworkRuntimeLoader(
    private val repository: PathNetworkRepository,
) {
    /**
     * Читает граф и строит сеть для привязки GPS во время тренировки.
     * Reads the graph and builds the network for GPS locking during a workout.
     */
    suspend fun loadNetwork(): RailNetwork = loadNetworkWithStats().network

    /**
     * Читает граф, строит сеть и считает топологическую сводку.
     * Reads the graph, builds the network, and computes topology summary.
     */
    suspend fun loadNetworkWithStats(): LoadedRailNetwork {
        val graph = repository.loadGraph()
        val network = RailNetworkBuilder.build(graph)
        val stats = PathGraphTopology.analyze(graph, network)
        return LoadedRailNetwork(graph = graph, network = network, stats = stats)
    }
}
