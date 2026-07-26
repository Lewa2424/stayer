package com.example.stayer.pathnet.domain

import com.example.stayer.pathnet.diagnostics.PathNetLogger
import com.example.stayer.pathnet.model.GeoPoint

/**
 * Инкрементальный map-matching: держит бегуна на нарисованной сети маршрута и считает
 * дистанцию как продвижение вперёд по сети (в т.ч. через несколько коротких кусков за тик).
 * Incremental map-matching: keeps the runner on the drawn route network and counts distance
 * as forward progress along the network (including across several short edges per tick).
 *
 * Правила:
 *  - Привязка к ближайшему ребру в пределах [startLockThresholdMeters].
 *  - Выбор сегмента с гистерезисом [sameEdgeBiasMeters], но у конца куска bias отключается,
 *    чтобы не «залипать» и не терять метры.
 *  - Дистанция = длина пути вперёд по сети до новой проекции (multi-hop), не только 1 сосед.
 *  - Уход дальше [maxDeviationMetersToUnlock] от сети отвязывает сессию.
 *
 * Rules:
 *  - Locks onto the nearest edge within [startLockThresholdMeters].
 *  - Edge choice uses [sameEdgeBiasMeters] hysteresis, but bias is disabled near the edge end
 *    so the matcher does not stick and steal meters.
 *  - Distance is the forward network path length to the new projection (multi-hop), not one hop.
 *  - Drifting further than [maxDeviationMetersToUnlock] unlocks the session.
 */
class RailMatcher(
    private val startLockThresholdMeters: Double = 100.0,
    private val maxDeviationMetersToUnlock: Double = 60.0,
    private val sameEdgeBiasMeters: Double = 6.0,
    private val maxPlausibleSpeedMps: Double = 10.0,
    private val endApproachMeters: Double = 2.0,
    private val maxTransitionHops: Int = 8,
) {
    @Volatile
    private var network: RailNetwork = RailNetwork(emptyList())

    private var currentEdgeIndex: Int? = null
    private var sOnEdgeMeters: Double = 0.0
    private var travelingTowardEnd: Boolean = true
    private var directionKnown: Boolean = false

    val isLocked: Boolean
        get() = currentEdgeIndex != null

    /**
     * Обновляет доступную сеть маршрута (после загрузки из хранилища).
     * Updates the available route network (after loading from storage).
     */
    fun updateNetwork(network: RailNetwork) {
        this.network = network
    }

    /**
     * Полностью отвязывает сессию от сети.
     * Fully unlocks the session from the network.
     */
    fun reset() {
        if (currentEdgeIndex != null) {
            PathNetLogger.info("RailMatcher: unlocked (reset)")
        }
        currentEdgeIndex = null
        sOnEdgeMeters = 0.0
        travelingTowardEnd = true
        directionKnown = false
    }

    /**
     * Пытается привязаться к ближайшему ребру сети в пределах порога.
     * Attempts to lock onto the nearest network edge within the threshold.
     *
     * @return true, если сессия привязана (уже была или привязалась сейчас).
     */
    fun tryLock(point: GeoPoint): Boolean {
        if (isLocked) return true
        if (network.isEmpty) return false

        val best = network.edges.indices
            .mapNotNull { index -> RailEdgeProjector.project(point, network.edges[index])?.let { index to it } }
            .filter { (_, projection) -> projection.distanceMeters <= startLockThresholdMeters }
            .minByOrNull { (_, projection) -> projection.distanceMeters }
            ?: return false

        val (index, projection) = best
        currentEdgeIndex = index
        sOnEdgeMeters = projection.sMeters
        directionKnown = false
        travelingTowardEnd = true
        PathNetLogger.info(
            "RailMatcher: locked to edge #$index (${network.edges[index].edgeId}) " +
                "at s=${fmt(projection.sMeters)}m, dist=${fmt(projection.distanceMeters)}m",
        )
        return true
    }

    /**
     * Продвигает бегуна по сети по новой GPS-точке.
     * Advances the runner along the network using a new GPS point.
     *
     * @return null, если сессия не привязана или точка ушла от сети слишком далеко.
     */
    fun advance(point: GeoPoint, dtSec: Double): RailAdvanceResult? {
        val ci = currentEdgeIndex ?: return null
        val net = network
        val curEdge = net.edges.getOrNull(ci) ?: run { reset(); return null }
        val curProj = RailEdgeProjector.project(point, curEdge) ?: run { reset(); return null }

        val maxPathMeters = if (dtSec > 0.0) {
            maxPlausibleSpeedMps * dtSec
        } else {
            maxPlausibleSpeedMps
        }.coerceAtLeast(0.0)

        val committed = chooseCommittedEdge(
            net = net,
            currentIndex = ci,
            curEdge = curEdge,
            curProj = curProj,
            point = point,
            maxPathMeters = maxPathMeters,
        )

        if (committed.projection.distanceMeters > maxDeviationMetersToUnlock) {
            PathNetLogger.warn(
                "RailMatcher: unlocked, too far from network (${fmt(committed.projection.distanceMeters)}m)",
            )
            reset()
            return null
        }

        val delta = committed.deltaMeters.coerceIn(0.0, maxPathMeters)

        currentEdgeIndex = committed.edgeIndex
        sOnEdgeMeters = committed.projection.sMeters
        travelingTowardEnd = committed.travelingTowardEnd
        directionKnown = committed.directionKnown

        return RailAdvanceResult(
            point = committed.projection.point,
            deltaMeters = delta,
            distanceToRailMeters = committed.projection.distanceMeters,
        )
    }

    /**
     * Продвигает позицию вдоль текущего ребра без GPS (dead reckoning по шагам).
     * Advances position along the current edge without GPS (step-based dead reckoning).
     *
     * Переходы на соседние рёбра без GPS не выполняются.
     * Does not transition to adjacent edges without GPS.
     */
    fun advanceByArcLength(arcMeters: Double, dtSec: Double = 1.0): RailAdvanceResult? {
        val ci = currentEdgeIndex ?: return null
        val edge = network.edges.getOrNull(ci) ?: run { reset(); return null }
        val length = edge.lengthMeters
        if (length <= 0.0) return null

        if (!directionKnown) {
            travelingTowardEnd = true
            directionKnown = true
        }

        var delta = arcMeters.coerceAtLeast(0.0)
        val maxDelta = if (dtSec > 0.0) maxPlausibleSpeedMps * dtSec else delta
        delta = delta.coerceIn(0.0, maxDelta.coerceAtLeast(0.0))
        if (delta <= 0.0) return null

        val forwardOld = if (travelingTowardEnd) sOnEdgeMeters else (length - sOnEdgeMeters)
        var forwardNew = forwardOld + delta

        if (edge.isClosed) {
            if (forwardNew > length) {
                forwardNew %= length
            }
        } else {
            forwardNew = forwardNew.coerceAtMost(length)
        }

        val actualDelta = when {
            edge.isClosed && forwardNew < forwardOld && forwardOld + delta > length -> {
                (length - forwardOld) + forwardNew
            }
            else -> (forwardNew - forwardOld).coerceAtLeast(0.0)
        }

        sOnEdgeMeters = if (travelingTowardEnd) forwardNew else (length - forwardNew)
        val point = RailEdgeProjector.pointAtArcLength(edge, sOnEdgeMeters) ?: return null

        return RailAdvanceResult(
            point = point,
            deltaMeters = actualDelta,
            distanceToRailMeters = 0.0,
        )
    }

    /**
     * Выбирает сегмент и считает продвижение вперёд по пути в сети.
     * Chooses an edge and computes forward progress along the network path.
     */
    private fun chooseCommittedEdge(
        net: RailNetwork,
        currentIndex: Int,
        curEdge: RailEdge,
        curProj: RailEdgeProjection,
        point: GeoPoint,
        maxPathMeters: Double,
    ): CommittedEdge {
        val remain = remainingForward(curEdge, sOnEdgeMeters)
        val nearEnd = remain <= endApproachMeters
        // У конца куска bias выключается — иначе трамвай залипает и ворует метры.
        // Near the edge end bias is off — otherwise the matcher sticks and steals meters.
        val bias = if (nearEnd) 0.0 else sameEdgeBiasMeters

        val candidates = candidateIndices(net, currentIndex, maxPathMeters)
            .mapNotNull { index ->
                val projection = RailEdgeProjector.project(point, net.edges[index]) ?: return@mapNotNull null
                val score = projection.distanceMeters - if (index == currentIndex) bias else 0.0
                Candidate(index, projection, score)
            }

        val best = candidates.minByOrNull { it.score }
            ?: return commitFromArrival(
                edgeIndex = currentIndex,
                projection = curProj,
                arrival = RailForwardPath.Arrival(
                    deltaMeters = 0.0,
                    travelingTowardEnd = travelingTowardEnd,
                    directionKnown = directionKnown,
                ),
            )

        val fromPose = RailForwardPath.Pose(
            edgeIndex = currentIndex,
            sMeters = sOnEdgeMeters,
            travelingTowardEnd = travelingTowardEnd,
            directionKnown = directionKnown,
        )

        fun arrivalFor(candidate: Candidate): RailForwardPath.Arrival? {
            return RailForwardPath.measure(
                network = net,
                from = fromPose,
                toEdgeIndex = candidate.edgeIndex,
                toSMeters = candidate.projection.sMeters,
                maxPathMeters = maxPathMeters,
                maxHops = maxTransitionHops,
            )
        }

        var chosen = best
        var arrival = arrivalFor(best)

        // Если залипли на конце текущего куска с нулевым delta — принудительно смотрим соседей вперёд.
        // If stuck at the end of the current edge with zero delta — force forward neighbors.
        val stuckAtEnd = chosen.edgeIndex == currentIndex &&
            nearEnd &&
            (arrival == null || arrival.deltaMeters <= 1e-6)
        if (stuckAtEnd) {
            val forced = candidates
                .filter { it.edgeIndex != currentIndex }
                .mapNotNull { candidate ->
                    val path = arrivalFor(candidate) ?: return@mapNotNull null
                    candidate to path
                }
                .minByOrNull { (candidate, path) ->
                    candidate.projection.distanceMeters + path.deltaMeters * 0.01
                }
            if (forced != null) {
                chosen = forced.first
                arrival = forced.second
            }
        }

        val resolved = arrival
        if (resolved == null) {
            // Нет валидного пути вперёд — остаёмся, метров 0 (не прыгаем назад по сети).
            // No valid forward path — stay put with zero meters (do not jump backward).
            return commitFromArrival(
                edgeIndex = currentIndex,
                projection = curProj,
                arrival = RailForwardPath.Arrival(
                    deltaMeters = 0.0,
                    travelingTowardEnd = travelingTowardEnd,
                    directionKnown = directionKnown,
                ),
            )
        }

        return commitFromArrival(chosen.edgeIndex, chosen.projection, resolved)
    }

    private fun commitFromArrival(
        edgeIndex: Int,
        projection: RailEdgeProjection,
        arrival: RailForwardPath.Arrival,
    ): CommittedEdge {
        return CommittedEdge(
            edgeIndex = edgeIndex,
            projection = projection,
            deltaMeters = arrival.deltaMeters,
            travelingTowardEnd = arrival.travelingTowardEnd,
            directionKnown = arrival.directionKnown,
        )
    }

    private fun remainingForward(edge: RailEdge, sMeters: Double): Double {
        if (!directionKnown) {
            return minOf(sMeters, (edge.lengthMeters - sMeters).coerceAtLeast(0.0))
        }
        return if (travelingTowardEnd) {
            (edge.lengthMeters - sMeters).coerceAtLeast(0.0)
        } else {
            sMeters.coerceAtLeast(0.0)
        }
    }

    /**
     * Кандидаты: текущее ребро + рёбра в радиусе нескольких хопов / метров пути.
     * Candidates: current edge + edges within a few hops / path meters.
     */
    private fun candidateIndices(
        net: RailNetwork,
        currentIndex: Int,
        maxPathMeters: Double,
    ): List<Int> {
        val set = LinkedHashSet<Int>()
        set.add(currentIndex)
        val edges = net.edges
        val curEdge = edges.getOrNull(currentIndex) ?: return set.toList()

        data class NodeState(val nodeId: String, val cost: Double, val hops: Int)
        val queue = ArrayDeque<NodeState>()
        val best = mutableMapOf<String, Double>()

        fun seed(nodeId: String, cost: Double) {
            if (cost > maxPathMeters) return
            val prev = best[nodeId]
            if (prev != null && cost >= prev) return
            best[nodeId] = cost
            queue.add(NodeState(nodeId, cost, 0))
        }

        // Стартуем с обоих узлов текущего ребра (направление ещё может уточниться).
        // Start from both nodes of the current edge (direction may still be refined).
        seed(curEdge.startNodeId, sOnEdgeMeters.coerceAtLeast(0.0))
        seed(curEdge.endNodeId, (curEdge.lengthMeters - sOnEdgeMeters).coerceAtLeast(0.0))

        while (queue.isNotEmpty()) {
            val state = queue.removeFirst()
            if (state.hops >= maxTransitionHops) continue
            for (edgeIndex in net.nodeToEdges[state.nodeId].orEmpty()) {
                set.add(edgeIndex)
                val edge = edges[edgeIndex]
                val enterAtStart = edge.startNodeId == state.nodeId
                val exitNode = when {
                    edge.isClosed -> state.nodeId
                    enterAtStart -> edge.endNodeId
                    else -> edge.startNodeId
                }
                if (edge.isClosed) continue
                val nextCost = state.cost + edge.lengthMeters
                if (nextCost > maxPathMeters) continue
                val prev = best[exitNode]
                if (prev != null && nextCost >= prev - 1e-6) continue
                best[exitNode] = nextCost
                queue.add(NodeState(exitNode, nextCost, state.hops + 1))
            }
        }
        return set.toList()
    }

    private fun fmt(value: Double): String = String.format("%.1f", value)

    private data class Candidate(
        val edgeIndex: Int,
        val projection: RailEdgeProjection,
        val score: Double,
    )

    private data class CommittedEdge(
        val edgeIndex: Int,
        val projection: RailEdgeProjection,
        val deltaMeters: Double,
        val travelingTowardEnd: Boolean,
        val directionKnown: Boolean,
    )
}

/**
 * Результат продвижения по сети маршрута.
 * Result of advancing along the route network.
 */
data class RailAdvanceResult(
    val point: GeoPoint,
    val deltaMeters: Double,
    val distanceToRailMeters: Double,
)
