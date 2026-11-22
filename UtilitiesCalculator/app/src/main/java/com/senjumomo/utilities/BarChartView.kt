package com.senjumomo.utilities

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil
import kotlin.math.min

/**
 * Minimal single-series column chart: one bar per month from a shared baseline, a hairline
 * average reference line, short month labels, and the value of the selected bar on its cap.
 * Tap a bar to select it; the latest month is selected by default.
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Bar(val label: String, val value: Float, val display: String, val fullLabel: String)

    private var bars: List<Bar> = emptyList()
    private var average: Float? = null
    private var averageDisplay: String = ""  // used in the accessibility description
    private var selected = -1

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint().apply {
        strokeWidth = 1f
        color = MaterialColors.getColor(this@BarChartView, com.google.android.material.R.attr.colorOutlineVariant)
    }
    private val avgPaint = Paint().apply {
        strokeWidth = 1f
        color = MaterialColors.getColor(this@BarChartView, com.google.android.material.R.attr.colorOutline)
    }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        color = MaterialColors.getColor(this@BarChartView, com.google.android.material.R.attr.colorOnSurfaceVariant)
    }
    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        color = MaterialColors.getColor(this@BarChartView, com.google.android.material.R.attr.colorOnSurface)
    }
    private val path = Path()
    private val rect = RectF()

    var barColor: Int
        get() = barPaint.color
        set(value) { barPaint.color = value; invalidate() }

    init {
        barColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        isClickable = true
    }

    fun setData(bars: List<Bar>, average: Float?, averageDisplay: String) {
        this.bars = bars
        this.average = average
        this.averageDisplay = averageDisplay
        selected = bars.lastIndex
        updateDescription()
        invalidate()
    }

    private fun updateDescription() {
        val sel = bars.getOrNull(selected)
        contentDescription = buildString {
            append(bars.joinToString { "${it.fullLabel}: ${it.display}" })
            if (average != null) append(". Average $averageDisplay")
            if (sel != null) append(". Selected ${sel.fullLabel}: ${sel.display}")
        }
    }

    // Plot geometry
    private val topPad get() = dp(22f)
    private val bottomPad get() = dp(20f)
    /** Right-hand gutter that holds the "avg" label, so it never collides with bar values. */
    private val gutter get() = if (average == null) 0f else axisText.measureText(AVG) + dp(8f)
    private val plotW get() = width - gutter
    private val slot get() = if (bars.isEmpty()) 0f else plotW / bars.size

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val plotTop = topPad
        val baseline = height - bottomPad
        val plotH = baseline - plotTop
        val max = maxOf(bars.maxOf { it.value }, average ?: 0f).takeIf { it > 0f } ?: 1f
        val barW = min(dp(24f), slot * 0.6f)
        val radius = dp(4f)
        val labelEvery = ceil(bars.size / 8f).toInt().coerceAtLeast(1)

        // Recessive top gridline + baseline
        canvas.drawLine(0f, plotTop, plotW, plotTop, gridPaint)
        canvas.drawLine(0f, baseline, plotW, baseline, gridPaint)

        bars.forEachIndexed { i, bar ->
            val cx = slot * i + slot / 2
            val h = plotH * (bar.value / max)
            barPaint.alpha = if (i == selected) 255 else 145
            if (h > 0f) {
                rect.set(cx - barW / 2, baseline - h, cx + barW / 2, baseline)
                val r = min(radius, h)
                path.reset()
                // Rounded data-end, square at the baseline.
                path.addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
                canvas.drawPath(path, barPaint)
            }
            if (i % labelEvery == 0 || i == bars.lastIndex) {
                axisText.textAlign = Paint.Align.CENTER
                axisText.isFakeBoldText = i == selected
                canvas.drawText(bar.label, cx, height - dp(4f), axisText)
            }
        }
        axisText.isFakeBoldText = false

        // Average reference line (solid hairline), labelled in the gutter. The value itself is
        // in the Average tile below the chart.
        average?.let { avg ->
            val y = baseline - plotH * (avg / max)
            canvas.drawLine(0f, y, plotW + dp(4f), y, avgPaint)
            axisText.textAlign = Paint.Align.RIGHT
            canvas.drawText(AVG, width.toFloat(), y + axisText.textSize / 3, axisText)
        }

        // Value on the selected bar's cap, kept inside the view.
        bars.getOrNull(selected)?.let { bar ->
            val cx = slot * selected + slot / 2
            val h = plotH * (bar.value / max)
            val half = valueText.measureText(bar.display) / 2
            val x = cx.coerceIn(half, plotW - half)
            canvas.drawText(bar.display, x, maxOf(baseline - h - dp(6f), sp(12f)), valueText)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bars.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(event.actionMasked == MotionEvent.ACTION_MOVE)
                val i = (event.x.coerceAtMost(plotW - 1) / slot).toInt().coerceIn(0, bars.lastIndex)
                if (i != selected) {
                    selected = i
                    updateDescription()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val AVG = "avg"
    }
}
