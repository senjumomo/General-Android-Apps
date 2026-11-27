package com.senjumomo.utilities

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.core.graphics.ColorUtils
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.color.MaterialColors

/**
 * The page header: a deep gradient built from the theme's colour (so it follows the wallpaper
 * with Material You), with soft light and two faint waves for depth. Rounded at the bottom.
 */
class HeroDrawable(context: Context) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val base = MaterialColors.getColor(context, R.attr.heroBase, Color.rgb(11, 92, 173))
    private val stops = intArrayOf(
        shift(base, -14f, 0.07f),
        base,
        shift(base, 20f, -0.08f),
    )

    // Two paints on purpose: a paint's alpha multiplies its shader, so the faint wave colour must
    // never be left on the paint that draws the gradients (it made them near-invisible on redraw).
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val wave1 = Path()
    private val wave2 = Path()
    private val corner = 28f * density

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        clip.reset()
        clip.addRoundRect(
            RectF(bounds), floatArrayOf(0f, 0f, 0f, 0f, corner, corner, corner, corner), Path.Direction.CW
        )
        wave1.reset()
        wave1.moveTo(0f, h * 0.80f)
        wave1.cubicTo(w * 0.25f, h * 0.60f, w * 0.55f, h * 1.00f, w, h * 0.68f)
        wave1.lineTo(w, h); wave1.lineTo(0f, h); wave1.close()
        wave2.reset()
        wave2.moveTo(0f, h * 0.90f)
        wave2.cubicTo(w * 0.30f, h * 0.74f, w * 0.70f, h * 1.02f, w, h * 0.84f)
        wave2.lineTo(w, h); wave2.lineTo(0f, h); wave2.close()
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.save()
        canvas.clipPath(clip)

        paint.shader = LinearGradient(0f, 0f, w, h, stops, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)

        // Light from the top right, a little from the bottom left.
        paint.shader = RadialGradient(
            w * 0.88f, h * 0.06f, w * 0.78f, 0x3DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(
            0f, h * 1.05f, w * 0.7f, 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        wavePaint.color = 0x14FFFFFF
        canvas.drawPath(wave1, wavePaint)
        wavePaint.color = 0x0DFFFFFF
        canvas.drawPath(wave2, wavePaint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    private fun shift(color: Int, hue: Float, lightness: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[0] = (hsl[0] + hue + 360f) % 360f
        hsl[1] = (hsl[1] * 1.05f).coerceAtMost(1f)
        hsl[2] = (hsl[2] + lightness).coerceIn(0.08f, 0.92f)
        return ColorUtils.HSLToColor(hsl)
    }
}

/** An app bar that paints [HeroDrawable] behind the toolbar and the big header content. */
class HeaderAppBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.appBarLayoutStyle,
) : AppBarLayout(context, attrs, defStyleAttr) {
    init {
        background = HeroDrawable(context)
    }
}
