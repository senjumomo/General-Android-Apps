package com.senjumomo.cards.packs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Draws a booster pack wrapper: crimped foil ends, the set's featured artwork, and the set name.
 * The app's own design (sharp at any size); it's then shown with the holo foil shader.
 */
object PackArt {
    const val WIDTH = 640
    const val HEIGHT = 1110

    /** Fraction of the height where the pack tears open (just below the top crimp). */
    const val TEAR_Y = 0.115f

    fun draw(set: CardSet, artwork: Bitmap?): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        val accent = artwork?.let(::averageColor) ?: Color.rgb(70, 50, 120)
        val dark = blend(accent, Color.BLACK, 0.72f)
        val mid = blend(accent, Color.BLACK, 0.35f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Body
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(mid, dark, blend(accent, Color.BLACK, 0.85f)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null

        // Featured artwork with soft edges fading into the wrapper.
        val artTop = h * 0.14f
        val artSize = w * 0.98f
        artwork?.let { art ->
            val dst = RectF((w - artSize) / 2, artTop, (w + artSize) / 2, artTop + artSize)
            c.drawBitmap(art, Rect(0, 0, art.width, art.height), dst, p)
            p.shader = LinearGradient(0f, dst.bottom - artSize * 0.35f, 0f, dst.bottom, Color.TRANSPARENT, dark, Shader.TileMode.CLAMP)
            c.drawRect(dst.left, dst.bottom - artSize * 0.35f, dst.right, dst.bottom + 2, p)
            p.shader = LinearGradient(0f, dst.top, 0f, dst.top + artSize * 0.12f, mid, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(dst.left, dst.top - 1, dst.right, dst.top + artSize * 0.12f, p)
            p.shader = null
        }

        // Set name
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 74f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setShadowLayer(14f, 0f, 4f, Color.argb(220, 0, 0, 0))
        }
        val layout = StaticLayout.Builder.obtain(set.name.uppercase(), 0, set.name.length, title, (w * 0.86f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build()
        var size = 74f
        var l = layout
        while (l.lineCount > 2 && size > 44f) {
            size -= 4f; title.textSize = size
            l = StaticLayout.Builder.obtain(set.name.uppercase(), 0, set.name.length, title, (w * 0.86f).toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build()
        }
        c.save()
        c.translate(w * 0.07f, h * 0.70f - l.height / 2f)
        l.draw(c)
        c.restore()

        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 255); textSize = 30f; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); letterSpacing = 0.12f
        }
        c.drawText(set.code + "  ·  BOOSTER PACK", w / 2, h * 0.81f, small)
        small.textSize = 24f; small.color = Color.argb(180, 255, 255, 255)
        c.drawText("9 CARDS PER PACK", w / 2, h * 0.845f, small)

        // Crimped foil ends (top and bottom).
        crimp(c, 0f, h * 0.085f, w, top = true)
        crimp(c, h - h * 0.07f, h, w, top = false)
        return bmp
    }

    private fun crimp(c: Canvas, y0: Float, y1: Float, w: Float, top: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, y0, w, y1,
            intArrayOf(Color.rgb(170, 170, 180), Color.rgb(245, 245, 250), Color.rgb(150, 150, 160), Color.rgb(230, 230, 238)),
            null, Shader.TileMode.MIRROR)
        c.drawRect(0f, y0, w, y1, p)
        // Ridges
        p.shader = null
        p.color = Color.argb(60, 0, 0, 0)
        var x = 0f
        while (x < w) { c.drawRect(x, y0, x + 3f, y1, p); x += 9f }
        // Serrated outer edge
        val path = Path()
        val tooth = 16f
        val edge = if (top) y0 else y1
        val dir = if (top) 1f else -1f
        path.moveTo(0f, edge)
        x = 0f
        while (x <= w) { path.lineTo(x + tooth / 2, edge + dir * 9f); path.lineTo(x + tooth, edge); x += tooth }
        path.lineTo(w, edge - dir * 2f); path.lineTo(0f, edge - dir * 2f); path.close()
        p.color = Color.TRANSPARENT
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        c.drawPath(path, p)
        p.xfermode = null
        // Inner seam line
        p.color = Color.argb(90, 0, 0, 0)
        val seam = if (top) y1 else y0
        c.drawRect(0f, seam - 2f, w, seam + 2f, p)
    }

    private fun averageColor(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 8, 8, true)
        var r = 0; var g = 0; var bl = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val px = s.getPixel(x, y); r += Color.red(px); g += Color.green(px); bl += Color.blue(px)
        }
        // Boost saturation a bit so the wrapper isn't muddy.
        val hsv = FloatArray(3)
        Color.RGBToHSV(r / 64, g / 64, bl / 64, hsv)
        hsv[1] = (hsv[1] * 1.4f).coerceAtMost(0.9f)
        hsv[2] = hsv[2].coerceIn(0.35f, 0.8f)
        return Color.HSVToColor(hsv)
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    /** The card whose artwork goes on the pack: the rarest, most valuable one. */
    fun featured(cards: List<SetCard>): SetCard? =
        cards.filter { !it.type.contains("Token", true) }
            .maxWithOrNull(compareBy<SetCard>({ it.tier.ordinal }, { it.priceUsd ?: 0.0 }))

    fun croppedArtUrl(artworkId: Long) = "https://images.ygoprodeck.com/images/cards_cropped/$artworkId.jpg"
    fun cardUrl(artworkId: Long) = "https://images.ygoprodeck.com/images/cards/$artworkId.jpg"
    fun cardSmallUrl(artworkId: Long) = "https://images.ygoprodeck.com/images/cards_small/$artworkId.jpg"
}
