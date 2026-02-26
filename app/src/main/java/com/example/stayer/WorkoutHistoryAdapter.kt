package com.example.stayer

import android.content.Context
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
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

    override fun onBindViewHolder(holder: WorkoutViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads[0] == true) {
            holder.bindExpandState()
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: WorkoutViewHolder, position: Int) {
        val workout = workoutHistoryList[position]
        holder.bind(workout)
        holder.bindExpandState()
    }

    override fun getItemCount(): Int {
        return workoutHistoryList.size
    }

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    inner class WorkoutViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Layouts
        private val headerLayout: ConstraintLayout = itemView.findViewById(R.id.history_header_layout)
        private val detailsLayout: LinearLayout = itemView.findViewById(R.id.history_details_layout)

        // Header Views
        private val dateTextView: TextView = itemView.findViewById(R.id.history_date)
        private val targetTextView: TextView = itemView.findViewById(R.id.history_target)
        private val expandIcon: ImageView = itemView.findViewById(R.id.history_expand_icon)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.history_delete)

        // Details Views
        private val actualDistanceText: TextView = itemView.findViewById(R.id.history_actual_distance)
        private val actualTimeText: TextView = itemView.findViewById(R.id.history_actual_time)
        private val actualPaceText: TextView = itemView.findViewById(R.id.history_actual_pace)
        private val intervalStatsText: TextView = itemView.findViewById(R.id.history_interval_stats)

        var isExpanded = false

        fun bindExpandState() {
            if (isExpanded) {
                detailsLayout.visibility = View.VISIBLE
                expandIcon.rotation = 180f
            } else {
                detailsLayout.visibility = View.GONE
                expandIcon.rotation = 0f
            }
        }

        fun bind(workout: WorkoutHistory) {
            // -- Header --
            dateTextView.text = workout.date

            // Format target text gracefully
            val targetStr = when {
                workout.targetDistanceKm != null -> String.format(Locale.getDefault(), "🎯 %.2f км", workout.targetDistanceKm)
                workout.targetTimeSec != null -> {
                    val m = workout.targetTimeSec / 60
                    val s = workout.targetTimeSec % 60
                    "🎯 %d:%02d".format(m, s)
                }
                else -> String.format(Locale.getDefault(), "🏃 %.2f км", workout.distance) // fallback logic for old
            }
            targetTextView.text = targetStr

            // -- Details --
            actualDistanceText.text = String.format(Locale.getDefault(), "🏃 %.2f км", workout.distance)
            actualTimeText.text = "⏱ ${workout.time}"
            actualPaceText.text = "⚡ ${formatPace(workout.elapsedMs, workout.distance)}"

            // -- Interval Stats --
            if ((workout.workoutMode == "interval" || workout.workoutMode == "combined") && workout.avgPaceWorkSec != null) {
                intervalStatsText.visibility = View.VISIBLE
                intervalStatsText.text = "📊 Работа: ${formatSecPerKm(workout.avgPaceWorkSec)} | 🚶‍♂️ Отдых: ${formatSecPerKm(workout.avgPaceRestSec)}"
            } else {
                intervalStatsText.visibility = View.GONE
            }

            // -- Click Listeners --
            headerLayout.setOnClickListener {
                isExpanded = !isExpanded
                recyclerView?.let { rv ->
                    TransitionManager.beginDelayedTransition(rv, AutoTransition().apply { duration = 200 })
                }
                notifyItemChanged(adapterPosition, true)
            }

            deleteButton.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    workoutHistoryList.removeAt(position)
                    notifyItemRemoved(position)
                    saveHistoryList(workoutHistoryList)
                }
            }
        }
    }
}