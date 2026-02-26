package com.example.stayer

data class WorkoutHistory(
    val date: String,
    val distance: Float,
    val time: String,
    val speed: Float,
    val elapsedMs: Long = 0L,
    val workoutMode: String = "normal",        // "normal" | "interval" | "combined"
    val avgPaceWorkSec: Int? = null,           // средний темп фаз WORK (сек/км)
    val avgPaceRestSec: Int? = null,           // средний темп фаз REST (сек/км)
    val avgPaceWithoutWarmupSec: Int? = null,  // средний темп без разминки/заминки
    val avgPaceTotalSec: Int? = null,          // средний темп всей тренировки
)