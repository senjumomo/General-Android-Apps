package com.senjumomo.utilities

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/** A tiny line of the last few months (white, for the History header) ending in a glowing dot. */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var values: List<Float> = emptyList()
    private var progress = 1f
    private var animator: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }

    private val path = Path()
    private val partial = Path()
    private val area = Path()
    private val pos = FloatArray(2)

    fun setData(values: List<Float>) {
        this.values = values
        progress = 1f
        rebuild()
        invalidate()
    }

    /** Draws the line from left to right. */
    fun animateIn() {
        animator?.cancel()
        if (values.size < 2 || !Motion.enabled(this)) { progress = 1f; invalidate(); return }
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            startDelay = 150
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuild()
    }

    private fun points(): List<Pair<Float, Float>> {
        if (values.isEmpty() || width == 0) return emptyList()
        val padX = 8f * density
        val padTop = 10f * density
        val padBottom = 8f * density
        val lo = values.min()
        val hi = values.max()
        val span = hi - lo
        return values.mapIndexed { i, v ->
            val x = if (values.size == 1) width - padX else padX + (width - 2 * padX) * i / (values.size - 1)
            val t = if (span <= 0f) 0.5f else (v - lo) / span
            x to (height - padBottom - (height - padTop - padBottom) * t)
        }
    }

    private fun rebuild() {
        path.reset()
        val pts = points()
        if (pts.isEmpty()) return
        path.moveTo(pts[0].first, pts[0].second)
        for (i in 1 until pts.size) {
            val (x0, y0) = pts[i - 1]
            val (x1, y1) = pts[i]
            val mid = (x0 + x1) / 2f
            path.cubicTo(mid, y0, mid, y1, x1, y1)
        }
        fill.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(), 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val pts = points()
        if (pts.isEmpty()) return
        val last = pts.last()
        if (pts.size == 1) {
            canvas.drawCircle(last.first, last.second, 9f * density, halo)
            canvas.drawCircle(last.first, last.second, 4.5f * density, dot)
            return
        }
        val measure = PathMeasure(path, false)
        val length = measure.length * progress
        partial.reset()
        measure.getSegment(0f, length, partial, true)
        measure.getPosTan(length, pos, null)

        // Soft area under the line, revealed together with it.
        area.reset()
        area.addPath(partial)
        area.lineTo(pos[0], height.toFloat())
        area.lineTo(pts.first().first, height.toFloat())
        area.close()
        canvas.drawPath(area, fill)
        canvas.drawPath(partial, line)

        canvas.drawCircle(pos[0], pos[1], 9f * density, halo)
        canvas.drawCircle(pos[0], pos[1], 4.5f * density, dot)
    }
}
