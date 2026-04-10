package com.example.stayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.stayer.analytics.WorkoutAnalyticsEngine
import com.example.stayer.history.WorkoutHistoryRepository
import com.example.stayer.ui.analytics.AnalyticsScreen
import com.example.stayer.ui.theme.StayerTheme

/**
 * Экран сводной аналитики по истории тренировок.
 * Screen with summary analytics built from workout history.
 */
class AnalyticsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = WorkoutHistoryRepository(this)
        val engine = WorkoutAnalyticsEngine()

        setContent {
            StayerTheme {
                AnalyticsScreen(
                    historyRepository = repository,
                    analyticsEngine = engine,
                    onBack = { finish() }
                )
            }
        }
    }
}
