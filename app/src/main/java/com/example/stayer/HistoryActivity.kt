package com.example.stayer

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var historyAdapter: WorkoutHistoryAdapter
    private val workoutHistoryList = mutableListOf<WorkoutHistory>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history) // Убедитесь, что layout существует

        recyclerView = findViewById(R.id.recyclerView) // Убедитесь, что у вас есть RecyclerView с этим ID
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Создаем и устанавливаем адаптер
        historyAdapter = WorkoutHistoryAdapter(this, workoutHistoryList)
        recyclerView.adapter = historyAdapter

        // Загружаем данные
        loadWorkoutHistory()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadWorkoutHistory() {
        val sharedPreferences = getSharedPreferences("WorkoutHistory", MODE_PRIVATE)
        val workoutJson = sharedPreferences.getString("workoutHistoryList", null)

        if (workoutJson != null) {
            try {
                val type = object : TypeToken<List<WorkoutHistory>>() {}.type
                val workouts: List<WorkoutHistory> = Gson().fromJson(workoutJson, type)

                workoutHistoryList.clear()  // Очищаем список перед добавлением новых данных
                workoutHistoryList.addAll(workouts.toMutableList())  // Добавляем все тренировки
                historyAdapter.notifyDataSetChanged()  // Обновляем адаптер для отображения данных

                Log.d("WorkoutHistory", "Loaded ${workouts.size} workouts")
            } catch (e: Exception) {
                Log.e("WorkoutHistory", "Error loading history: ${e.message}")
            }
        } else {
            Log.d("WorkoutHistory", "No data found")
        }
    }
}
