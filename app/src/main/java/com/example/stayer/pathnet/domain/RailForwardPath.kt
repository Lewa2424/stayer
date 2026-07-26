package com.example.stayer.pathnet.domain

/**
 * Считает продвижение вперёд по сети от текущей позиции до целевого ребра.
 * Computes forward progress through the network from the current pose to a target edge.
 *
 * Нужен, чтобы на коротких кусках не терять метры: credit = остаток текущего +
 * полные промежуточные рёбра + вход в целевое, а не только «один сосед».
 * Needed so short edges do not steal meters: credit = remainder on current +
 * full intermediate edges + entry into target, not just "one neighbor".
 */
object RailForwardPath {
    data class Pose(
        val edgeIndex: Int,
        val sMeters: Double,
        val travelingTowardEnd: Boolean,
        val directionKnown: Boolean,
    )

    data class Arrival(
        val deltaMeters: Double,
        val travelingTowardEnd: Boolean,
        val directionKnown: Boolean,
    )

    /**
     * Возвращает длину пути вперёд до проекции на целевом ребре, либо null если пути нет.
     * Returns forward path length to the projection on the target edge, or null if unreachable.
     */
    fun measure(
        network: RailNetwork,
        from: Pose,
        toEdgeIndex: Int,
        toSMeters: Double,
        maxPathMeters: Double,
        maxHops: Int = 12,
    ): Arrival? {
        val edges = network.edges
        if (edges.isEmpty()) return null
        val fromEdge = edges.getOrNull(from.edgeIndex) ?: return null
        if (toEdgeIndex !in edges.indices) return null
        if (maxPathMeters < 0.0) return null

        if (from.edgeIndex == toEdgeIndex) {
            return sameEdgeArrival(fromEdge, from, toSMeters)
        }

        val towardEnd = if (from.directionKnown) {
            from.travelingTowardEnd
        } else {
            // Без известного направления берём кратчайший выход с текущего ребра.
            // Without a known direction, leave the current edge via the shorter exit.
            val toStart = from.sMeters
            val toEnd = fromEdge.lengthMeters - from.sMeters
            toEnd <= toStart
        }

        val remainOnFrom = if (towardEnd) {
            (fromEdge.lengthMeters - from.sMeters).coerceAtLeast(0.0)
        } else {
            from.sMeters.coerceAtLeast(0.0)
        }
        if (remainOnFrom > maxPathMeters) return null

        val startNode = if (towardEnd) fromEdge.endNodeId else fromEdge.startNodeId
        data class State(
            val nodeId: String,
            val costMeters: Double,
            val hops: Int,
            val viaEdgeIndex: Int,
        )

        val queue = ArrayDeque<State>()
        queue.add(State(startNode, remainOnFrom, 0, from.edgeIndex))
        val bestAtNode = mutableMapOf<String, Double>()
        bestAtNode[startNode] = remainOnFrom

        var bestArrival: Arrival? = null

        while (queue.isNotEmpty()) {
            val state = queue.removeFirst()
            if (state.costMeters > maxPathMeters) continue
            if (state.hops > maxHops) continue

            val incident = network.nodeToEdges[state.nodeId].orEmpty()
            for (edgeIndex in incident) {
                if (edgeIndex == state.viaEdgeIndex) continue
                val edge = edges[edgeIndex]
                val enterAtStart = edge.startNodeId == state.nodeId
                val enterAtEnd = edge.endNodeId == state.nodeId
                if (!enterAtStart && !enterAtEnd) continue

                val travelTowardEnd = enterAtStart
                if (edgeIndex == toEdgeIndex) {
                    val into = if (travelTowardEnd) {
                        toSMeters.coerceIn(0.0, edge.lengthMeters)
                    } else {
                        (edge.lengthMeters - toSMeters).coerceIn(0.0, edge.lengthMeters)
                    }
                    val total = state.costMeters + into
                    if (total <= maxPathMeters) {
                        val candidate = Arrival(
                            deltaMeters = total.coerceAtLeast(0.0),
                            travelingTowardEnd = travelTowardEnd,
                            directionKnown = true,
                        )
                        if (bestArrival == null || candidate.deltaMeters < bestArrival.deltaMeters) {
                            bestArrival = candidate
                        }
                    }
                    continue
                }

                // Полный проход промежуточного ребра к противоположному узлу.
                // Full traversal of an intermediate edge to the opposite node.
                if (edge.isClosed) continue // кольцо-одноребро обрабатывается same-edge веткой
                val exitNode = if (travelTowardEnd) edge.endNodeId else edge.startNodeId
                if (exitNode == state.nodeId) continue
                val nextCost = state.costMeters + edge.lengthMeters
                if (nextCost > maxPathMeters) continue
                if (state.hops + 1 > maxHops) continue
                val prevBest = bestAtNode[exitNode]
                if (prevBest != null && nextCost >= prevBest - 1e-6) continue
                bestAtNode[exitNode] = nextCost
                queue.add(
                    State(
                        nodeId = exitNode,
                        costMeters = nextCost,
                        hops = state.hops + 1,
                        viaEdgeIndex = edgeIndex,
                    ),
                )
            }
        }

        return bestArrival
    }

    private fun sameEdgeArrival(
        edge: RailEdge,
        from: Pose,
        toSMeters: Double,
    ): Arrival {
        if (!from.directionKnown) {
            val towardEnd = toSMeters >= from.sMeters
            val known = toSMeters != from.sMeters
            return Arrival(
                deltaMeters = kotlin.math.abs(toSMeters - from.sMeters),
                travelingTowardEnd = towardEnd,
                directionKnown = known,
            )
        }

        val length = edge.lengthMeters
        val forwardOld = if (from.travelingTowardEnd) from.sMeters else (length - from.sMeters)
        val forwardNew = if (from.travelingTowardEnd) toSMeters else (length - toSMeters)
        var delta = forwardNew - forwardOld
        if (edge.isClosed && delta < -length / 2.0) {
            delta += length
        }
        return Arrival(
            deltaMeters = delta.coerceAtLeast(0.0),
            travelingTowardEnd = from.travelingTowardEnd,
            directionKnown = true,
        )
    }
}
