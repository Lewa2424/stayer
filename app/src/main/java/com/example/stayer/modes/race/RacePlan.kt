package com.example.stayer.modes.race

data class RaceSegmentPlan(
    val distanceKm: Double,
    val targetPaceSecPerKm: Int
)

data class RacePlan(
    val totalDistanceKm: Double,
    val segments: List<RaceSegmentPlan>
)
