package com.example.stayer.history

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Компактный график темпа для карточки истории тренировки.
 * Рисует одну линию темпа по последовательности участков без внешних зависимостей.
 *
 * Compact pace chart for the workout history card.
 * Draws a single pace line across ordered segments without external dependencies.
 */
class WorkoutPaceChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D7D0E8")
        strokeWidth = dp(1.2f)
        style = Paint.Style.STROKE
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5B8DEF")
        strokeWidth = dp(2.4f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5B8DEF")
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6A647A")
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
    }

    private val pacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6A647A")
        textSize = sp(10f)
        textAlign = Paint.Align.LEFT
    }

    private var points: List<WorkoutPaceChartPoint> = emptyList()

    fun setPoints(value: List<WorkoutPaceChartPoint>) {
        points = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return

        val left = paddingLeft + dp(34f)
        val right = width - paddingRight - dp(8f)
        val top = paddingTop + dp(10f)
        val bottom = height - paddingBottom - dp(24f)
        if (right <= left || bottom <= top) return

        val paces = points.map { it.paceSecPerKm }
        val minPace = paces.minOrNull() ?: return
        val maxPace = paces.maxOrNull() ?: return
        val rawRange = max(1, maxPace - minPace)
        val visualRange = max(120, (rawRange * 2.0f).toInt())
        val center = (minPace + maxPace) / 2
        var chartMin = center - visualRange / 2
        var chartMax = center + visualRange / 2
        if (chartMin < 1) {
            chartMax += 1 - chartMin
            chartMin = 1
        }

        drawAxes(canvas, left, top, right, bottom)
        drawGuideLabels(canvas, chartMin, chartMax, left, top, bottom)
        drawPaceLine(canvas, left, top, right, bottom, chartMin, chartMax)
        drawPointLabels(canvas, left, right, bottom)
    }

    /**
     * Рисует оси графика и нижнюю базовую линию.
     * Draws chart axes and the bottom baseline.
     */
    private fun drawAxes(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        canvas.drawLine(left, top, left, bottom, axisPaint)
        canvas.drawLine(left, bottom, right, bottom, axisPaint)
    }

    /**
     * Рисует подписи шкалы темпа слева.
     * Draws left-side pace scale labels.
     */
    private fun drawGuideLabels(
        canvas: Canvas,
        chartMin: Int,
        chartMax: Int,
        left: Float,
        top: Float,
        bottom: Float
    ) {
        val topLabelY = top + pacePaint.textSize
        val bottomLabelY = bottom - dp(4f)
        canvas.drawText(formatPace(chartMin), dp(4f), topLabelY, pacePaint)
        canvas.drawText(formatPace(chartMax), dp(4f), bottomLabelY, pacePaint)
    }

    /**
     * Рисует линию темпа и маркеры точек.
     * Draws the pace line and point markers.
     */
    private fun drawPaceLine(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        chartMin: Int,
        chartMax: Int
    ) {
        val pointCount = points.size
        val stepX = if (pointCount == 1) 0f else (right - left) / (pointCount - 1)
        var previousX: Float? = null
        var previousY: Float? = null

        points.forEachIndexed { index, point ->
            val x = if (pointCount == 1) (left + right) / 2f else left + stepX * index
            val ratio = (point.paceSecPerKm - chartMin).toFloat() / (chartMax - chartMin).toFloat()
            val y = top + (bottom - top) * ratio
            previousX?.let { canvas.drawLine(it, previousY ?: y, x, y, linePaint) }
            canvas.drawCircle(x, y, dp(3.4f), pointPaint)
            previousX = x
            previousY = y
        }
    }

    /**
     * Рисует подписи участков под графиком.
     * Draws segment labels under the chart.
     */
    private fun drawPointLabels(canvas: Canvas, left: Float, right: Float, bottom: Float) {
        val pointCount = points.size
        if (pointCount == 0) return
        val stepX = if (pointCount == 1) 0f else (right - left) / (pointCount - 1)
        val labelY = bottom + dp(16f)

        points.forEachIndexed { index, point ->
            val x = if (pointCount == 1) (left + right) / 2f else left + stepX * index
            canvas.drawText(point.label, x, labelY, labelPaint)
        }
    }

    /**
     * Форматирует темп в минутах и секундах на километр.
     * Formats pace as minutes and seconds per kilometer.
     */
    private fun formatPace(value: Int): String {
        val safe = max(0, value)
        val minutes = safe / 60
        val seconds = safe % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    /**
     * Переводит dp в пиксели.
     * Converts dp to pixels.
     */
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /**
     * Переводит sp в пиксели.
     * Converts sp to pixels.
     */
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
