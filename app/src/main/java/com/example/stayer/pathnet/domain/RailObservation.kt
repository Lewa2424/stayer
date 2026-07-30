package com.example.stayer.pathnet.domain

import com.example.stayer.pathnet.model.GeoPoint

/**
 * Единое наблюдение для rail-одометра (волна 1 «Липучка»).
 * Unified observation for the rail odometer (Lipuchka wave 1).
 */
data class RailObservation(
    val point: GeoPoint?,
    val dtSec: Double,
    val rawDeltaMeters: Double?,
    val accuracyMeters: Double,
    val locationSpeedMps: Double?,
    val cadenceDeltaMeters: Double?,
    val quality: ObservationQuality,
    val timestampNanos: Long = 0L,
)

enum class ObservationQuality {
    /** Фильтр GPS принял точку. GPS filter accepted the point. */
    ACCEPTED_GPS,
    /** Точка отклонена фильтром, но используется для soft-rail. Rejected by filter, soft-rail only. */
    SOFT_GPS,
    /** Только шагомер / DR. Cadence / dead-reckoning only. */
    CADENCE_ONLY,
}
