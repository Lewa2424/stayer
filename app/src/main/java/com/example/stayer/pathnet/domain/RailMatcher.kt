package com.example.stayer.pathnet.domain

import com.example.stayer.pathnet.diagnostics.PathNetLogger
import com.example.stayer.pathnet.model.GeoPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Инкрементальный map-matching: держит бегуна на нарисованной сети маршрута и считает
 * дистанцию как подтверждённое липкое продвижение по сети.
 * Incremental map-matching: keeps a sticky accepted pose on the route network.
 *
 * Инвариант: изменение внутренней позиции вдоль маршрута = сумма начисленных метров.
 * Invariant: internal route pose change equals credited meters.
 */
class RailMatcher(
    private val startLockThresholdMeters: Double = 100.0,
    private val maxDeviationMetersToUnlock: Double = 60.0,
    private val sameEdgeBiasMeters: Double = 6.0,
    private val maxPlausibleSpeedMps: Double = 7.0,
    private val endApproachMeters: Double = 2.0,
    private val maxTransitionHops: Int = 8,
    private val searchRadiusMeters: Double = 40.0,
    private val maxUnresolvedTicksForDirectionReset: Int = 3,
    private val minDirectionLockMeters: Double = 3.0,
) {
    @Volatile
    private var network: RailNetwork = RailNetwork(emptyList())

    private var currentEdgeIndex: Int? = null
    private var sOnEdgeMeters: Double = 0.0
    private var travelingTowardEnd: Boolean = true
    private var directionKnown: Boolean = false
    private val motionEstimator = StickyMotionEstimator(maxPlausibleSpeedMps)
    private val pendingReverse = mutableListOf<RailObservation>()
    private var pendingPose: RailForwardPath.Pose? = null

    val isLocked: Boolean
        get() = currentEdgeIndex != null

    /** Долг отключён: pose никогда не догоняет старую проекцию. Debt is disabled. */
    val debtMeters: Double
        get() = 0.0

    val currentEdgeId: String?
        get() = currentEdgeIndex?.let { network.edges.getOrNull(it)?.edgeId }

    val currentSMeters: Double
        get() = sOnEdgeMeters

    val isDirectionKnown: Boolean
        get() = directionKnown

    val isTravelingTowardEnd: Boolean
        get() = travelingTowardEnd

    /**
     * Обновляет доступную сеть маршрута (после загрузки из хранилища).
     * Updates the available route network (after loading from storage).
     *
     * Если сессия уже привязана — сбрасывает lock, чтобы не держать устаревший edgeIndex.
     * If already locked, unlocks so a stale edgeIndex is not kept.
     */
    fun updateNetwork(network: RailNetwork) {
        val wasLocked = isLocked
        this.network = network
        if (wasLocked) {
            PathNetLogger.info("RailMatcher: network replaced while locked — unlocking")
            reset()
        }
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
        pendingReverse.clear()
        pendingPose = null
        motionEstimator.reset()
    }

    /**
     * Пытается привязаться к ближайшему ребру сети в пределах порога.
     * Attempts to lock onto the nearest network edge within the threshold.
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
        pendingReverse.clear()
        pendingPose = null
        motionEstimator.reset()
        PathNetLogger.info(
            "RailMatcher: locked to edge #$index (${network.edges[index].edgeId}) " +
                "at s=${fmt(projection.sMeters)}m, dist=${fmt(projection.distanceMeters)}m",
        )
        return true
    }

    /**
     * Обрабатывает наблюдение: проекция — кандидат, движение pose — единственный credit.
     * Processes an observation: projection is a candidate, accepted pose movement is credit.
     */
    fun process(observation: RailObservation): RailAdvanceResult? {
        val point = observation.point
        if (point == null) {
            val cadence = motionEstimator.estimate(observation)
            return if (cadence.confidence >= 2 && cadence.distanceMeters > 0.0) {
                advanceByArcLength(cadence.distanceMeters, observation.dtSec)?.copy(
                    status = RailAdvanceStatus.MOTION_SLIDE,
                    coveredDurationSec = observation.dtSec,
                    creditKind = RailCreditKind.CADENCE_SLIDE,
                )
            } else {
                holdResult(RailAdvanceStatus.HOLD, 0.0)
            }
        }
        val ci = currentEdgeIndex ?: return null
        val net = network
        val curEdge = net.edges.getOrNull(ci) ?: run { reset(); return null }
        val curProjection = RailEdgeProjector.project(point, curEdge) ?: return null
        val pose = pose()

        if (curProjection.distanceMeters > maxDeviationMetersToUnlock) {
            PathNetLogger.warn("RailMatcher: unlocked, too far from network (${fmt(curProjection.distanceMeters)}m)")
            reset()
            return result(curProjection.point, 0.0, curProjection.distanceMeters, RailAdvanceStatus.OFF_RAIL, 0.0)
        }
        val signedSameEdge = if (travelingTowardEnd) curProjection.sMeters - sOnEdgeMeters else sOnEdgeMeters - curProjection.sMeters
        if (pendingReverse.isNotEmpty() || signedSameEdge < -0.12) {
            if (pendingReverse.isEmpty()) pendingPose = pose
            pendingReverse += observation
            return evaluatePending()
        }

        return processForward(observation, point, pose, curEdge, curProjection)
    }

    /** Совместимый тонкий wrapper без независимых источников движения. */
    fun advance(point: GeoPoint, dtSec: Double): RailAdvanceResult? = process(
        RailObservation(
            point = point,
            dtSec = dtSec,
            rawDeltaMeters = null,
            accuracyMeters = 5.0,
            locationSpeedMps = null,
            cadenceDeltaMeters = null,
            quality = ObservationQuality.ACCEPTED_GPS,
        ),
    )

    /** Вычисляет локальный credit и двигает pose только на фактически принятые метры. */
    private fun processForward(
        observation: RailObservation,
        point: GeoPoint,
        pose: RailForwardPath.Pose,
        curEdge: RailEdge,
        curProjection: RailEdgeProjection,
        replay: Boolean = false,
    ): RailAdvanceResult {
        val committed = chooseCommittedEdge(
            net = network,
            pose = pose,
            curEdge = curEdge,
            curProj = curProjection,
            point = point,
            searchBudget = max(searchRadiusMeters, motionEstimator.hardCapMeters(observation.dtSec)),
            allowDirectionFlip = false,
        )
        val arrival = committed.arrival ?: return holdResult(committed.status, committed.projection.distanceMeters)
        val pathDelta = arrival.deltaMeters
        val motion = motionEstimator.estimate(observation)
        val hardCap = motionEstimator.hardCapMeters(observation.dtSec)
        if (motion.stationary) return holdResult(RailAdvanceStatus.HOLD, committed.projection.distanceMeters, pathDelta)
        val jumpThreshold = max(6.0, max(3.0 * max(motion.distanceMeters, 1.0), hardCap + min(observation.accuracyMeters * 0.25, 5.0)))
        if (pathDelta > jumpThreshold) {
            if (motion.confidence < 2) return holdResult(RailAdvanceStatus.HOLD, committed.projection.distanceMeters, pathDelta)
            return applyMotionSlide(observation, motion.distanceMeters, committed.projection.distanceMeters, pathDelta, replay)
        }
        val bootstrap = motion.confidence == 0 && observation.rawDeltaMeters == null &&
            observation.locationSpeedMps == null && observation.cadenceDeltaMeters == null &&
            pathDelta > 0.0 && pathDelta <= hardCap
        val budget = when {
            motion.confidence >= 1 -> min(hardCap, max(motion.distanceMeters * 1.45 + 0.25, 0.25))
            bootstrap -> hardCap
            else -> 0.0
        }
        val credit = min(pathDelta, budget)
        if (credit <= 0.0) return holdResult(RailAdvanceStatus.HOLD, committed.projection.distanceMeters, pathDelta)
        val moved = movePoseAlongPath(network, pose, committed.edgeIndex, committed.projection.sMeters,
            arrival.travelingTowardEnd, arrival.directionKnown, credit, pathDelta)
        setPose(moved)
        motionEstimator.updateEma(credit, observation, motion.confidence > 0 || bootstrap)
        return result(
            RailEdgeProjector.pointAtArcLength(network.edges[moved.edgeIndex], moved.sMeters) ?: committed.projection.point,
            credit, committed.projection.distanceMeters,
            if (replay) RailAdvanceStatus.REPLAY_FORWARD else committed.status,
            pathDelta,
            if (replay) RailCreditKind.REPLAY_FORWARD else RailCreditKind.PROJECTION_CONFIRMED,
            observation.dtSec,
        )
    }

    /** Подтверждает или отбрасывает буфер возможного разворота. */
    private fun evaluatePending(): RailAdvanceResult {
        val anchor = pendingPose ?: pose()
        val samples = pendingReverse.toList()
        val currentEdge = network.edges.getOrNull(anchor.edgeIndex) ?: return holdResult(RailAdvanceStatus.HOLD, 0.0)
        val backs = samples.mapNotNull { obs ->
            obs.point?.let { RailEdgeProjector.project(it, currentEdge) }?.let { projection ->
                if (anchor.travelingTowardEnd) anchor.sMeters - projection.sMeters else projection.sMeters - anchor.sMeters
            }
        }
        val supported = samples.count { estimate -> motionEstimator.estimate(estimate).let { it.confidence >= 2 && !it.stationary && it.distanceMeters > 0.12 } }
        val monotonic = backs.zipWithNext().count { (a, b) -> b - a > 0.12 } >= max(1, backs.size - 2)
        val reverse = backs.size >= 3 && backs.takeLast(3).all { it > 0.6 } &&
            (backs.lastOrNull() ?: 0.0) - (backs.firstOrNull() ?: 0.0) >= 2.0 && monotonic &&
            supported >= max(2, samples.size / 2)
        val forward = backs.takeLast(2).any { it < -0.8 }
        if (!reverse && !forward && samples.size < 16 && samples.sumOf { max(it.dtSec, 0.0) } < 12.0) {
            return holdResult(RailAdvanceStatus.REVERSE_PENDING, 0.0)
        }
        pendingReverse.clear()
        pendingPose = null
        if (!reverse && !forward) return holdResult(RailAdvanceStatus.HOLD, 0.0)
        if (reverse) {
            travelingTowardEnd = !anchor.travelingTowardEnd
            directionKnown = true
        }
        var last: RailAdvanceResult = holdResult(if (reverse) RailAdvanceStatus.REVERSE_CONFIRMED else RailAdvanceStatus.REPLAY_FORWARD, 0.0)
        samples.forEach { obs ->
            val point = obs.point ?: return@forEach
            val edge = network.edges.getOrNull(currentEdgeIndex ?: return@forEach) ?: return@forEach
            val projection = RailEdgeProjector.project(point, edge) ?: return@forEach
            last = processForward(obs, point, pose(), edge, projection, replay = true)
        }
        return last
    }

    /** Начисляет подтверждённое независимыми источниками движение без телепорта к кандидату. */
    private fun applyMotionSlide(
        observation: RailObservation,
        meters: Double,
        distanceToRail: Double,
        pathDelta: Double,
        replay: Boolean,
    ): RailAdvanceResult {
        val moved = moveForwardBy(
            meters.coerceAtMost(motionEstimator.hardCapMeters(observation.dtSec)),
            observation.dtSec,
        )
            ?: return holdResult(RailAdvanceStatus.HOLD, distanceToRail, pathDelta)
        motionEstimator.updateEma(meters, observation, true)
        return result(moved.point, moved.deltaMeters, distanceToRail,
            if (replay) RailAdvanceStatus.REPLAY_FORWARD else RailAdvanceStatus.MOTION_SLIDE,
            pathDelta, if (replay) RailCreditKind.REPLAY_FORWARD else RailCreditKind.MOTION_SLIDE, observation.dtSec)
    }

    /**
     * Продвигает позицию вдоль текущего ребра без GPS (dead reckoning по шагам).
     * Advances position along the current edge without GPS (step-based dead reckoning).
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
            status = RailAdvanceStatus.SAME_EDGE_FORWARD,
            debtMeters = 0.0,
            capApplied = false,
            pathDeltaBeforeCap = actualDelta,
            coveredDurationSec = if (actualDelta > 0.0) dtSec else 0.0,
            creditKind = RailCreditKind.CADENCE_SLIDE,
        )
    }

    /** Возвращает текущую принятую позу. */
    private fun pose() = RailForwardPath.Pose(
        edgeIndex = currentEdgeIndex ?: 0,
        sMeters = sOnEdgeMeters,
        travelingTowardEnd = travelingTowardEnd,
        directionKnown = directionKnown,
    )

    /** Устанавливает принятую позу после единственного источника credit. */
    private fun setPose(pose: RailForwardPath.Pose) {
        currentEdgeIndex = pose.edgeIndex
        sOnEdgeMeters = pose.sMeters
        travelingTowardEnd = pose.travelingTowardEnd
        directionKnown = pose.directionKnown
    }

    /** Двигает pose по текущему направлению и возвращает только реально пройденное. */
    private fun moveForwardBy(meters: Double, dtSec: Double): RailAdvanceResult? =
        advanceByArcLength(meters, dtSec)

    /** Формирует нулевой результат, не меняя принятую позу. */
    private fun holdResult(status: RailAdvanceStatus, distanceToRail: Double, pathDelta: Double = 0.0): RailAdvanceResult {
        val edge = currentEdgeIndex?.let { network.edges.getOrNull(it) }
        val point = edge?.let { RailEdgeProjector.pointAtArcLength(it, sOnEdgeMeters) }
            ?: GeoPoint(0.0, 0.0)
        return result(point, 0.0, distanceToRail, status, pathDelta)
    }

    /** Собирает результат с отключёнными legacy debt/cap полями. */
    private fun result(
        point: GeoPoint,
        delta: Double,
        distanceToRail: Double,
        status: RailAdvanceStatus,
        pathDelta: Double,
        creditKind: RailCreditKind = RailCreditKind.NONE,
        coveredDurationSec: Double = 0.0,
    ) = RailAdvanceResult(
        point = point,
        deltaMeters = delta,
        distanceToRailMeters = distanceToRail,
        status = status,
        debtMeters = 0.0,
        capApplied = false,
        pathDeltaBeforeCap = pathDelta,
        coveredDurationSec = if (delta > 0.0) coveredDurationSec else 0.0,
        creditKind = if (delta > 0.0) creditKind else RailCreditKind.HOLD,
    )

    private fun chooseCommittedEdge(
        net: RailNetwork,
        pose: RailForwardPath.Pose,
        curEdge: RailEdge,
        curProj: RailEdgeProjection,
        point: GeoPoint,
        searchBudget: Double,
        allowDirectionFlip: Boolean,
    ): CommittedEdge {
        val remain = remainingForward(curEdge, pose.sMeters, pose.travelingTowardEnd, pose.directionKnown)
        val nearEnd = remain <= endApproachMeters
        val bias = if (nearEnd) 0.0 else sameEdgeBiasMeters

        val candidates = candidateIndices(net, pose.edgeIndex, searchBudget)
            .mapNotNull { index ->
                val projection = RailEdgeProjector.project(point, net.edges[index]) ?: return@mapNotNull null
                val score = projection.distanceMeters - if (index == pose.edgeIndex) bias else 0.0
                Candidate(index, projection, score)
            }
            .sortedBy { it.score }

        if (candidates.isEmpty()) {
            return CommittedEdge(
                edgeIndex = pose.edgeIndex,
                projection = curProj,
                arrival = null,
                status = RailAdvanceStatus.NO_FEASIBLE_CANDIDATE,
            )
        }

        fun arrivalFor(candidate: Candidate, fromPose: RailForwardPath.Pose): RailForwardPath.Arrival? {
            return RailForwardPath.measure(
                network = net,
                from = fromPose,
                toEdgeIndex = candidate.edgeIndex,
                toSMeters = candidate.projection.sMeters,
                maxPathMeters = searchBudget,
                maxHops = maxTransitionHops,
                minDirectionLockMeters = minDirectionLockMeters,
            )
        }

        // Сначала среди ВСЕХ достижимых кандидатов — не только геометрически ближайший.
        // First among ALL reachable candidates — not only the geometrically nearest.
        val reachable = candidates.mapNotNull { candidate ->
            val arrival = arrivalFor(candidate, pose) ?: return@mapNotNull null
            Triple(candidate, arrival, candidate.projection.distanceMeters + arrival.deltaMeters * 0.01)
        }

        if (reachable.isNotEmpty()) {
            val best = reachable.minBy { it.third }
            return CommittedEdge(
                edgeIndex = best.first.edgeIndex,
                projection = best.first.projection,
                arrival = best.second,
                status = if (best.first.edgeIndex == pose.edgeIndex) {
                    if (best.second.deltaMeters <= 1e-9) {
                        RailAdvanceStatus.SAME_EDGE_BACKWARD_ZERO
                    } else {
                        RailAdvanceStatus.SAME_EDGE_FORWARD
                    }
                } else {
                    RailAdvanceStatus.EDGE_TRANSITION
                },
            )
        }

        // Никто недостижим при текущем направлении.
        // Nobody reachable with the current direction.
        if (allowDirectionFlip && pose.directionKnown) {
            val flipped = pose.copy(travelingTowardEnd = !pose.travelingTowardEnd)
            val flippedReachable = candidates.mapNotNull { candidate ->
                val arrival = arrivalFor(candidate, flipped) ?: return@mapNotNull null
                Triple(candidate, arrival, candidate.projection.distanceMeters + arrival.deltaMeters * 0.01)
            }
            if (flippedReachable.isNotEmpty()) {
                val best = flippedReachable.minBy { it.third }
                return CommittedEdge(
                    edgeIndex = best.first.edgeIndex,
                    projection = best.first.projection,
                    arrival = best.second,
                    status = RailAdvanceStatus.DIRECTION_RECOVERED,
                )
            }
        }

        val nearest = candidates.first()
        return CommittedEdge(
            edgeIndex = pose.edgeIndex,
            projection = curProj,
            arrival = null,
            status = if (nearest.edgeIndex != pose.edgeIndex) {
                RailAdvanceStatus.BEST_UNREACHABLE
            } else {
                RailAdvanceStatus.NO_FEASIBLE_CANDIDATE
            },
        )
    }

    /**
     * Двигает pose вперёд на [creditMeters] вдоль пути к целевой проекции.
     * Moves pose forward by [creditMeters] along the path toward the target projection.
     */
    private fun movePoseAlongPath(
        net: RailNetwork,
        from: RailForwardPath.Pose,
        targetEdgeIndex: Int,
        targetSMeters: Double,
        targetTravelingTowardEnd: Boolean,
        targetDirectionKnown: Boolean,
        creditMeters: Double,
        fullPathDelta: Double,
    ): RailForwardPath.Pose {
        if (creditMeters <= 1e-9) {
            return from
        }
        // Полный путь помещается в credit — можно сразу закоммитить целевую проекцию.
        // Full path fits in credit — commit the target projection directly.
        if (fullPathDelta <= creditMeters + 1e-6) {
            return RailForwardPath.Pose(
                edgeIndex = targetEdgeIndex,
                sMeters = targetSMeters,
                travelingTowardEnd = targetTravelingTowardEnd,
                directionKnown = targetDirectionKnown || fullPathDelta > minDirectionLockMeters,
            )
        }

        // Частичный credit: идём вдоль текущего ребра, затем через соседей.
        // Partial credit: walk along current edge, then through neighbors.
        var edgeIndex = from.edgeIndex
        var s = from.sMeters
        var towardEnd = if (from.directionKnown) from.travelingTowardEnd else targetTravelingTowardEnd
        var known = from.directionKnown || targetDirectionKnown
        var remaining = creditMeters
        var hops = 0

        while (remaining > 1e-9 && hops <= maxTransitionHops) {
            hops++
            val edge = net.edges.getOrNull(edgeIndex) ?: break
            val length = edge.lengthMeters
            if (length <= 1e-9) break

            if (edgeIndex == targetEdgeIndex) {
                val forwardOld = if (towardEnd) s else (length - s)
                val forwardTarget = if (towardEnd) targetSMeters else (length - targetSMeters)
                val need = (forwardTarget - forwardOld).coerceAtLeast(0.0)
                val step = min(need, remaining)
                val forwardNew = forwardOld + step
                s = if (towardEnd) forwardNew else (length - forwardNew)
                remaining -= step
                known = true
                break
            }

            val remainOnEdge = if (towardEnd) (length - s).coerceAtLeast(0.0) else s.coerceAtLeast(0.0)
            if (remainOnEdge <= 1e-9) {
                // Переход на следующий узел к цели.
                val exitNode = if (towardEnd) edge.endNodeId else edge.startNodeId
                val next = pickNextEdgeTowardTarget(net, edgeIndex, exitNode, targetEdgeIndex) ?: break
                edgeIndex = next.first
                towardEnd = next.second
                s = if (towardEnd) 0.0 else net.edges[edgeIndex].lengthMeters
                known = true
                continue
            }

            val step = min(remainOnEdge, remaining)
            val forwardOld = if (towardEnd) s else (length - s)
            val forwardNew = forwardOld + step
            s = if (towardEnd) forwardNew else (length - forwardNew)
            remaining -= step
            known = true

            if (remaining > 1e-9 && abs(remainOnEdge - step) <= 1e-9) {
                val exitNode = if (towardEnd) edge.endNodeId else edge.startNodeId
                val next = pickNextEdgeTowardTarget(net, edgeIndex, exitNode, targetEdgeIndex) ?: break
                edgeIndex = next.first
                towardEnd = next.second
                s = if (towardEnd) 0.0 else net.edges[edgeIndex].lengthMeters
            }
        }

        return RailForwardPath.Pose(
            edgeIndex = edgeIndex,
            sMeters = s,
            travelingTowardEnd = towardEnd,
            directionKnown = known,
        )
    }

    private fun pickNextEdgeTowardTarget(
        net: RailNetwork,
        viaEdgeIndex: Int,
        atNodeId: String,
        targetEdgeIndex: Int,
    ): Pair<Int, Boolean>? {
        val incident = net.nodeToEdges[atNodeId].orEmpty()
        // Предпочитаем целевое ребро, иначе любой другой сосед.
        // Prefer the target edge, otherwise any other neighbor.
        val ordered = incident.sortedBy { if (it == targetEdgeIndex) 0 else 1 }
        for (edgeIndex in ordered) {
            if (edgeIndex == viaEdgeIndex) continue
            val edge = net.edges[edgeIndex]
            val enterAtStart = edge.startNodeId == atNodeId
            val enterAtEnd = edge.endNodeId == atNodeId
            if (!enterAtStart && !enterAtEnd) continue
            return edgeIndex to enterAtStart
        }
        return null
    }

    private fun remainingForward(
        edge: RailEdge,
        sMeters: Double,
        travelingTowardEnd: Boolean,
        directionKnown: Boolean,
    ): Double {
        if (!directionKnown) {
            return min(sMeters, (edge.lengthMeters - sMeters).coerceAtLeast(0.0))
        }
        return if (travelingTowardEnd) {
            (edge.lengthMeters - sMeters).coerceAtLeast(0.0)
        } else {
            sMeters.coerceAtLeast(0.0)
        }
    }

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
        val arrival: RailForwardPath.Arrival?,
        val status: RailAdvanceStatus,
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
    val status: RailAdvanceStatus = RailAdvanceStatus.SAME_EDGE_FORWARD,
    val debtMeters: Double = 0.0,
    val capApplied: Boolean = false,
    val pathDeltaBeforeCap: Double = deltaMeters,
    val coveredDurationSec: Double = 0.0,
    val creditKind: RailCreditKind = RailCreditKind.NONE,
)
