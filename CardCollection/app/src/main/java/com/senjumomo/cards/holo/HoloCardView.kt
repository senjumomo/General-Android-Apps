package com.senjumomo.cards.holo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.RequiresApi
import com.senjumomo.cards.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A card that tilts in 3D and catches the light. On Android 13+ every pixel is shaded on the GPU by
 * res/raw/holo_core.agsl (foil patterns per rarity); older versions get a simpler sheen.
 * Tilt comes from [externalTilt] (the gyroscope) plus finger drags, which spring back on release.
 */
class HoloCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Supplies the gyroscope tilt each frame (x, y in -1..1), or null for a gentle idle sway. */
    var externalTilt: (() -> Pair<Float, Float>)? = null

    private var bitmap: Bitmap? = null
    private val src = Rect()
    private var mode = HoloStyle.COMMON
    private var photo = 1f
    private var art = HoloStyle.artRect(false)

    private var running = false
    private val start = SystemClock.uptimeMillis()

    // Finger drag adds to the sensor tilt and springs back when released.
    private var dragX = 0f
    private var dragY = 0f
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var startDragX = 0f
    private var startDragY = 0f

    /** Current tilt actually shown (after smoothing), for tests and accessibility. */
    var tiltX = 0f; private set
    var tiltY = 0f; private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val dst = RectF()
    private var runtime: Any? = null // RuntimeShader, kept untyped so older Androids can load this class

    init {
        cameraDistance = 14000 * resources.displayMetrics.density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.035f)
            }
        }
        clipToOutline = true
        elevation = 18 * resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= 33) runtime = createShader(context)
    }

    /** True when the full per-pixel effect is available (Android 13+ and the shader compiled). */
    val fullEffect get() = runtime != null

    fun setCard(bitmap: Bitmap, rarity: String, isPhoto: Boolean, pendulum: Boolean) {
        this.bitmap = bitmap
        mode = HoloStyle.mode(rarity)
        photo = if (isPhoto) 1f else 0f
        art = HoloStyle.artRect(pendulum)
        // Centre-crop to card proportions (59 × 86).
        val w = bitmap.width
        val h = bitmap.height
        val target = 59f / 86f
        if (w.toFloat() / h > target) {
            val cw = (h * target).toInt()
            src.set((w - cw) / 2, 0, (w - cw) / 2 + cw, h)
        } else {
            val ch = (w / target).toInt()
            src.set(0, (h - ch) / 2, w, (h - ch) / 2 + ch)
        }
        contentDescription = "Card with ${HoloStyle.label(mode).lowercase()}. Tilt your phone or drag to see the foil."
        invalidate()
    }

    fun start() { running = true; postInvalidateOnAnimation() }
    fun stop() { running = false }

    /** For screenshots/tests: freeze at a given tilt. */
    fun freeze(x: Float, y: Float) {
        running = false
        externalTilt = { x to y }
        dragX = 0f; dragY = 0f
        tiltX = x; tiltY = y
        applyRotation()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Largest card (59 × 86) that fits.
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        var w = maxW
        var h = (w * 86f / 59f).toInt()
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && h > maxH) {
            h = maxH
            w = (h * 59f / 86f).toInt()
        }
        setMeasuredDimension(w, h)
    }

    private fun updateTilt() {
        val (sx, sy) = externalTilt?.invoke() ?: run {
            val t = (SystemClock.uptimeMillis() - start) / 1000f
            (sin(t * 0.7f) * 0.35f) to (cos(t * 0.5f) * 0.2f)
        }
        if (!dragging) {
            dragX *= 0.86f
            dragY *= 0.86f
            if (abs(dragX) < 0.001f) dragX = 0f
            if (abs(dragY) < 0.001f) dragY = 0f
        }
        val targetX = (sx + dragX).coerceIn(-1f, 1f)
        val targetY = (sy + dragY).coerceIn(-1f, 1f)
        tiltX += (targetX - tiltX) * 0.35f
        tiltY += (targetY - tiltY) * 0.35f
        applyRotation()
    }

    private fun applyRotation() {
        rotationY = tiltX * 16f
        rotationX = -tiltY * 16f
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        if (running) updateTilt()
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        matrix.setRectToRect(RectF(src), dst, Matrix.ScaleToFit.FILL)

        val shader = runtime
        if (Build.VERSION.SDK_INT >= 33 && shader != null) {
            drawFull(canvas, bmp, shader as RuntimeShader)
        } else {
            drawFallback(canvas, bmp)
        }
        if (running) postInvalidateOnAnimation()
    }

    @RequiresApi(33)
    private fun drawFull(canvas: Canvas, bmp: Bitmap, shader: RuntimeShader) {
        val image = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        shader.setInputShader("image", image)
        shader.setFloatUniform("cardSize", width.toFloat(), height.toFloat())
        shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
        shader.setFloatUniform("texel", 1f / src.width(), 1f / src.height())
        shader.setFloatUniform("tilt", tiltX, tiltY)
        shader.setFloatUniform("time", (SystemClock.uptimeMillis() - start) / 1000f)
        shader.setFloatUniform("mode", mode.toFloat())
        shader.setFloatUniform("artRect", art[0], art[1], art[2], art[3])
        shader.setFloatUniform("photo", photo)
        paint.shader = shader
        canvas.drawRect(dst, paint)
    }

    // --- Fallback for Android 12 and older: bitmap + moving rainbow sheen + glare ----------------

    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawFallback(canvas: Canvas, bmp: Bitmap) {
        paint.shader = null
        canvas.drawBitmap(bmp, src, dst, paint)
        val w = width.toFloat()
        val h = height.toFloat()
        if (mode != HoloStyle.COMMON && mode != HoloStyle.RARE) {
            val wholeCard = mode == HoloStyle.STARLIGHT || mode == HoloStyle.COLLECTORS || mode == HoloStyle.PARALLEL
            val area = if (wholeCard) RectF(0f, 0f, w, h) else RectF(art[0] * w, art[1] * h, art[2] * w, art[3] * h)
            val shift = (tiltX * 0.6f + tiltY * 0.4f) * w
            fallbackPaint.shader = LinearGradient(
                area.left + shift - w * 0.5f, area.top, area.right + shift + w * 0.5f, area.bottom,
                intArrayOf(0x00FF0080, 0x55FF4FD8, 0x5539A0FF, 0x5550FFB0, 0x55FFF35C, 0x00FF8040),
                null, Shader.TileMode.MIRROR,
            )
            fallbackPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            canvas.drawRect(area, fallbackPaint)
        }
        val gx = w * (0.5f + tiltX * 0.85f)
        val gy = h * (0.45f + tiltY * 0.75f)
        fallbackPaint.shader = RadialGradient(gx, gy, w * 0.7f, 0x40FFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        fallbackPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        canvas.drawRect(dst, fallbackPaint)
        fallbackPaint.xfermode = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                downX = event.x; downY = event.y
                startDragX = dragX; startDragY = dragY
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                dragX = (startDragX + (event.x - downX) / (width * 0.5f)).coerceIn(-1.2f, 1.2f)
                dragY = (startDragY + (event.y - downY) / (height * 0.5f)).coerceIn(-1.2f, 1.2f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    abs(event.x - downX) < 10 && abs(event.y - downY) < 10) performClick()
            }
        }
        if (!running) invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        private var source: String? = null

        /** The app's shader: platform prefix + the shared core + entry point. */
        fun shaderSource(context: Context): String = source ?: run {
            val core = context.resources.openRawResource(R.raw.holo_core).bufferedReader().use { it.readText() }
            """
            uniform shader image;
            uniform float2 cardSize;
            float4 sampleCard(float2 uv) { return float4(image.eval(clamp(uv, 0.0, 1.0) * cardSize)); }
            $core
            half4 main(float2 fragCoord) { return half4(shade(fragCoord / resolution)); }
            """.trimIndent().also { source = it }
        }

        @RequiresApi(33)
        private fun createShader(context: Context): RuntimeShader? = try {
            RuntimeShader(shaderSource(context))
        } catch (e: Exception) {
            Log.e("HoloCardView", "Holo shader failed to compile; using the simple sheen", e)
            null
        }
    }
}
