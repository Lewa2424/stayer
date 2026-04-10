package com.example.stayer

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.stayer.history.WorkoutHistoryRepository

/**
 * Экран списка сохранённых тренировок.
 * Screen that shows saved workout history cards.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var historyAdapter: WorkoutHistoryAdapter
    private val workoutHistoryList = mutableListOf<WorkoutHistory>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        historyAdapter = WorkoutHistoryAdapter(this, workoutHistoryList)
        recyclerView.adapter = historyAdapter

        loadWorkoutHistory()
    }

    /**
     * Загружает историю из единого repository и обновляет адаптер.
     * Loads workout history from the shared repository and refreshes the adapter.
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun loadWorkoutHistory() {
        val workouts = WorkoutHistoryRepository(this).loadAll()
        workoutHistoryList.clear()
        workoutHistoryList.addAll(workouts)
        historyAdapter.notifyDataSetChanged()
        Log.d("WorkoutHistory", "Loaded ${workouts.size} workouts")
    }
}
