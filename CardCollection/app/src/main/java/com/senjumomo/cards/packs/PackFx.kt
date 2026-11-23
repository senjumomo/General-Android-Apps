package com.senjumomo.cards.packs

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Light effects for pack opening, drawn on a plain canvas so they can sit between the views:
 * - [dim]: darkens the screen while you cut the pack;
 * - [burst]: rainbow light rays and sparks pouring out of the torn opening;
 * - [twinkle]: little four-point stars popping around a card as it lands;
 * - [flare]: a bright star flare when a big pull is revealed;
 * - [rainbow]: the background washes into slowly shifting colour (with drifting sparkles) for big pulls.
 *
 * Everything is a function of the clock ([now]), so tests can freeze a frame.
 */
class PackFx @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** The clock (ms). Tests replace it to render a fixed moment. */
    var now: () -> Long = { SystemClock.uptimeMillis() }

    private val density = resources.displayMetrics.density
    private val rnd = Random(7)

    // --- Dim --------------------------------------------------------------------------------------

    private var dimFrom = 0f
    private var dimTo = 0f
    private var dimStart = 0L
    private var dimMs = 1L

    /** Darken the screen to [amount] (0..1) over [ms]. */
    fun dim(amount: Float, ms: Long = 0) {
        dimFrom = dimNow(); dimTo = amount; dimStart = now(); dimMs = max(ms, 1)
        postInvalidateOnAnimation()
    }

    private fun dimNow(): Float {
        val t = ((now() - dimStart) / dimMs.toFloat()).coerceIn(0f, 1f)
        return dimFrom + (dimTo - dimFrom) * t
    }

    // --- Burst ------------------------------------------------------------------------------------

    private class Spark(val angle: Float, val speed: Float, val size: Float, val hue: Float, val delay: Float)

    private var burstStart = -1L
    private var burstX = 0f
    private var burstY = 0f
    private var burstW = 0f
    private var sparks: List<Spark> = emptyList()

    /** Rays and sparks from a torn opening centred at ([x], [y]) that is [width] wide. */
    fun burst(x: Float, y: Float, width: Float) {
        burstStart = now(); burstX = x; burstY = y; burstW = width
        sparks = List(46) {
            Spark(
                angle = (-PI / 2 + (rnd.nextFloat() - 0.5f) * 2.2f).toFloat(),
                speed = (0.45f + rnd.nextFloat() * 0.8f) * width,
                size = (2.5f + rnd.nextFloat() * 5f) * density,
                hue = rnd.nextFloat() * 360f,
                delay = rnd.nextFloat() * 0.25f,
            )
        }
        postInvalidateOnAnimation()
    }

    // --- Twinkles ---------------------------------------------------------------------------------

    private class Star(val x: Float, val y: Float, val size: Float, val start: Long, val color: Int)

    private val stars = mutableListOf<Star>()

    /** A few stars popping in and out around [rect] (in this view's coordinates). */
    fun twinkle(rect: RectF, count: Int = 4, color: Int = Color.WHITE) {
        val t = now()
        repeat(count) { i ->
            // Around the corners and edges, a little outside the card.
            val side = rnd.nextInt(4)
            val along = rnd.nextFloat()
            val out = (6 + rnd.nextFloat() * 12) * density
            val (x, y) = when (side) {
                0 -> rect.left + rect.width() * along to rect.top - out * 0.5f
                1 -> rect.right + out * 0.5f to rect.top + rect.height() * along
                2 -> rect.left + rect.width() * along to rect.bottom + out * 0.5f
                else -> rect.left - out * 0.5f to rect.top + rect.height() * along
            }
            stars += Star(x, y, (10 + rnd.nextFloat() * 14) * density, t + i * 110L, color)
        }
        postInvalidateOnAnimation()
    }

    // --- Flare ------------------------------------------------------------------------------------

    private var flareStart = -1L
    private var flareX = 0f
    private var flareY = 0f
    private var flareColor = Color.WHITE

    fun flare(x: Float, y: Float, color: Int = Color.WHITE) {
        flareStart = now(); flareX = x; flareY = y; flareColor = color
        postInvalidateOnAnimation()
    }

    // --- Rainbow background -------------------------------------------------------------------------

    private var rainbowFrom = 0f
    private var rainbowTo = 0f
    private var rainbowStart = 0L
    private val motes = List(36) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.4f + rnd.nextFloat(), rnd.nextFloat()) }

    /** Fade the colourful background in or out. */
    fun rainbow(on: Boolean) {
        val target = if (on) 1f else 0f
        if (target == rainbowTo) return
        rainbowFrom = rainbowNow(); rainbowTo = target; rainbowStart = now()
        postInvalidateOnAnimation()
    }

    private fun rainbowNow(): Float {
        val t = ((now() - rainbowStart) / RAINBOW_FADE_MS.toFloat()).coerceIn(0f, 1f)
        return rainbowFrom + (rainbowTo - rainbowFrom) * (t * t * (3 - 2 * t))
    }

    // --- Starfield (parallax with the phone's tilt) ----------------------------------------------------

    /** Tilt (-1..1 each axis) for the starfield's parallax; null = no starfield. */
    var starfieldTilt: (() -> Pair<Float, Float>)? = null
    private val field = List(110) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.2f + rnd.nextFloat() * 0.8f, rnd.nextFloat()) }

    // --- Particles: foil flakes, confetti, sparkle rain, shooting stars --------------------------------

    private class Particle(
        val x0: Float, val y0: Float, val vx: Float, val vy: Float, val g: Float, val size: Float,
        val color: Int, val spin: Float, val start: Long, val life: Float, val shape: Int,
    )

    private val particles = mutableListOf<Particle>()

    /** Little foil flakes shed from a tear at ([x], [y]) along a [width]-wide line. */
    fun flakes(x: Float, y: Float, width: Float, count: Int) {
        val t = now()
        repeat(count) {
            particles += Particle(
                x + (rnd.nextFloat() - 0.5f) * width, y, (rnd.nextFloat() - 0.5f) * 220 * density,
                -(60 + rnd.nextFloat() * 220) * density, 520 * density, (3 + rnd.nextFloat() * 4) * density,
                hue(rnd.nextFloat() * 360f, 0.25f, 1f, 1f), 6 + rnd.nextFloat() * 14, t, 0.9f + rnd.nextFloat() * 0.6f, FLAKE,
            )
        }
        postInvalidateOnAnimation()
    }

    /** A burst of spinning foil confetti from ([x], [y]); [gold] for the rarest pulls. */
    fun confetti(x: Float, y: Float, count: Int, gold: Boolean = false, spread: Float = 1f, down: Boolean = false) {
        val t = now()
        repeat(count) {
            val a = ((if (down) PI / 2 else -PI / 2) + (rnd.nextFloat() - 0.5f) * 2.6f * spread).toFloat()
            val speed = (if (down) 60 + rnd.nextFloat() * 260 else 250 + rnd.nextFloat() * 650) * density
            val c = if (gold) hue(38f + rnd.nextFloat() * 18f, 0.35f + rnd.nextFloat() * 0.4f, 1f, 1f)
                else hue(rnd.nextFloat() * 360f, 0.55f, 1f, 1f)
            particles += Particle(x, y, cos(a) * speed, sin(a) * speed, 900 * density, (5 + rnd.nextFloat() * 6) * density,
                c, 5 + rnd.nextFloat() * 16, t + (rnd.nextFloat() * 60).toLong(), 1.6f + rnd.nextFloat() * 0.9f, FLAKE)
        }
        postInvalidateOnAnimation()
    }

    /** Sparkles drifting down the whole screen for [ms]. */
    fun sparkleRain(ms: Long, color: Int, count: Int = 60) {
        val t = now()
        repeat(count) {
            particles += Particle(rnd.nextFloat() * width, -20 * density, (rnd.nextFloat() - 0.5f) * 40 * density,
                (90 + rnd.nextFloat() * 160) * density, 30 * density, (8 + rnd.nextFloat() * 12) * density,
                if (rnd.nextFloat() < 0.5f) Color.WHITE else color, 2 + rnd.nextFloat() * 3,
                t + (rnd.nextFloat() * ms).toLong(), 2.5f + rnd.nextFloat() * 2f, SPARKLE)
        }
        postInvalidateOnAnimation()
    }

    /** A few shooting stars streaking across the top half. */
    fun shootingStars(count: Int, color: Int = Color.WHITE) {
        val t = now()
        repeat(count) { i ->
            val fromLeft = rnd.nextBoolean()
            val speed = (900 + rnd.nextFloat() * 600) * density
            particles += Particle(if (fromLeft) -40 * density else width + 40 * density, rnd.nextFloat() * height * 0.45f,
                if (fromLeft) speed else -speed, speed * 0.35f, 0f, 2.5f * density, color, 0f,
                t + i * 260L + (rnd.nextFloat() * 200).toLong(), 1.1f, SHOOTING)
        }
        postInvalidateOnAnimation()
    }

    // --- Flash and tear glow -------------------------------------------------------------------------

    private var flashStart = -1L
    private var flashMs = 1L

    fun flash(ms: Long = 280) { flashStart = now(); flashMs = ms; postInvalidateOnAnimation() }

    private var tearX = 0f
    private var tearY = 0f
    private var tearW = 0f
    private var tearAmount = 0f

    /** Light leaking out of the cut as it's torn ([progress] 0..1). */
    fun tearGlow(x: Float, y: Float, width: Float, progress: Float) {
        tearX = x; tearY = y; tearW = width; tearAmount = progress
        postInvalidateOnAnimation()
    }

    /** Stop everything at once (new pack). */
    fun clear() {
        particles.clear(); flashStart = -1; tearAmount = 0f
        dimFrom = 0f; dimTo = 0f; burstStart = -1; flareStart = -1; stars.clear()
        rainbowFrom = 0f; rainbowTo = 0f
        invalidate()
    }

    // --- Drawing ----------------------------------------------------------------------------------

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val hsv = FloatArray(3)

    private fun hue(h: Float, s: Float, v: Float, alpha: Float): Int {
        hsv[0] = ((h % 360f) + 360f) % 360f; hsv[1] = s; hsv[2] = v
        return Color.HSVToColor((alpha.coerceIn(0f, 1f) * 255).toInt(), hsv)
    }

    private fun additive(p: Paint) {
        if (Build.VERSION.SDK_INT >= 29) p.blendMode = BlendMode.PLUS
    }

    override fun onDraw(canvas: Canvas) {
        val t = now()
        var animating = false

        starfieldTilt?.let { drawStarfield(canvas, t, it()); animating = true }

        val rb = rainbowNow()
        if (rb > 0f) { drawRainbow(canvas, t, rb); animating = true }
        if (rainbowNow() != rainbowTo) animating = true

        val d = dimNow()
        if (d > 0f) {
            paint.reset(); paint.color = Color.argb((d * 255).toInt(), 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        if (d != dimTo) animating = true

        if (burstStart >= 0) {
            val e = (t - burstStart) / BURST_MS.toFloat()
            if (e < 1f) { drawBurst(canvas, e); animating = true } else burstStart = -1
        }

        stars.removeAll { t - it.start > STAR_MS }
        for (s in stars) {
            val e = (t - s.start) / STAR_MS.toFloat()
            if (e >= 0f) drawStar(canvas, s.x, s.y, s.size * sin(e * PI.toFloat()), e * 90f, s.color, sin(e * PI.toFloat()))
        }
        if (stars.isNotEmpty()) animating = true

        if (flareStart >= 0) {
            val e = (t - flareStart) / FLARE_MS.toFloat()
            if (e < 1f) { drawFlare(canvas, e); animating = true } else flareStart = -1
        }

        if (tearAmount > 0f) drawTearGlow(canvas)

        particles.removeAll { (t - it.start) / 1000f > it.life || it.y0 > height + 200 * density }
        for (p in particles) drawParticle(canvas, p, t)
        if (particles.isNotEmpty()) animating = true

        if (flashStart >= 0) {
            val e = (t - flashStart) / flashMs.toFloat()
            if (e < 1f) {
                paint.reset()
                paint.color = Color.argb(((1f - e) * (1f - e) * 210).toInt(), 255, 250, 240)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                animating = true
            } else flashStart = -1
        }

        if (animating) postInvalidateOnAnimation()
    }

    private fun drawStarfield(canvas: Canvas, t: Long, tilt: Pair<Float, Float>) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = t / 1000f
        paint.reset()
        val shift = 28 * density
        for (f in field) {
            // Deeper stars (small depth) move less: parallax.
            val x = ((f[0] * w + tilt.first * shift * f[2] * 2) % w + w) % w
            val y = ((f[1] * h + tilt.second * shift * f[2] * 2) % h + h) % h
            val tw = 0.55f + 0.45f * sin(s * (1.2f + f[3] * 2.5f) + f[3] * 40f)
            paint.color = Color.argb((tw * (70 + 120 * f[2])).toInt(), 220, 225, 255)
            canvas.drawCircle(x, y, (0.5f + f[2] * 1.1f) * density, paint)
        }
    }

    private fun drawTearGlow(canvas: Canvas) {
        val r = tearW * (0.55f + 0.25f * tearAmount)
        paint.reset(); additive(paint)
        paint.shader = RadialGradient(tearX, tearY, r,
            intArrayOf(Color.argb((tearAmount * 200).toInt(), 255, 245, 220), Color.argb((tearAmount * 90).toInt(), 200, 170, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        canvas.save(); canvas.scale(1f, 0.35f, tearX, tearY)
        canvas.drawCircle(tearX, tearY, r, paint)
        canvas.restore()
        paint.shader = null
    }

    private val rect = RectF()

    private fun drawParticle(canvas: Canvas, p: Particle, now: Long) {
        val tt = (now - p.start) / 1000f
        if (tt < 0f) return
        val k = tt / p.life
        val x = p.x0 + p.vx * tt
        val y = p.y0 + p.vy * tt + 0.5f * p.g * tt * tt
        val fade = if (k < 0.75f) 1f else 1f - (k - 0.75f) / 0.25f
        when (p.shape) {
            FLAKE -> {
                // A spinning foil square: its visible height flips with the spin and it glints face-on.
                val flip = cos(p.spin * tt)
                paint.reset()
                val glint = flip * flip
                paint.color = withAlpha(blend(p.color, Color.WHITE, glint * glint * 0.7f), fade)
                canvas.save()
                canvas.rotate(p.spin * tt * 40f, x, y)
                rect.set(x - p.size / 2, y - p.size * 0.35f * kotlin.math.abs(flip) - 0.5f, x + p.size / 2, y + p.size * 0.35f * kotlin.math.abs(flip) + 0.5f)
                canvas.drawRect(rect, paint)
                canvas.restore()
            }
            SPARKLE -> {
                val tw = 0.5f + 0.5f * sin(tt * p.spin * 6f + p.x0)
                drawStar(canvas, x + sin(tt * 2f + p.x0) * 10 * density, y, p.size * (0.6f + 0.4f * tw), tt * 60f, p.color, fade * (0.5f + 0.5f * tw))
            }
            SHOOTING -> {
                val len = 90 * density
                val dx = p.vx / kotlin.math.hypot(p.vx, p.vy)
                val dy = p.vy / kotlin.math.hypot(p.vx, p.vy)
                paint.reset()
                paint.strokeWidth = p.size
                paint.strokeCap = Paint.Cap.ROUND
                paint.shader = LinearGradient(x, y, x - dx * len, y - dy * len, withAlpha(p.color, fade), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawLine(x, y, x - dx * len, y - dy * len, paint)
                paint.shader = null
                drawStar(canvas, x, y, p.size * 5, 0f, p.color, fade)
            }
        }
    }

    private fun blend(a: Int, b: Int, k: Float): Int {
        val m = k.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * m).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * m).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * m).toInt(),
        )
    }

    private fun drawRainbow(canvas: Canvas, t: Long, amount: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = t / 1000f
        val base = s * 25f
        // A deep, saturated wash so white text on top stays readable.
        paint.reset()
        paint.shader = LinearGradient(
            0f, 0f, w * 0.6f, h,
            intArrayOf(hue(base + 300f, 0.55f, 0.55f, amount), hue(base + 20f, 0.6f, 0.58f, amount),
                hue(base + 200f, 0.55f, 0.52f, amount), hue(base + 270f, 0.6f, 0.45f, amount)),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        // Soft light from the centre.
        paint.shader = RadialGradient(w / 2, h * 0.45f, max(w, h) * 0.6f,
            intArrayOf(Color.argb((amount * 90).toInt(), 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        // Drifting sparkles.
        for (m in motes) {
            val y = ((m[1] - s * 0.04f * m[2]) % 1f + 1f) % 1f
            val x = m[0] + sin(s * 0.8f + m[3] * 6f) * 0.02f
            val tw = 0.5f + 0.5f * sin(s * 3f * m[2] + m[3] * 20f)
            paint.color = Color.argb((amount * tw * 170).toInt(), 255, 255, 255)
            canvas.drawCircle(x * w, y * h, (0.6f + m[2] * 0.9f) * density, paint)
        }
    }

    private fun drawBurst(canvas: Canvas, e: Float) {
        // Rays: grow fast, hold, fade; fan slowly open.
        val grow = min(1f, e / 0.18f)
        val fade = if (e < 0.45f) 1f else 1f - (e - 0.45f) / 0.55f
        val len = burstW * (1.3f + 0.5f * e) * grow
        val n = 11
        paint.reset(); additive(paint)
        for (pass in 0..1) for (i in 0 until n) {
            val f = i / (n - 1f)
            val spread = 1.05f + 0.15f * e
            val a = (-PI / 2 + (f - 0.5f) * 2 * spread).toFloat() + sin(e * 4f + i) * 0.03f
            // A wide faint beam with a narrower brighter one inside reads as soft light.
            val half = (0.05f + 0.03f * ((i * 37) % 5) / 4f) * (if (pass == 0) 1.8f else 0.7f)
            val ox = burstX + (f - 0.5f) * burstW * 0.5f
            val oy = burstY
            val l = len * (0.75f + 0.25f * ((i * 53) % 7) / 6f)
            path.reset()
            path.moveTo(ox, oy)
            path.lineTo(ox + cos(a - half) * l, oy + sin(a - half) * l)
            path.lineTo(ox + cos(a + half) * l, oy + sin(a + half) * l)
            path.close()
            val c = hue(f * 300f + e * 60f, 0.5f, 1f, (if (pass == 0) 0.16f else 0.3f) * fade)
            paint.shader = RadialGradient(ox, oy, l, intArrayOf(c, withAlpha(c, Color.alpha(c) / 255f * 0.5f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(path, paint)
        }
        paint.shader = null
        // Bright light at the opening.
        val glowR = burstW * 0.75f
        paint.shader = RadialGradient(burstX, burstY, glowR,
            intArrayOf(Color.argb((fade * 230).toInt(), 255, 255, 255), Color.argb((fade * 90).toInt(), 255, 240, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        canvas.save(); canvas.scale(1f, 0.45f, burstX, burstY)
        canvas.drawCircle(burstX, burstY, glowR, paint)
        canvas.restore()
        paint.shader = null
        // Sparks fly up and out, slowing and falling a little.
        for (s in sparks) {
            val k = ((e - s.delay) / (1f - s.delay)).coerceIn(0f, 1f)
            if (k <= 0f) continue
            val dist = s.speed * (1f - (1f - k) * (1f - k))
            val x = burstX + cos(s.angle) * dist
            val y = burstY + sin(s.angle) * dist + k * k * burstW * 0.35f
            drawStar(canvas, x, y, s.size * 1.6f * (1f - k * 0.5f), k * 120f, hue(s.hue, 0.25f, 1f, 1f), 1f - k)
        }
    }

    private fun drawFlare(canvas: Canvas, e: Float) {
        val a = if (e < 0.15f) e / 0.15f else 1f - (e - 0.15f) / 0.85f
        val r = (60 + 120 * e) * density
        paint.reset(); additive(paint)
        paint.shader = RadialGradient(flareX, flareY, r,
            intArrayOf(Color.argb((a * 255).toInt(), 255, 255, 255), withAlpha(flareColor, a * 0.5f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(flareX, flareY, r, paint)
        paint.shader = null
        // Long thin streaks.
        drawStar(canvas, flareX, flareY, r * 2.4f, 0f, Color.WHITE, a, waist = 0.035f)
        drawStar(canvas, flareX, flareY, r * 1.1f, 45f, flareColor, a * 0.8f, waist = 0.05f)
    }

    private fun withAlpha(c: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    /** Four-point star (a sparkle) with a soft core. */
    private fun drawStar(
        canvas: Canvas, x: Float, y: Float, size: Float, rotation: Float, color: Int, alpha: Float, waist: Float = 0.14f,
    ) {
        if (size <= 0.5f || alpha <= 0.01f) return
        val r = size / 2
        val w = r * waist
        paint.shader = null
        paint.color = withAlpha(color, alpha)
        canvas.save()
        canvas.rotate(rotation, x, y)
        path.reset()
        path.moveTo(x, y - r); path.lineTo(x + w, y - w); path.lineTo(x + r, y); path.lineTo(x + w, y + w)
        path.lineTo(x, y + r); path.lineTo(x - w, y + w); path.lineTo(x - r, y); path.lineTo(x - w, y - w)
        path.close()
        canvas.drawPath(path, paint)
        canvas.restore()
        paint.color = withAlpha(Color.WHITE, alpha)
        canvas.drawCircle(x, y, max(w * 1.3f, density), paint)
    }

    companion object {
        const val BURST_MS = 1300L
        const val STAR_MS = 650L
        const val FLARE_MS = 800L
        const val RAINBOW_FADE_MS = 500L
        private const val FLAKE = 0
        private const val SPARKLE = 1
        private const val SHOOTING = 2
    }
}
