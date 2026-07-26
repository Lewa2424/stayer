package com.example.stayer

import com.example.stayer.engine.PaceCadenceScale
import org.junit.Assert.assertEquals
import org.junit.Test

class PaceCadenceScaleTest {
    @Test
    fun paceLevels_coverSevenToFourMinutes() {
        assertEquals(13, PaceCadenceScale.PACE_LEVELS_SEC.size)
        assertEquals(420, PaceCadenceScale.PACE_LEVELS_SEC.first())
        assertEquals(240, PaceCadenceScale.PACE_LEVELS_SEC.last())
    }

    @Test
    fun nearestPaceBucket_snapsToFifteenSecondStep() {
        assertEquals(330, PaceCadenceScale.nearestPaceBucket(328))
        assertEquals(315, PaceCadenceScale.nearestPaceBucket(308))
    }

    @Test
    fun formatPace_rendersMinutesAndSeconds() {
        assertEquals("7:00", PaceCadenceScale.formatPace(420))
        assertEquals("4:00", PaceCadenceScale.formatPace(240))
        assertEquals("5:30", PaceCadenceScale.formatPace(330))
    }
}
