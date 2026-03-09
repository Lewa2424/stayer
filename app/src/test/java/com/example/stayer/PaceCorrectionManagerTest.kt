package com.example.stayer

import com.example.stayer.engine.PaceCorrectionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PaceCorrectionManagerTest {

    @Test
    fun batchAndPerSecondInputsProduceSameStablePhrase() {
        val batched = PaceCorrectionManager().apply {
            feedSample(deltaM = 150.0, durationSec = 30.0)
        }
        val perSecond = PaceCorrectionManager().apply {
            repeat(30) { feedSample(deltaM = 5.0, durationSec = 1.0) }
        }

        val batchedSuggestion = repeatUntilSuggestion(batched, targetPaceSecPerKm = 345, elapsedSec = 30.0)
        val perSecondSuggestion = repeatUntilSuggestion(perSecond, targetPaceSecPerKm = 345, elapsedSec = 30.0)

        assertEquals("Медленнее", batchedSuggestion)
        assertEquals(perSecondSuggestion, batchedSuggestion)
    }

    @Test
    fun smoothArcDoesNotSuppressSuggestion() {
        val manager = PaceCorrectionManager()
        manager.feedSample(deltaM = 100.0, durationSec = 30.0)
        feedScenario(manager, buildSmoothArc())

        val suggestion = repeatUntilSuggestion(manager, targetPaceSecPerKm = 345, elapsedSec = 30.0)

        assertEquals("Медленнее", suggestion)
    }

    @Test
    fun sharpTurnSuppressesSuggestion() {
        val manager = PaceCorrectionManager()
        manager.feedSample(deltaM = 100.0, durationSec = 30.0)
        feedScenario(manager, buildSharpTurn(firstLegMeters = 50.0, secondLegMeters = 50.0))

        val suggestion = repeatUntilSuggestion(manager, targetPaceSecPerKm = 345, elapsedSec = 30.0)

        assertNull(suggestion)
    }

    @Test
    fun insideDeadZoneRemainsSilent() {
        val manager = PaceCorrectionManager().apply {
            feedSample(deltaM = 100.0, durationSec = 30.0)
        }

        val suggestion = repeatUntilSuggestion(manager, targetPaceSecPerKm = 309, elapsedSec = 30.0)

        assertNull(suggestion)
    }

    @Test
    fun mediumDeviationUsesIntermediatePhrase() {
        val manager = PaceCorrectionManager().apply {
            feedSample(deltaM = 100.0, durationSec = 30.0)
        }

        val suggestion = repeatUntilSuggestion(manager, targetPaceSecPerKm = 325, elapsedSec = 30.0)

        assertEquals("Немного медленнее", suggestion)
    }

    private fun repeatUntilSuggestion(
        manager: PaceCorrectionManager,
        targetPaceSecPerKm: Int,
        elapsedSec: Double
    ): String? {
        var result: String? = null
        repeat(10) { tick ->
            result = manager.maybeSuggest(
                targetPaceSecPerKm = targetPaceSecPerKm,
                currentTimeMs = 30_000L + tick * 1_000L,
                currentElapsedSec = elapsedSec
            )
            if (result != null) return result
        }
        return result
    }

    private fun feedScenario(manager: PaceCorrectionManager, pointsMeters: List<Pair<Double, Double>>) {
        pointsMeters.forEachIndexed { idx, (xMeters, yMeters) ->
            val lat = yMeters / 111_320.0
            val lon = xMeters / 111_320.0
            manager.feedGpsPoint(latDeg = lat, lonDeg = lon, elapsedSec = idx.toDouble())
        }
    }

    private fun buildSmoothArc(): List<Pair<Double, Double>> {
        val radius = 36.5
        return (0..30).map { sec ->
            val s = (100.0 / 30.0) * sec
            val theta = s / radius
            val x = radius * sin(theta)
            val y = radius * (1 - cos(theta))
            x to y
        }
    }

    private fun buildSharpTurn(firstLegMeters: Double, secondLegMeters: Double): List<Pair<Double, Double>> {
        val firstSteps = 15
        val secondSteps = 15
        val first = (0..firstSteps).map { step ->
            val x = firstLegMeters * step / firstSteps
            x to 0.0
        }
        val second = (1..secondSteps).map { step ->
            val y = secondLegMeters * step / secondSteps
            firstLegMeters to y
        }
        return first + second
    }
}
