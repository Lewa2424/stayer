package com.example.stayer.pathnet.data

import com.example.stayer.pathnet.domain.RailNetwork
import com.example.stayer.pathnet.domain.RailNetworkBuilder

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
    suspend fun loadNetwork(): RailNetwork {
        val graph = repository.loadGraph()
        return RailNetworkBuilder.build(graph)
    }
}
