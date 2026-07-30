package com.example.stayer

import com.example.stayer.pathnet.domain.PathGraphTopology
import com.example.stayer.pathnet.domain.RailAdvanceStatus
import com.example.stayer.pathnet.domain.RailEdge
import com.example.stayer.pathnet.domain.RailMatcher
import com.example.stayer.pathnet.domain.RailNetwork
import com.example.stayer.pathnet.domain.RailNetworkBuilder
import com.example.stayer.pathnet.model.GeoPoint
import com.example.stayer.pathnet.model.PathEdge
import com.example.stayer.pathnet.model.PathEdgeSource
import com.example.stayer.pathnet.model.PathGraph
import com.example.stayer.pathnet.model.PathNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регресс-тесты state-safe rail-одометра и топологии.
 * Regression tests for state-safe rail odometer and topology.
 */
class RailMatcherStateSafeTest {

    @Test
    fun fork_reachableNeighborGetsCredit() {
        val main0 = straightEdge("e0", "n0", "n1", lengthMeters = 10.0, lonStart = 0.0)
        val main1 = straightEdge("e1", "n1", "n2", lengthMeters = 10.0, lonStart = 10.0 / 111_320.0)
        val spur = RailEdge(
            edgeId = "spur",
            startNodeId = "n1",
            endNodeId = "nX",
            points = listOf(
                GeoPoint(0.0, 10.0 / 111_320.0),
                GeoPoint(0.001, 10.0 / 111_320.0),
            ),
            cumulativeMeters = listOf(0.0, 111.0),
        )
        val network = RailNetwork(listOf(main0, main1, spur))
        val matcher = RailMatcher(sameEdgeBiasMeters = 0.0, searchRadiusMeters = 80.0)
        matcher.updateNetwork(network)

        assertTrue(matcher.tryLock(GeoPoint(0.0, 1.0 / 111_320.0)))
        val onMain1 = GeoPoint(0.0, 15.0 / 111_320.0)
        val result = matcher.advance(onMain1, dtSec = 2.0)
        assertNotNull(result)
        assertTrue(result!!.deltaMeters > 1.0)
    }

    @Test
    fun unreachableNearestNeighbor_doesNotSwitchEdge() {
        // Параллельное изолированное ребро ближе к GPS, но без топологического пути.
        val e0 = straightEdge("e0", "n0", "n1", lengthMeters = 40.0, lonStart = 0.0)
        val isolated = RailEdge(
            edgeId = "iso",
            startNodeId = "nA",
            endNodeId = "nB",
            points = listOf(
                GeoPoint(15.0 / 111_320.0, 0.0),
                GeoPoint(15.0 / 111_320.0, 40.0 / 111_320.0),
            ),
            cumulativeMeters = listOf(0.0, 40.0),
        )
        val network = RailNetwork(listOf(e0, isolated))
        val matcher = RailMatcher(
            searchRadiusMeters = 80.0,
            sameEdgeBiasMeters = 0.0,
        )
        matcher.updateNetwork(network)

        assertTrue(matcher.tryLock(GeoPoint(0.0, 5.0 / 111_320.0)))
        matcher.advance(GeoPoint(0.0, 8.0 / 111_320.0), dtSec = 1.0)
        assertEquals("e0", matcher.currentEdgeId)

        val onIso = GeoPoint(15.0 / 111_320.0, 20.0 / 111_320.0)
        val r1 = matcher.advance(onIso, dtSec = 1.0)
        assertNotNull(r1)
        assertTrue(matcher.isLocked)
        // Не должны переключиться на недостижимое ребро.
        assertEquals("e0", matcher.currentEdgeId)
    }

    @Test
    fun cap_poseMovesOnlyByCreditedMeters() {
        val edge = straightEdge("e0", "n0", "n1", lengthMeters = 100.0, lonStart = 0.0)
        val network = RailNetwork(listOf(edge))
        val matcher = RailMatcher(maxPlausibleSpeedMps = 10.0)
        matcher.updateNetwork(network)

        assertTrue(matcher.tryLock(GeoPoint(0.0, 1.0 / 111_320.0)))
        val sBefore = matcher.currentSMeters

        val far = GeoPoint(0.0, 26.0 / 111_320.0)
        val result = matcher.advance(far, dtSec = 1.0)
        assertNotNull(result)
        assertTrue(result!!.deltaMeters <= 10.0 + 1e-6)
        // Далёкая проекция без независимого motion больше не запускает cap-марш.
        assertEquals(0.0, result.deltaMeters, 1e-6)
        val moved = matcher.currentSMeters - sBefore
        assertEquals(result.deltaMeters, moved, 0.5)
    }

    @Test
    fun confirmedForwardMotionCreditsAfterARejectedBackwardCandidate() {
        val edge = straightEdge("e0", "n0", "n1", lengthMeters = 50.0, lonStart = 0.0)
        val network = RailNetwork(listOf(edge))
        val matcher = RailMatcher(
            maxUnresolvedTicksForDirectionReset = 2,
            minDirectionLockMeters = 0.5,
            maxPlausibleSpeedMps = 10.0,
        )
        matcher.updateNetwork(network)

        assertTrue(matcher.tryLock(GeoPoint(0.0, 20.0 / 111_320.0)))
        // Одиночная задняя проекция не меняет направление.
        matcher.advance(GeoPoint(0.0, 18.0 / 111_320.0), dtSec = 1.0)

        var totalCredit = 0.0
        for (i in 1..4) {
            val lon = (20.0 + i * 3.0) / 111_320.0
            val r = matcher.process(
                com.example.stayer.pathnet.domain.RailObservation(
                    point = GeoPoint(0.0, lon),
                    dtSec = 1.0,
                    rawDeltaMeters = 3.0,
                    accuracyMeters = 5.0,
                    locationSpeedMps = 3.0,
                    cadenceDeltaMeters = null,
                    quality = com.example.stayer.pathnet.domain.ObservationQuality.ACCEPTED_GPS,
                ),
            )
            assertNotNull(r)
            totalCredit += r!!.deltaMeters
        }
        assertTrue("expected forward credit after recovery, got $totalCredit", totalCredit > 2.0)
    }

    @Test
    fun coincidentEndpoints_mergeDegree1() {
        val n0 = PathNode("n0", GeoPoint(46.30, 30.65))
        val n1 = PathNode("n1", GeoPoint(46.301, 30.65))
        val n2a = PathNode("n2a", GeoPoint(46.302, 30.65))
        val n2b = PathNode("n2b", GeoPoint(46.302, 30.65))
        val n3 = PathNode("n3", GeoPoint(46.303, 30.65))

        val e0 = PathEdge(
            id = "e0",
            startNodeId = "n0",
            endNodeId = "n1",
            geometry = listOf(n0.point, n1.point),
            lengthMeters = 111.0,
            source = PathEdgeSource.MANUAL_FREEFORM,
        )
        val e1 = PathEdge(
            id = "e1",
            startNodeId = "n1",
            endNodeId = "n2a",
            geometry = listOf(n1.point, n2a.point),
            lengthMeters = 111.0,
            source = PathEdgeSource.MANUAL_FREEFORM,
        )
        val e2 = PathEdge(
            id = "e2",
            startNodeId = "n2b",
            endNodeId = "n3",
            geometry = listOf(n2b.point, n3.point),
            lengthMeters = 111.0,
            source = PathEdgeSource.MANUAL_FREEFORM,
        )
        val graph = PathGraph(
            nodes = mapOf(n0.id to n0, n1.id to n1, n2a.id to n2a, n2b.id to n2b, n3.id to n3),
            edges = mapOf(e0.id to e0, e1.id to e1, e2.id to e2),
        )

        val before = PathGraphTopology.findCoincidentDisconnectedEndpoints(graph)
        assertTrue(before.isNotEmpty())

        val (merged, count) = PathGraphTopology.mergeCoincidentDegree1Endpoints(graph)
        assertTrue(count >= 1)
        assertTrue(merged.nodes.size < graph.nodes.size)
        // После merge концы должны быть соединены общим nodeId.
        val stillBroken = PathGraphTopology.findCoincidentDisconnectedEndpoints(merged)
        assertTrue(stillBroken.isEmpty())
    }

    @Test
    fun collapseDegree2_reducesStoredChain() {
        val e0 = straightEdge("e0", "n0", "n1", 4.0)
        val e1 = straightEdge("e1", "n1", "n2", 4.0)
        val e2 = straightEdge("e2", "n2", "n3", 4.0)
        val runtime = RailNetworkBuilder.collapseDegree2Chains(listOf(e0, e1, e2))
        assertEquals(1, runtime.size)
    }

    @Test
    fun holdPose_onNull_doesNotAdvanceS() {
        val e0 = straightEdge("e0", "n0", "n1", lengthMeters = 30.0)
        val matcher = RailMatcher()
        matcher.updateNetwork(RailNetwork(listOf(e0)))
        assertTrue(matcher.tryLock(GeoPoint(0.0, 5.0 / 111_320.0)))
        // Фиксируем направление вперёд маленьким шагом.
        matcher.advance(GeoPoint(0.0, 8.0 / 111_320.0), dtSec = 1.0)
        val s = matcher.currentSMeters
        // Backward projection при known direction → 0 credit, но раньше баг двигал s назад.
        val back = matcher.advance(GeoPoint(0.0, 6.0 / 111_320.0), dtSec = 1.0)
        assertNotNull(back)
        // При same-edge backward arrival != null с delta=0 — pose может обновиться к проекции
        // только если arrival не null. После фикса: при delta=0 fullPath помещается,
        // movePoseAlongPath с credit=0 возвращает from — s не меняется.
        if (back!!.deltaMeters <= 1e-9) {
            assertEquals(s, matcher.currentSMeters, 1e-6)
        }
    }

    private fun straightEdge(
        id: String,
        start: String,
        end: String,
        lengthMeters: Double,
        lonStart: Double = 0.0,
    ): RailEdge {
        val startPoint = GeoPoint(0.0, lonStart)
        val endPoint = GeoPoint(0.0, lonStart + lengthMeters / 111_320.0)
        return RailEdge(
            edgeId = id,
            startNodeId = start,
            endNodeId = end,
            points = listOf(startPoint, endPoint),
            cumulativeMeters = listOf(0.0, lengthMeters),
        )
    }
}
