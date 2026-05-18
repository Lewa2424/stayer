package com.example.stayer

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView
import com.example.stayer.modes.race.RaceSegmentPlan

/**
 * Адаптер участков режима забега.
 * RecyclerView adapter for race mode segments.
 */
class RaceSegmentAdapter(
    private val segments: MutableList<RaceSegmentPlan>,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<RaceSegmentAdapter.RaceSegmentViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RaceSegmentViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_race_segment, parent, false)
        return RaceSegmentViewHolder(view)
    }

    override fun onBindViewHolder(holder: RaceSegmentViewHolder, position: Int) {
        holder.bind(segments[position])
    }

    override fun getItemCount(): Int = segments.size

    fun addSegment(segment: RaceSegmentPlan) {
        segments.add(segment)
        notifyItemInserted(segments.lastIndex)
        onChanged()
    }

    fun getSegments(): List<RaceSegmentPlan> = segments.toList()

    fun removeSegment(position: Int) {
        if (position !in segments.indices) return
        segments.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, segments.size - position)
        onChanged()
    }

    inner class RaceSegmentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val etDistance = itemView.findViewById<EditText>(R.id.etRaceSegmentDistance)
        private val etPace = itemView.findViewById<EditText>(R.id.etRaceSegmentPace)
        private val btnDelete = itemView.findViewById<ImageButton>(R.id.btnDeleteRaceSegment)

        fun bind(segment: RaceSegmentPlan) {
            etDistance.clearWatcher()
            etPace.clearWatcher()

            etDistance.setText(if (segment.distanceKm > 0.0) String.format("%.2f", segment.distanceKm) else "")
            etPace.setText(if (segment.targetPaceSecPerKm > 0) formatPace(segment.targetPaceSecPerKm) else "")

            btnDelete.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) removeSegment(position)
            }

            etDistance.watchText { text ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@watchText
                val current = segments.getOrNull(position) ?: return@watchText
                val value = text.replace(',', '.').toDoubleOrNull() ?: return@watchText
                segments[position] = current.copy(distanceKm = value)
                onChanged()
            }

            etPace.watchText { text ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@watchText
                val current = segments.getOrNull(position) ?: return@watchText
                val value = parsePaceToSecPerKm(text) ?: return@watchText
                segments[position] = current.copy(targetPaceSecPerKm = value)
                onChanged()
            }
        }

        private val watcherTagKey = R.id.btnDeleteRaceSegment

        private fun EditText.clearWatcher() {
            val old = getTag(watcherTagKey) as? TextWatcher ?: return
            removeTextChangedListener(old)
            setTag(watcherTagKey, null)
        }

        private fun EditText.watchText(action: (String) -> Unit) {
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    action(s?.toString()?.trim().orEmpty())
                }
            }
            setTag(watcherTagKey, watcher)
            addTextChangedListener(watcher)
        }

        private fun parsePaceToSecPerKm(text: String): Int? {
            val parts = text.trim().split(":")
            if (parts.size != 2) return null
            return try {
                val minutes = parts[0].toInt()
                val seconds = parts[1].toInt()
                if (minutes < 0 || seconds !in 0..59) null else minutes * 60 + seconds
            } catch (_: Exception) {
                null
            }
        }

        private fun formatPace(secPerKm: Int): String {
            val minutes = secPerKm / 60
            val seconds = secPerKm % 60
            return "%d:%02d".format(minutes, seconds)
        }
    }
}
