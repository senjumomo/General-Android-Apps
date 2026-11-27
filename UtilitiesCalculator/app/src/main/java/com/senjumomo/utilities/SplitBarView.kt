package com.senjumomo.utilities

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max

/**
 * One rounded bar cut into coloured segments (debt / water / electricity), each as wide as its
 * share of the bill. Segments are separated by small gaps and glide to their new size.
 */
class SplitBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var colors: IntArray = intArrayOf(0xFFFF9DBB.toInt(), 0xFF7DD3FF.toInt(), 0xFFFFCF5C.toInt())
        set(value) { field = value; invalidate() }

    var trackColor: Int = 0x33FFFFFF
        set(value) { field = value; invalidate() }

    private var shown = FloatArray(0)
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setShares(shares: FloatArray, animate: Boolean = true) {
        animator?.cancel()
        val from = if (shown.size == shares.size) shown.copyOf() else FloatArray(shares.size)
        if (!animate || !Motion.enabled(this)) {
            shown = shares.copyOf()
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                val t = it.animatedValue as Float
                shown = FloatArray(shares.size) { i -> from[i] + (shares[i] - from[i]) * t }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val r = h / 2f
        paint.color = trackColor
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, paint)

        val active = shown.indices.filter { shown[it] > 0.0005f }
        if (active.isEmpty()) return
        val gap = 3f * resources.displayMetrics.density
        val available = w - gap * (active.size - 1)
        // A tiny share still shows as a dot, then everything is scaled back to fit.
        val widths = active.map { max(shown[it] * available, h * 0.9f) }
        val scale = available / widths.sum()
        var x = 0f
        active.forEachIndexed { n, i ->
            val segment = widths[n] * scale
            paint.color = colors[i % colors.size]
            rect.set(x, 0f, x + segment, h)
            canvas.drawRoundRect(rect, r, r, paint)
            x += segment + gap
        }
    }
}
