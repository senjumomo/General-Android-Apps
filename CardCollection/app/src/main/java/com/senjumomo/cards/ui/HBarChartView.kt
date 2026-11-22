package com.senjumomo.cards.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as M

/**
 * Single-series horizontal bar chart: one row per category with its name and value on top and a
 * bar (square at the baseline, rounded at the end) underneath. Rows are tappable.
 */
class HBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Row(val label: String, val detail: String, val value: Float, val display: String, val key: String?)

    var onRowClick: ((Row) -> Unit)? = null

    private var rows: List<Row> = emptyList()
    private var pressed = -1

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val rowH get() = dp(46f)
    private val barH get() = dp(10f)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint().apply {
        color = MaterialColors.getColor(this@HBarChartView, M.attr.colorSurfaceContainerHighest)
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
        color = MaterialColors.getColor(this@HBarChartView, M.attr.colorOnSurface)
    }
    private val detailPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        color = MaterialColors.getColor(this@HBarChartView, M.attr.colorOnSurfaceVariant)
    }
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
        isFakeBoldText = true
        textAlign = Paint.Align.RIGHT
        color = MaterialColors.getColor(this@HBarChartView, M.attr.colorOnSurface)
    }
    private val path = Path()
    private val rect = RectF()

    var barColor: Int
        get() = barPaint.color
        set(v) { barPaint.color = v; invalidate() }

    init {
        barColor = MaterialColors.getColor(this, M.attr.colorPrimary)
        isClickable = true
    }

    fun setRows(rows: List<Row>) {
        this.rows = rows
        contentDescription = rows.joinToString { "${it.label}: ${it.display}" }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (rows.size * rowH).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val max = rows.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1f
        val w = width.toFloat()
        rows.forEachIndexed { i, row ->
            val top = i * rowH
            if (i == pressed) canvas.drawRect(0f, top, w, top + rowH, pressPaint)
            val textY = top + dp(18f)

            // Value on the right, then label + detail ellipsized into the remaining space.
            canvas.drawText(row.display, w, textY, valuePaint)
            val room = w - valuePaint.measureText(row.display) - dp(12f)
            val detail = if (row.detail.isEmpty()) "" else "  ${row.detail}"
            val labelRoom = (room - detailPaint.measureText(detail)).coerceAtLeast(room * 0.5f)
            val label = TextUtils.ellipsize(row.label, labelPaint, labelRoom, TextUtils.TruncateAt.END).toString()
            canvas.drawText(label, 0f, textY, labelPaint)
            val detailX = labelPaint.measureText(label)
            val detailText = TextUtils.ellipsize(detail, detailPaint, (room - detailX).coerceAtLeast(0f), TextUtils.TruncateAt.END)
            canvas.drawText(detailText.toString(), detailX, textY, detailPaint)

            val barTop = top + dp(26f)
            val len = (w * row.value / max).coerceAtLeast(if (row.value > 0) dp(4f) else 0f)
            if (len > 0) {
                rect.set(0f, barTop, len, barTop + barH)
                val r = minOf(dp(4f), len)
                path.reset()
                // Square at the baseline (left), rounded at the data end (right).
                path.addRoundRect(rect, floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f), Path.Direction.CW)
                canvas.drawPath(path, barPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val i = (event.y / rowH).toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = if (rows.getOrNull(i)?.key != null) i else -1; invalidate() }
            MotionEvent.ACTION_UP -> {
                if (i == pressed) rows.getOrNull(i)?.let { onRowClick?.invoke(it) }
                pressed = -1; invalidate(); performClick()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
