package com.example.stayer

import com.example.stayer.pathnet.domain.RailEdge
import com.example.stayer.pathnet.domain.RailEdgeProjector
import com.example.stayer.pathnet.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RailEdgeProjectorTest {
    @Test
    fun pointAtArcLength_interpolatesAlongEdge() {
        val start = GeoPoint(46.3000, 30.6540)
        val end = GeoPoint(46.3010, 30.6540)
        val edge = RailEdge(
            edgeId = "e1",
            startNodeId = "n1",
            endNodeId = "n2",
            points = listOf(start, end),
            cumulativeMeters = listOf(0.0, 111.0),
        )
        val mid = RailEdgeProjector.pointAtArcLength(edge, 55.5)
        assertNotNull(mid)
        assertEquals(start.lat + (end.lat - start.lat) * 0.5, mid!!.lat, 1e-6)
    }
}
