package com.example.stayer

import com.example.stayer.pathnet.domain.ObservationQuality
import com.example.stayer.pathnet.domain.RailEdge
import com.example.stayer.pathnet.domain.RailMatcher
import com.example.stayer.pathnet.domain.RailNetwork
import com.example.stayer.pathnet.domain.RailObservation
import com.example.stayer.pathnet.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверяет safety-инварианты первой волны липкого rail-одометра. */
class LipuchkaWave1Test {

    @Test
    fun phantomJumpWithCorruptRawDoesNotOvercount() {
        val matcher = matcherAt(1.0)
        repeat(5) {
            val result = matcher.process(observation(500.0, raw = 50.0, speed = 50.0, cadence = 0.0))
            assertEquals(0.0, result!!.deltaMeters, 1e-6)
        }
        assertTrue(matcher.currentSMeters < 2.0)
    }

    @Test
    fun stationaryCadenceAndSpeedGiveNoCredit() {
        val matcher = matcherAt(10.0)
        repeat(5) {
            val result = matcher.process(observation(12.0, raw = 1.0, speed = 0.0, cadence = 0.0))
            assertEquals(0.0, result!!.deltaMeters, 1e-6)
        }
    }

    @Test
    fun smallBackThenForwardDoesNotReverse() {
        val matcher = matcherAt(10.0)
        matcher.process(observation(12.0, raw = 2.0, speed = 2.0))
        matcher.process(observation(9.0, raw = 2.0, speed = 2.0))
        val result = matcher.process(observation(14.0, raw = 2.0, speed = 2.0))
        assertTrue(result != null)
        assertTrue(matcher.isTravelingTowardEnd)
    }

    @Test
    fun poseDeltaEqualsCreditForNormalForwardMotion() {
        val matcher = matcherAt(10.0)
        val before = matcher.currentSMeters
        val result = matcher.process(observation(12.0, raw = 2.0, speed = 2.0))!!
        assertTrue(result.deltaMeters > 0.0)
        assertEquals(result.deltaMeters, matcher.currentSMeters - before, 0.5)
    }

    private fun matcherAt(sMeters: Double): RailMatcher {
        val matcher = RailMatcher()
        matcher.updateNetwork(
            RailNetwork(
                listOf(
                    RailEdge(
                        edgeId = "line",
                        startNodeId = "a",
                        endNodeId = "b",
                        points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1000.0 / 111_320.0)),
                        cumulativeMeters = listOf(0.0, 1000.0),
                    ),
                ),
            ),
        )
        assertTrue(matcher.tryLock(GeoPoint(0.0, sMeters / 111_320.0)))
        return matcher
    }

    private fun observation(
        candidateMeters: Double,
        raw: Double,
        speed: Double,
        cadence: Double? = null,
    ) = RailObservation(
        point = GeoPoint(0.0, candidateMeters / 111_320.0),
        dtSec = 1.0,
        rawDeltaMeters = raw,
        accuracyMeters = 5.0,
        locationSpeedMps = speed,
        cadenceDeltaMeters = cadence,
        quality = ObservationQuality.ACCEPTED_GPS,
    )
}
