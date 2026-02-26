package com.example.stayer

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DisplayGoalsActivity : AppCompatActivity() {
    // Инициализация переменных для TextView
    private lateinit var targetDistanceTextView: TextView
    private lateinit var targetTimeTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_display_goals) // Устанавливаем разметку для экрана отображения целей

        // Инициализация элементов
        targetDistanceTextView = findViewById(R.id.targetDistanceTextView)
        targetTimeTextView = findViewById(R.id.targetTimeTextView)

        // Получение данных из Intent
        val distance = intent.getStringExtra("TARGET_DISTANCE")
        val time = intent.getStringExtra("TARGET_TIME")

        // Установка текста в TextView
        targetDistanceTextView.text = distance ?: "Не задано"
        targetTimeTextView.text = time ?: "Не задано"
    }
}