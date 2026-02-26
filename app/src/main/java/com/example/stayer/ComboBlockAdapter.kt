package com.example.stayer

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView Adapter for the combo workout block list.
 * Each item is a ComboBlock rendered with the appropriate card layout.
 */
class ComboBlockAdapter(
    private val blocks: MutableList<ComboBlock>,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<ComboBlockAdapter.BlockViewHolder>() {

    companion object {
        private const val TYPE_WARMUP = 0
        private const val TYPE_PACE = 1
        private const val TYPE_INTERVAL = 2
        private const val TYPE_COOLDOWN = 3
    }

    override fun getItemViewType(position: Int): Int = when (blocks[position]) {
        is ComboBlock.WarmupBlock -> TYPE_WARMUP
        is ComboBlock.PaceBlock -> TYPE_PACE
        is ComboBlock.IntervalBlock -> TYPE_INTERVAL
        is ComboBlock.CooldownBlock -> TYPE_COOLDOWN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BlockViewHolder {
        val layoutId = when (viewType) {
            TYPE_WARMUP -> R.layout.item_combo_warmup
            TYPE_PACE -> R.layout.item_combo_pace
            TYPE_INTERVAL -> R.layout.item_combo_interval
            TYPE_COOLDOWN -> R.layout.item_combo_cooldown
            else -> throw IllegalArgumentException("Unknown view type $viewType")
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return BlockViewHolder(view)
    }

    override fun onBindViewHolder(holder: BlockViewHolder, position: Int) {
        holder.bind(blocks[position], position)
    }

    override fun getItemCount(): Int = blocks.size

    fun addBlock(block: ComboBlock) {
        blocks.add(block)
        notifyItemInserted(blocks.size - 1)
        onChanged()
    }

    fun removeBlock(position: Int) {
        if (position in blocks.indices) {
            blocks.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, blocks.size - position)
            onChanged()
        }
    }

    fun getBlocks(): List<ComboBlock> = blocks.toList()

    inner class BlockViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        fun bind(block: ComboBlock, position: Int) {
            // Delete button (present in all layouts)
            itemView.findViewById<ImageButton>(R.id.btnDelete)?.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) removeBlock(pos)
            }

            when (block) {
                is ComboBlock.WarmupBlock -> bindWarmup(block, position)
                is ComboBlock.PaceBlock -> bindPace(block, position)
                is ComboBlock.IntervalBlock -> bindInterval(block, position)
                is ComboBlock.CooldownBlock -> bindCooldown(block, position)
            }
        }

        private fun bindWarmup(block: ComboBlock.WarmupBlock, pos: Int) {
            val etTime = itemView.findViewById<EditText>(R.id.etTime)
            val etPace = itemView.findViewById<EditText>(R.id.etPace)

            etTime.clearWatcher()
            etPace.clearWatcher()

            etTime.setText(if (block.durationSec > 0) formatTime(block.durationSec) else "")
            etPace.setText(block.pace?.let { formatPace(it) } ?: "")

            etTime.watchText { text ->
                val p = adapterPosition; if (p == RecyclerView.NO_POSITION) return@watchText
                val b = blocks.getOrNull(p) as? ComboBlock.WarmupBlock ?: return@watchText
                val sec = parseTimeToSec(text)
                if (sec != null && sec > 0) {
                    blocks[p] = b.copy(durationSec = sec)
                    onChanged()
                }
            }
            etPace.watchText { text ->
                val p = adapterPosition; if (p == RecyclerView.NO_POSITION) return@watchText
                val b = blocks.getOrNull(p) as? ComboBlock.WarmupBlock ?: return@watchText
                val pace = parsePaceToSecPerKm(text)
                blocks[p] = b.copy(pace = pace)
                onChanged()
            }
        }

        private fun bindPace(block: ComboBlock.PaceBlock, pos: Int) {
            val etDist = itemView.findViewById<EditText>(R.id.etDistance)
            val etPace = itemView.findViewById<EditText>(R.id.etPace)

            etDist.clearWatcher()
            etPace.clearWatcher()

            etDist.setText(block.distanceKm?.let { String.format("%.1f", it) } ?: "")
            etPace.setText(if (block.paceSecPerKm > 0) formatPace(block.paceSecPerKm) else "")

            etDist.watchText { text ->
                val p = adapterPosition; if (p == RecyclerView.NO_POSITION) return@watchText
                val b = blocks.getOrNull(p) as? ComboBlock.PaceBlock ?: return@watchText
                val d = text.replace(',', '.').toDoubleOrNull()
                blocks[p] = b.copy(distanceKm = d)
                onChanged()
            }
            etPace.watchText { text ->
                val p = adapterPosition; if (p == RecyclerView.NO_POSITION) return@watchText
                val b = blocks.getOrNull(p) as? ComboBlock.PaceBlock ?: return@watchText
                val pace = parsePaceToSecPerKm(text)
                if (pace != null && pace > 0) {
                    blocks[p] = b.copy(paceSecPerKm = pace)
                    onChanged()
                }
            }
        }

        private fun bindInterval(block: ComboBlock.IntervalBlock, pos: Int) {
            val etWorkTime = itemView.findViewById<EditText>(R.id.etWorkTime)
            val etWorkPace = itemView.findViewById<EditText>(R.id.etWorkPace)
            val etRestTime = itemView.findViewById<EditText>(R.id.etRestTime)
            val etRestPace = itemView.findViewById<EditText>(R.id.etRestPace)
            val etRepeats = itemView.findViewById<EditText>(R.id.etRepeats)

            etWorkTime.clearWatcher()
            etWorkPace.clearWatcher()
            etRestTime.clearWatcher()
            etRestPace.clearWatcher()
            etRepeats.clearWatcher()

            etWorkTime.setText(if (block.workSec > 0) formatTime(block.workSec) else "")
            etWorkPace.setText(if (block.workPace > 0) formatPace(block.workPace) else "")
            etRestTime.setText(if (block.restSec > 0) formatTime(block.restSec) else "")
            etRestPace.setText(block.restPace?.let { formatPace(it) } ?: "")
            etRepeats.setText(if (block.repeats > 0) block.repeats.toString() else "")

            etWorkTime.watchText { text ->
                val p = adapterPosition; if (p == RecyclerView.NO_POSITION) return@watchText
                val b = blocks.getOrNull(p) as? ComboBlock.IntervalBlock ?: return@watchText
                val sec = parseTimeToSec(text)
                if (sec != null && sec > 0) {
                    blocks[p] = b.copy(workSec = sec)
                    onChanged()
                }
            }
            etWorkPace.watchText { text ->
                val p = adapterPosition; if (p == RecyclerView.NO_POSITION) return@watchText
                val b = blocks.getOrNull(p) as? ComboBlock.IntervalBlock ?: return@watchText
                val pace = parsePaceToSecPerKm(text)
                if (pace != null && pace > 0) {
                    blocks[p] = b.copy(workPace = pace)
                    onChanged()
                }
            }
            etRestTime.watchText { text ->
                val p = adapterPosition; if (p == RecyclerView.NO_POSITION) return@watchText
                val b = blocks.getOrNull(p) as? ComboBlock.IntervalBlock ?: return@watchText
                val sec = parseTimeToSec(text)
                if (sec != null && sec > 0) {
                    blocks[p] = b.copy(restSec = sec)
                    onChanged()
                }
            }
            etRestPace.watchText { text ->
                val p = adapterPosition; if (p == RecyclerView.NO_POSITION) return@watchText
                val b = blocks.getOrNull(p) as? ComboBlock.IntervalBlock ?: return@watchText
                val pace = parsePaceToSecPerKm(text)
                blocks[p] = b.copy(restPace = pace)
                onChanged()
            }
            etRepeats.watchText { text ->
                val p = adapterPosition; if (p == RecyclerView.NO_POSITION) return@watchText
                val b = blocks.getOrNull(p) as? ComboBlock.IntervalBlock ?: return@watchText
                val r = text.toIntOrNull()
                if (r != null && r > 0) {
                    blocks[p] = b.copy(repeats = r)
                    onChanged()
                }
            }
        }

        private fun bindCooldown(block: ComboBlock.CooldownBlock, pos: Int) {
            val etTime = itemView.findViewById<EditText>(R.id.etTime)
            val etPace = itemView.findViewById<EditText>(R.id.etPace)

            etTime.clearWatcher()
            etPace.clearWatcher()

            etTime.setText(if (block.durationSec > 0) formatTime(block.durationSec) else "")
            etPace.setText(block.pace?.let { formatPace(it) } ?: "")

            etTime.watchText { text ->
                val p = adapterPosition; if (p == RecyclerView.NO_POSITION) return@watchText
                val b = blocks.getOrNull(p) as? ComboBlock.CooldownBlock ?: return@watchText
                val sec = parseTimeToSec(text)
                if (sec != null && sec > 0) {
                    blocks[p] = b.copy(durationSec = sec)
                    onChanged()
                }
            }
            etPace.watchText { text ->
                val p = adapterPosition; if (p == RecyclerView.NO_POSITION) return@watchText
                val b = blocks.getOrNull(p) as? ComboBlock.CooldownBlock ?: return@watchText
                val pace = parsePaceToSecPerKm(text)
                blocks[p] = b.copy(pace = pace)
                onChanged()
            }
        }

        // ── Helpers ──

        private val WATCHER_TAG_KEY = R.id.btnDelete // reuse an existing ID as tag key

        private fun EditText.clearWatcher() {
            val old = getTag(WATCHER_TAG_KEY) as? TextWatcher
            if (old != null) {
                removeTextChangedListener(old)
                setTag(WATCHER_TAG_KEY, null)
            }
        }

        private fun EditText.watchText(action: (String) -> Unit) {
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    action(s?.toString()?.trim() ?: "")
                }
            }
            setTag(WATCHER_TAG_KEY, watcher)
            addTextChangedListener(watcher)
        }

        private fun parseTimeToSec(text: String): Int? {
            val parts = text.trim().split(":")
            if (parts.size !in 2..3) return null
            return try {
                val nums = parts.map { it.toInt() }
                if (nums.size == 2) {
                    val m = nums[0]; val s = nums[1]
                    if (m < 0 || s !in 0..59) null else m * 60 + s
                } else {
                    val h = nums[0]; val m = nums[1]; val s = nums[2]
                    if (h < 0 || m !in 0..59 || s !in 0..59) null else h * 3600 + m * 60 + s
                }
            } catch (_: Exception) { null }
        }

        private fun parsePaceToSecPerKm(text: String): Int? {
            val parts = text.trim().split(":")
            if (parts.size != 2) return null
            return try {
                val m = parts[0].toInt(); val s = parts[1].toInt()
                if (m < 0 || s !in 0..59) null else m * 60 + s
            } catch (_: Exception) { null }
        }

        private fun formatTime(sec: Int): String {
            val m = sec / 60; val s = sec % 60
            return "%d:%02d".format(m, s)
        }

        private fun formatPace(secPerKm: Int): String {
            val m = secPerKm / 60; val s = secPerKm % 60
            return "%d:%02d".format(m, s)
        }
    }
}
