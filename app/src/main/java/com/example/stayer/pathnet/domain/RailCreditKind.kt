package com.example.stayer.pathnet.domain

/**
 * Тип начисления дистанции по рельсам.
 * Kind of rail distance credit.
 */
enum class RailCreditKind {
    NONE,
    PROJECTION_CONFIRMED,
    MOTION_SLIDE,
    CADENCE_SLIDE,
    REPLAY_FORWARD,
    REPLAY_REVERSE,
    HOLD,
}
