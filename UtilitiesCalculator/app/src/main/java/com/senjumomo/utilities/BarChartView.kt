package com.senjumomo.utilities

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil
import kotlin.math.min

/**
 * Single-series column chart: one soft gradient bar per month from a shared baseline, a dashed
 * average line labelled in a gutter, short month labels, and a small bubble with the value of the
 * selected bar. Tap or drag across the bars to select; the latest month is selected by default.
 * [animateIn] makes the bars grow one after another.
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

    /** 0..(1 + STAGGER * (n - 1)); anything past the end means fully grown. */
    private var intro = Float.MAX_VALUE
    private var introAnimator: ValueAnimator? = null
    private val overshoot = OvershootInterpolator(1.0f)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint().apply {
        strokeWidth = 1f
        color = MaterialColors.getColor(this@BarChartView, com.google.android.material.R.attr.colorOutlineVariant)
    }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.2f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
        color = MaterialColors.getColor(this@BarChartView, com.google.android.material.R.attr.colorOutline)
    }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.manrope_600)
        color = MaterialColors.getColor(this@BarChartView, com.google.android.material.R.attr.colorOnSurfaceVariant)
    }
    private val axisTextSelected = Paint(axisText).apply {
        typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.manrope_800)
        color = MaterialColors.getColor(this@BarChartView, com.google.android.material.R.attr.colorOnSurface)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12.5f)
        typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.manrope_800)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val rect = RectF()

    var barColor: Int = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        set(value) {
            field = value
            valueText.color = if (ColorUtils.calculateLuminance(value) > 0.45) 0xFF14181D.toInt() else 0xFFFFFFFF.toInt()
            invalidate()
        }

    init {
        barColor = barColor
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

    /** Grows the bars from the baseline, left to right. */
    fun animateIn() {
        introAnimator?.cancel()
        if (bars.isEmpty() || !Motion.enabled(this)) {
            intro = Float.MAX_VALUE
            invalidate()
            return
        }
        val total = 1f + STAGGER * (bars.size - 1)
        intro = 0f
        introAnimator = ValueAnimator.ofFloat(0f, total).apply {
            duration = 560L + 50L * bars.size
            startDelay = 120
            interpolator = LinearInterpolator()
            addUpdateListener { intro = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun grown(i: Int): Float {
        if (intro == Float.MAX_VALUE) return 1f
        return overshoot.getInterpolation((intro - i * STAGGER).coerceIn(0f, 1f))
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
    private val topPad get() = dp(42f)
    private val bottomPad get() = dp(22f)
    /** Right-hand gutter that holds the "avg" label, so it never collides with the bars. */
    private val gutter get() = if (average == null) 0f else axisText.measureText(AVG) + dp(10f)
    private val plotW get() = width - gutter
    private val slot get() = if (bars.isEmpty()) 0f else plotW / bars.size

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val plotTop = topPad
        val baseline = height - bottomPad
        val plotH = baseline - plotTop
        val max = maxOf(bars.maxOf { it.value }, average ?: 0f).takeIf { it > 0f } ?: 1f
        val barW = min(dp(30f), slot * 0.62f)
        val radius = dp(8f)
        val labelEvery = ceil(bars.size / 8f).toInt().coerceAtLeast(1)

        canvas.drawLine(0f, baseline, plotW, baseline, gridPaint)

        bars.forEachIndexed { i, bar ->
            val cx = slot * i + slot / 2
            val h = plotH * (bar.value / max) * grown(i)
            if (h > 0.5f) {
                val top = baseline - h
                val isSel = i == selected
                // Selected bar is vivid; the rest are soft tints so the eye lands on one month.
                val topColor = ColorUtils.setAlphaComponent(barColor, if (isSel) 255 else 92)
                val bottomColor = ColorUtils.setAlphaComponent(barColor, if (isSel) 165 else 36)
                barPaint.shader = LinearGradient(0f, top, 0f, baseline, topColor, bottomColor, Shader.TileMode.CLAMP)
                rect.set(cx - barW / 2, top, cx + barW / 2, baseline)
                val r = min(radius, h)
                path.reset()
                // Rounded data-end, square at the baseline.
                path.addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
                canvas.drawPath(path, barPaint)
            }
            if (i % labelEvery == 0 || i == bars.lastIndex) {
                val paint = if (i == selected) axisTextSelected else axisText
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(bar.label, cx, height - dp(5f), paint)
            }
        }
        barPaint.shader = null

        // Average reference line (dashed), labelled in the gutter. The value itself is in the
        // Average tile below the chart.
        average?.let { avg ->
            val y = baseline - plotH * (avg / max)
            path.reset()
            path.moveTo(0f, y)
            path.lineTo(plotW + dp(4f), y)
            canvas.drawPath(path, avgPaint)
            axisText.textAlign = Paint.Align.RIGHT
            canvas.drawText(AVG, width.toFloat(), y + axisText.textSize / 3, axisText)
        }

        // Value bubble above the selected bar, kept inside the view.
        bars.getOrNull(selected)?.let { bar ->
            val cx = slot * selected + slot / 2
            val barTop = baseline - plotH * (bar.value / max) * grown(selected)
            val textW = valueText.measureText(bar.display)
            val bw = textW + dp(20f)
            val bh = dp(28f)
            val caret = dp(5f)
            val left = (cx - bw / 2).coerceIn(0f, plotW - bw)
            val bottom = (barTop - dp(7f) - caret).coerceAtLeast(bh)
            rect.set(left, bottom - bh, left + bw, bottom)
            bubblePaint.color = barColor
            canvas.drawRoundRect(rect, bh / 2, bh / 2, bubblePaint)
            val caretX = cx.coerceIn(left + bh / 2, left + bw - bh / 2)
            path.reset()
            path.moveTo(caretX - caret, bottom - 1f)
            path.lineTo(caretX + caret, bottom - 1f)
            path.lineTo(caretX, bottom + caret)
            path.close()
            canvas.drawPath(path, bubblePaint)
            canvas.drawText(bar.display, left + bw / 2, rect.centerY() + valueText.textSize / 3, valueText)
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
                    Motion.tick(this)
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
        const val STAGGER = 0.07f
    }
}
