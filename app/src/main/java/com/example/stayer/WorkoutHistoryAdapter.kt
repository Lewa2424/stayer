package com.example.stayer

import android.content.Context
import android.graphics.Color
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import java.util.Locale

class WorkoutHistoryAdapter(
    private val context: Context,
    private val workoutHistoryList: MutableList<WorkoutHistory>
) : RecyclerView.Adapter<WorkoutHistoryAdapter.WorkoutViewHolder>() {

    private val primaryTextColor = Color.parseColor("#424242")
    private val secondaryTextColor = Color.parseColor("#616161")
    private val positiveDeltaColor = Color.parseColor("#2E7D32")
    private val negativeDeltaColor = Color.parseColor("#C62828")

    private fun formatDistance(distanceKm: Float): String {
        return String.format(Locale.getDefault(), "%.2f км", distanceKm)
    }

    private fun paceSeconds(elapsedMs: Long, distanceKm: Float): Int? {
        if (distanceKm <= 0f || elapsedMs <= 0L) return null
        return ((elapsedMs / 1000f) / distanceKm).toInt()
    }

    private fun formatPace(elapsedMs: Long, distanceKm: Float): String {
        return formatSecPerKm(paceSeconds(elapsedMs, distanceKm))
    }

    private fun formatSecPerKm(sec: Int?): String {
        if (sec == null || sec <= 0) return "—"
        val minutes = sec / 60
        val seconds = sec % 60
        return String.format(Locale.getDefault(), "%d:%02d/км", minutes, seconds)
    }

    private fun formatClock(totalSec: Int): String {
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatSignedClockDelta(deltaSec: Int): String {
        val sign = if (deltaSec > 0) "+" else "−"
        val abs = kotlin.math.abs(deltaSec)
        return "$sign${formatClock(abs)}"
    }

    private fun formatSignedPaceDelta(deltaSec: Int): String {
        val sign = if (deltaSec > 0) "+" else "−"
        return "$sign${formatSecPerKm(kotlin.math.abs(deltaSec))}"
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

    override fun getItemCount(): Int = workoutHistoryList.size

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
        private val headerLayout: ConstraintLayout = itemView.findViewById(R.id.history_header_layout)
        private val detailsLayout: LinearLayout = itemView.findViewById(R.id.history_details_layout)

        private val dateTextView: TextView = itemView.findViewById(R.id.history_date)
        private val targetTextView: TextView = itemView.findViewById(R.id.history_target)
        private val expandIcon: ImageView = itemView.findViewById(R.id.history_expand_icon)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.history_delete)

        private val summaryContainer: LinearLayout = itemView.findViewById(R.id.history_summary_container)
        private val segmentsTitle: TextView = itemView.findViewById(R.id.history_segments_title)
        private val segmentsContainer: LinearLayout = itemView.findViewById(R.id.history_segments_container)

        var isExpanded = false
        private var isCheckpointDetailsExpanded = false

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
            dateTextView.text = workout.date
            targetTextView.text = decorateTestLabel(buildHeaderGoalText(workout), workout.isTest)
            isCheckpointDetailsExpanded = false

            bindDetails(workout)

            headerLayout.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                isExpanded = !isExpanded
                recyclerView?.let { rv ->
                    TransitionManager.beginDelayedTransition(rv, AutoTransition().apply { duration = 200 })
                }
                notifyItemChanged(position, true)
            }

            deleteButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                workoutHistoryList.removeAt(position)
                notifyItemRemoved(position)
                saveHistoryList(workoutHistoryList)
            }
        }

        private fun bindDetails(workout: WorkoutHistory) {
            summaryContainer.removeAllViews()
            segmentsContainer.removeAllViews()
            segmentsTitle.visibility = View.GONE
            segmentsContainer.visibility = View.GONE

            when (workout.workoutMode) {
                "interval" -> bindIntervalDetails(workout)
                "combined" -> bindCombinedDetails(workout)
                else -> bindNormalDetails(workout)
            }
        }

        private fun bindNormalDetails(workout: WorkoutHistory) {
            addSummaryLine("Фактическая дистанция: ${formatDistance(workout.distance)}")
            addSummaryLine("Фактическое время: ${workout.time}")
            addSummaryLine("Средний темп: ${formatPace(workout.elapsedMs, workout.distance)}")
            

            buildNormalDeviation(workout)?.let {
                addSummaryLine("Отклонение от цели: $it")
            }

            val checkpoints = workout.checkpointDetails.orEmpty()
            if (checkpoints.isNotEmpty()) {
                bindCheckpointToggle(checkpoints)
            }
        }

        private fun bindIntervalDetails(workout: WorkoutHistory) {
            addSummaryLine("Общая дистанция: ${formatDistance(workout.distance)}")
            addSummaryLine("Общее время: ${workout.time}")
            buildSharedTargetPaceLine(workout)?.let { addSummaryLine(it) }
            
            
            bindSegments(workout)
        }

        private fun bindCombinedDetails(workout: WorkoutHistory) {
            addSummaryLine("Общая дистанция: ${formatDistance(workout.distance)}")
            addSummaryLine("Общее время: ${workout.time}")
            
            
            bindSegments(workout)
        }

        private fun bindSegments(workout: WorkoutHistory) {
            val segments = workout.segmentDetails.orEmpty()
            if (segments.isNotEmpty()) {
                segmentsTitle.visibility = View.VISIBLE
                segmentsContainer.visibility = View.VISIBLE
                segments.forEach { segment ->
                    addSegmentLine(buildSegmentLine(workout, segment))
                }
                return
            }

            if (workout.avgPaceWorkSec != null) {
                addSummaryLine("Средний темп участков: ${formatSecPerKm(workout.avgPaceWorkSec)}")
            }
            addSummaryLine(
                "Детализация по участкам недоступна для этой записи.",
                secondary = true
            )
        }

        private fun buildHeaderGoalText(workout: WorkoutHistory): String {
            return when (workout.workoutMode) {
                "interval" -> buildExpandedIntervalHeader(workout.goalLabel)
                "combined" -> "Комбо"
                else -> {
                    workout.goalLabel?.takeIf { it.isNotBlank() }?.let { return it }
                    val parts = mutableListOf<String>()
                    workout.targetDistanceKm?.takeIf { it > 0f }?.let { parts += formatDistance(it) }
                    when {
                        workout.normalGoalMode == 1 && workout.targetPaceSecPerKm != null ->
                            parts += formatSecPerKm(workout.targetPaceSecPerKm)
                        workout.targetTimeSec != null -> parts += formatClock(workout.targetTimeSec)
                    }
                    if (parts.isNotEmpty()) parts.joinToString(" • ")
                    else formatDistance(workout.distance)
                }
            }
        }

        private fun decorateTestLabel(baseText: String, isTest: Boolean): String {
            if (!isTest) return baseText
            return "Тест • $baseText"
        }

        private fun buildNormalDeviation(workout: WorkoutHistory): String? {
            val targetTime = workout.targetTimeSec ?: return null
            val actualTime = (workout.elapsedMs / 1000L).toInt().takeIf { it > 0 } ?: parseTimeToSec(workout.time)
            return formatSignedClockDelta(actualTime - targetTime)
        }

        private fun bindCheckpointToggle(checkpoints: List<WorkoutHistoryCheckpoint>) {
            val toggleButton = makeDetailsButton(if (isCheckpointDetailsExpanded) "Скрыть детали" else "Детали")
            toggleButton.setOnClickListener {
                isCheckpointDetailsExpanded = !isCheckpointDetailsExpanded
                toggleButton.text = if (isCheckpointDetailsExpanded) "Скрыть детали" else "Детали"
                renderCheckpointDetails(checkpoints)
            }
            summaryContainer.addView(toggleButton)
            renderCheckpointDetails(checkpoints)
        }

        private fun renderCheckpointDetails(checkpoints: List<WorkoutHistoryCheckpoint>) {
            segmentsContainer.removeAllViews()
            if (!isCheckpointDetailsExpanded) {
                segmentsTitle.visibility = View.GONE
                segmentsContainer.visibility = View.GONE
                return
            }

            segmentsTitle.text = "Детали чекпоинтов"
            segmentsTitle.visibility = View.VISIBLE
            segmentsContainer.visibility = View.VISIBLE
            checkpoints.forEachIndexed { index, checkpoint ->
                addCheckpointLine(index, checkpoint)
            }
        }

        private fun addCheckpointLine(index: Int, checkpoint: WorkoutHistoryCheckpoint) {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (index == 0) 0 else dp(10)
                }
            }

            container.addView(makeTextView(buildCheckpointRange(checkpoint), secondary = false, topMarginDp = 0))
            container.addView(makeTextView("Время: ${formatClock(checkpoint.durationSec)}", secondary = false, topMarginDp = 4))
            container.addView(makeTextView("Темп: ${formatSecPerKm(checkpoint.paceSecPerKm)}", secondary = false, topMarginDp = 4))
            container.addView(makeDeltaTextView(checkpoint.deltaSec))
            segmentsContainer.addView(container)
        }

        private fun buildCheckpointRange(checkpoint: WorkoutHistoryCheckpoint): String {
            return String.format(
                Locale.getDefault(),
                "%.2f-%.2f км",
                checkpoint.fromKm,
                checkpoint.toKm
            )
        }

        private fun makeDeltaTextView(deltaSec: Int): TextView {
            val color = when {
                deltaSec > 0 -> positiveDeltaColor
                deltaSec < 0 -> negativeDeltaColor
                else -> secondaryTextColor
            }
            return makeTextView("Отклонение: ${formatSignedClockDelta(deltaSec)}", secondary = false, topMarginDp = 4).apply {
                setTextColor(color)
            }
        }

        private fun makeDetailsButton(text: String): AppCompatButton {
            return AppCompatButton(context).apply {
                this.text = text
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(12)
                }
                isAllCaps = false
            }
        }

        private fun buildSegmentLine(workout: WorkoutHistory, segment: WorkoutHistorySegment): String {
            val typeLabel = when {
                workout.workoutMode == "combined" && segment.type == "PACE" -> "темповый"
                workout.workoutMode == "combined" && segment.type == "WORK" -> "интервальный"
                else -> null
            }
            val title = if (typeLabel != null) "${segment.title} ($typeLabel)" else segment.title
            return buildString {
                append(title)
                append('\n')
                append("Темп: ")
                append(formatSecPerKm(segment.actualPaceSecPerKm))
                append('\n')
                append("Дистанция: ")
                append(formatDistance(segment.distanceKm))
            }
        }

        private fun addSummaryLine(text: String, secondary: Boolean = false) {
            summaryContainer.addView(makeTextView(text, secondary = secondary, topMarginDp = if (summaryContainer.childCount == 0) 0 else 8))
        }

        private fun addSegmentLine(text: String) {
            segmentsContainer.addView(makeTextView(text, secondary = false, topMarginDp = if (segmentsContainer.childCount == 0) 0 else 8))
        }

        private fun makeTextView(text: String, secondary: Boolean, topMarginDp: Int): TextView {
            return TextView(context).apply {
                this.text = text
                textSize = 15f
                setLineSpacing(0f, 1.1f)
                setTextColor(if (secondary) secondaryTextColor else primaryTextColor)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(topMarginDp)
                }
            }
        }

        private fun dp(value: Int): Int {
            return (value * itemView.resources.displayMetrics.density).toInt()
        }

        private fun parseTimeToSec(value: String): Int {
            val parts = value.split(":").mapNotNull { it.toIntOrNull() }
            return when (parts.size) {
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> 0
            }
        }

        private fun buildExpandedIntervalHeader(goalLabel: String?): String {
            val compact = goalLabel?.trim().orEmpty()
            val match = Regex("""(\d+)×(\d{2}:\d{2})\s*/\s*(\d{2}:\d{2})""").matchEntire(compact)
            if (match != null) {
                val repeats = match.groupValues[1]
                val work = match.groupValues[2]
                val rest = match.groupValues[3]
                return "Ускорения: ${repeats}×${work} мин.\nОтдых: ${rest} мин."
            }
            return compact.ifBlank { "Интервальная" }
        }

        private fun buildSharedTargetPaceLine(workout: WorkoutHistory): String? {
            val targetPaces = workout.segmentDetails.orEmpty()
                .mapNotNull { it.targetPaceSecPerKm }
                .distinct()
            return if (targetPaces.size == 1) {
                "Цель участков: ${formatSecPerKm(targetPaces.first())}"
            } else {
                null
            }
        }
    }
}
