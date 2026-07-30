package com.example.stayer.pathnet.domain

/**
 * Причина/статус одного тика продвижения по рельсам (для логов и тестов).
 * Reason/status of a single rail-advance tick (for logs and tests).
 */
enum class RailAdvanceStatus {
    SAME_EDGE_FORWARD,
    SAME_EDGE_BACKWARD_ZERO,
    EDGE_TRANSITION,
    BEST_UNREACHABLE,
    NO_FEASIBLE_CANDIDATE,
    /** CAP больше не двигатель дистанции; оставлено для совместимости логов. */
    CAP_APPLIED,
    OFF_RAIL,
    RELOCK,
    DIRECTION_RECOVERED,
    HOLD,
    MOTION_SLIDE,
    REVERSE_PENDING,
    REVERSE_CONFIRMED,
    REPLAY_FORWARD,
}
