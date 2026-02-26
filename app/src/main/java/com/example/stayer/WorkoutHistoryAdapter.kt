package com.example.stayer

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import java.util.Locale

// Класс адаптера для отображения истории тренировок
class WorkoutHistoryAdapter(
    private val context: Context,
    private val workoutHistoryList: MutableList<WorkoutHistory>
) : RecyclerView.Adapter<WorkoutHistoryAdapter.WorkoutViewHolder>() {

    private fun formatPace(elapsedMs: Long, distanceKm: Float): String {
        if (distanceKm <= 0f || elapsedMs <= 0L) return "—"
        val totalSeconds = elapsedMs / 1000
        val secPerKm = (totalSeconds.toFloat() / distanceKm).toInt()
        val minutes = secPerKm / 60
        val seconds = secPerKm % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun formatSecPerKm(sec: Int?): String {
        if (sec == null || sec <= 0) return "—"
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d/км".format(m, s)
    }

    private fun saveHistoryList(list: List<WorkoutHistory>) {
        val sharedPreferences = context.getSharedPreferences("WorkoutHistory", Context.MODE_PRIVATE)
        sharedPreferences.edit {
            putString("workoutHistoryList", Gson().toJson(list))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WorkoutViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_workout_history, parent, false)
        return WorkoutViewHolder(view)
    }

    override fun onBindViewHolder(holder: WorkoutViewHolder, position: Int) {
        val workout = workoutHistoryList[position]
        holder.bind(workout)
    }

    override fun getItemCount(): Int {
        return workoutHistoryList.size
    }

    inner class WorkoutViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateTextView: TextView = itemView.findViewById(R.id.history_date)
        private val distanceTextView: TextView = itemView.findViewById(R.id.history_distance)
        private val timeTextView: TextView = itemView.findViewById(R.id.history_time)
        private val paceTextView: TextView = itemView.findViewById(R.id.history_speed)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.history_delete)

        private val modeIcon: android.widget.ImageView = itemView.findViewById(R.id.history_mode_icon)

        fun bind(workout: WorkoutHistory) {
            // Устанавливаем иконку режима
            if (workout.workoutMode == "interval" || workout.workoutMode == "combined") {
                modeIcon.visibility = View.VISIBLE
                modeIcon.setImageResource(android.R.drawable.ic_menu_sort_by_size) // Placeholder icon for intervals
                
                // Клик по всей строке для показа деталей
                itemView.setOnClickListener {
                    showIntervalDetails(workout)
                }
            } else {
                modeIcon.visibility = View.INVISIBLE
                itemView.setOnClickListener(null)
            }

            // Устанавливаем значения для каждого TextView
            dateTextView.text = workout.date
            distanceTextView.text = String.format(Locale.getDefault(), "%.2f км", workout.distance)
            timeTextView.text = workout.time
            paceTextView.text = formatPace(workout.elapsedMs, workout.distance)

            deleteButton.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    workoutHistoryList.removeAt(position)
                    notifyItemRemoved(position)
                    saveHistoryList(workoutHistoryList)
                }
            }
        }

        private fun showIntervalDetails(workout: WorkoutHistory) {
            val sb = StringBuilder()
            sb.append("Средний темп по фазам:\n\n")
            sb.append("• Работа: ${formatSecPerKm(workout.avgPaceWorkSec)}\n")
            sb.append("• Отдых: ${formatSecPerKm(workout.avgPaceRestSec)}\n")
            sb.append("• Без разминки/заминки: ${formatSecPerKm(workout.avgPaceWithoutWarmupSec)}\n")
            sb.append("• Общий по тренировке: ${formatSecPerKm(workout.avgPaceTotalSec)}\n")

            AlertDialog.Builder(context)
                .setTitle("Детали тренировки")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show()
        }
    }
}