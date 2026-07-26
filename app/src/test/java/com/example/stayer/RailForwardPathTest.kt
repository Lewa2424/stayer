package com.example.stayer

import com.example.stayer.pathnet.domain.RailEdge
import com.example.stayer.pathnet.domain.RailForwardPath
import com.example.stayer.pathnet.domain.RailNetwork
import com.example.stayer.pathnet.domain.RailNetworkBuilder
import com.example.stayer.pathnet.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверяет, что multi-hop путь по коротким рёбрам не ворует метры на стыках.
 * Verifies multi-hop progress across short edges does not steal meters at joints.
 */
class RailForwardPathTest {
    @Test
    fun measure_acrossThreeShortEdges_creditsFullPath() {
        // Три куска по 4 м: n0-n1, n1-n2, n2-n3. Старт у начала первого, цель — середина третьего.
        val e0 = straightEdge("e0", "n0", "n1", lengthMeters = 4.0)
        val e1 = straightEdge("e1", "n1", "n2", lengthMeters = 4.0)
        val e2 = straightEdge("e2", "n2", "n3", lengthMeters = 4.0)
        val network = RailNetwork(listOf(e0, e1, e2))

        val arrival = RailForwardPath.measure(
            network = network,
            from = RailForwardPath.Pose(
                edgeIndex = 0,
                sMeters = 0.5,
                travelingTowardEnd = true,
                directionKnown = true,
            ),
            toEdgeIndex = 2,
            toSMeters = 2.0,
            maxPathMeters = 40.0,
        )

        assertNotNull(arrival)
        // remain on e0 (3.5) + full e1 (4) + into e2 (2) = 9.5
        assertEquals(9.5, arrival!!.deltaMeters, 1e-6)
        assertTrue(arrival.travelingTowardEnd)
    }

    @Test
    fun measure_sameEdgeForward_onlyCountsForward() {
        val edge = straightEdge("e0", "n0", "n1", lengthMeters = 10.0)
        val network = RailNetwork(listOf(edge))
        val arrival = RailForwardPath.measure(
            network = network,
            from = RailForwardPath.Pose(
                edgeIndex = 0,
                sMeters = 7.0,
                travelingTowardEnd = true,
                directionKnown = true,
            ),
            toEdgeIndex = 0,
            toSMeters = 4.0,
            maxPathMeters = 40.0,
        )
        assertNotNull(arrival)
        assertEquals(0.0, arrival!!.deltaMeters, 1e-9)
    }

    @Test
    fun collapseDegree2_mergesChainIntoOneEdge() {
        val e0 = straightEdge("e0", "n0", "n1", lengthMeters = 4.0)
        val e1 = straightEdge("e1", "n1", "n2", lengthMeters = 4.0)
        val e2 = straightEdge("e2", "n2", "n3", lengthMeters = 4.0)
        val collapsed = RailNetworkBuilder.collapseDegree2Chains(listOf(e0, e1, e2))
        assertEquals(1, collapsed.size)
        assertEquals(12.0, collapsed[0].lengthMeters, 1e-6)
        val ends = setOf(collapsed[0].startNodeId, collapsed[0].endNodeId)
        assertEquals(setOf("n0", "n3"), ends)
    }

    private fun straightEdge(
        id: String,
        start: String,
        end: String,
        lengthMeters: Double,
    ): RailEdge {
        // Геометрия условная: длины задаём cumulativeMeters напрямую (без Android Location).
        val startPoint = GeoPoint(0.0, 0.0)
        val endPoint = GeoPoint(0.0, lengthMeters / 111_320.0)
        return RailEdge(
            edgeId = id,
            startNodeId = start,
            endNodeId = end,
            points = listOf(startPoint, endPoint),
            cumulativeMeters = listOf(0.0, lengthMeters),
        )
    }
}
