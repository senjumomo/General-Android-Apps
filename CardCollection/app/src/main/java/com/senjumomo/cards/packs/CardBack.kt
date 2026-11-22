package com.senjumomo.cards.packs

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The back of a card. The real one is the official TCG back from Yugipedia (927 × 1353), downloaded
 * once and cached; [get] is a drawn stand-in used only until it's loaded or when offline.
 */
object CardBack {
    /** Official English TCG card back, full resolution. */
    const val OFFICIAL_URL = "https://img.yugipedia.com/e/e5/Back-EN.png"

    const val WIDTH = 813
    const val HEIGHT = 1185

    @Volatile private var cached: Bitmap? = null

    fun get(): Bitmap = cached ?: synchronized(this) { cached ?: draw().also { cached = it } }

    private fun draw(): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Bronze frame
        p.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.rgb(214, 150, 84), Color.rgb(150, 86, 38), Color.rgb(201, 132, 66), Color.rgb(128, 70, 30)),
            null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)

        // Panel
        val inset = w * 0.045f
        val panel = RectF(inset, inset, w - inset, h - inset)
        p.shader = RadialGradient(w / 2, h / 2, h * 0.62f,
            intArrayOf(Color.rgb(8, 3, 2), Color.rgb(40, 16, 6), Color.rgb(70, 30, 10)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(panel, p)
        p.shader = null

        // Vortex: strands spiralling into the centre, brighter towards the outside.
        c.save()
        c.clipRect(panel)
        val random = Random(7)
        val cx = w / 2
        val cy = h / 2
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
            maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        repeat(170) {
            val start = random.nextFloat() * 6.283f
            val r0 = w * (0.12f + random.nextFloat() * 0.55f)
            val turn = 2.2f + random.nextFloat() * 2.4f
            val path = Path()
            var first = true
            var t = 0f
            while (t <= turn) {
                val r = r0 * exp(-0.32f * t)                       // spirals inwards
                val a = start + t + sin(t * 3f + start) * 0.06f    // slight wobble
                val x = cx + cos(a) * r
                val y = cy + sin(a) * r * 1.38f                    // card-shaped (taller) vortex
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                t += 0.04f
            }
            val bright = 0.35f + random.nextFloat() * 0.65f
            val col = Color.rgb((255 * bright).toInt(), (150 * bright).toInt(), (45 * bright).toInt())
            glow.color = Color.argb(70, Color.red(col), Color.green(col), Color.blue(col))
            glow.strokeWidth = 10f + random.nextFloat() * 14f
            c.drawPath(path, glow)
            line.color = Color.argb((90 + 120 * bright).toInt(), Color.red(col), Color.green(col), Color.blue(col))
            line.strokeWidth = 1.5f + random.nextFloat() * 4f
            c.drawPath(path, line)
        }
        // Dark eye in the middle
        p.shader = RadialGradient(cx, cy, w * 0.26f, intArrayOf(Color.argb(255, 0, 0, 0), Color.argb(200, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.save(); c.scale(1f, 1.38f, cx, cy); c.drawCircle(cx, cy, w * 0.26f, p); c.restore()
        p.shader = null
        c.restore()

        // Bevel on the frame
        val bevel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
        bevel.color = Color.argb(140, 255, 220, 170); c.drawRect(inset - 3, inset - 3, w - inset + 3, h - inset + 3, bevel)
        bevel.color = Color.argb(160, 60, 25, 5); c.drawRect(inset + 1, inset + 1, w - inset - 1, h - inset - 1, bevel)
        return bmp
    }
}
