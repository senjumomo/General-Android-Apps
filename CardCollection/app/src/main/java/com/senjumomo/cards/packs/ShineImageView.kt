package com.senjumomo.cards.packs

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import com.google.android.material.imageview.ShapeableImageView

/**
 * An image with a foil gleam: every few seconds a soft band of light (faintly rainbow at its edges)
 * sweeps across it. Each view's timing depends on where it is on screen, so on a shelf of packs the
 * light ripples across them, and moves as you scroll, like light catching foil.
 */
class ShineImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ShapeableImageView(context, attrs) {

    /** Fixed clock for tests (ms); null = real time. */
    var clock: (() -> Long)? = null
    var shine = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val clip = Path()
    private val loc = IntArray(2)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = drawable ?: return
        if (!shine || (clock == null && !ValueAnimator.areAnimatorsEnabled())) return
        val now = clock?.invoke() ?: SystemClock.uptimeMillis()

        // Where the image is actually drawn.
        bounds.set(0f, 0f, d.intrinsicWidth.toFloat().coerceAtLeast(1f), d.intrinsicHeight.toFloat().coerceAtLeast(1f))
        if (d.intrinsicWidth <= 0) bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        else imageMatrix.mapRect(bounds)
        bounds.offset(paddingLeft.toFloat(), paddingTop.toFloat())
        if (!bounds.intersect(0f, 0f, width.toFloat(), height.toFloat())) return

        getLocationOnScreen(loc)
        val screen = resources.displayMetrics
        val offset = (loc[0] / screen.widthPixels.toFloat()) * 0.35f + (loc[1] / screen.heightPixels.toFloat()) * 0.25f
        val phase = ((now / PERIOD_MS.toFloat() - offset) % 1f + 1f) % 1f
        if (phase < SWEEP) {
            val p = phase / SWEEP                       // 0..1 across the image
            val w = bounds.width()
            val h = bounds.height()
            val cx = bounds.left + (-0.4f + 1.8f * p) * w
            val cy = bounds.top + (-0.4f + 1.8f * p) * h * 0.6f
            val band = (w + h) * 0.17f
            paint.shader = LinearGradient(
                cx - band, cy - band * 0.6f, cx + band, cy + band * 0.6f,
                intArrayOf(Color.TRANSPARENT, 0x3366E0FF, 0x70FFFFFF, 0x33FF88E0, Color.TRANSPARENT),
                floatArrayOf(0f, 0.32f, 0.5f, 0.68f, 1f), Shader.TileMode.CLAMP,
            )
            val r = shapeAppearanceModel.topLeftCornerSize.getCornerSize(bounds)
            clip.reset()
            clip.addRoundRect(bounds, r, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(bounds, paint)
            canvas.restore()
            if (clock == null) postInvalidateOnAnimation()
        } else if (clock == null) {
            // Sleep until the next sweep (re-checked now and then, since scrolling shifts it).
            val untilNext = ((1f - phase) * PERIOD_MS).toLong()
            postInvalidateDelayed(untilNext.coerceAtMost(400))
        }
    }

    companion object {
        const val PERIOD_MS = 4200L
        const val SWEEP = 0.22f
    }
}
