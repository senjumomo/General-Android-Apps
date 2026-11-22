package com.senjumomo.cards

import android.graphics.RuntimeShader
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.senjumomo.cards.holo.HoloShaders
import com.senjumomo.cards.holo.HoloStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HoloTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false), theme = "Theme.Cards.Holo")

    @Test
    fun rarityMapping() {
        assertEquals(HoloStyle.SECRET, HoloStyle.mode("1st Edition Secret Rare"))
        assertEquals(HoloStyle.PRISMATIC, HoloStyle.mode("1st Edition Prismatic Secret Rare"))
        assertEquals(HoloStyle.ULTIMATE, HoloStyle.mode("1st Edition Prismatic Ultimate Rare"))
        assertEquals(HoloStyle.QUARTER_CENTURY, HoloStyle.mode("Quarter Century Secret Rare"))
        assertEquals(HoloStyle.PLATINUM, HoloStyle.mode("1st Edition Platinum Secret Rare"))
        assertEquals(HoloStyle.STARLIGHT, HoloStyle.mode("1st Edition Starlight Rare"))
        assertEquals(HoloStyle.ULTRA, HoloStyle.mode("Ultra Rare"))
        assertEquals(HoloStyle.SUPER, HoloStyle.mode("1st Edition Super Rare"))
        assertEquals(HoloStyle.RARE, HoloStyle.mode("Rare"))
        assertEquals(HoloStyle.COMMON, HoloStyle.mode("1st Edition Common"))
        assertEquals(HoloStyle.GOLD, HoloStyle.mode("Premium Gold Rare"))
    }

    /** Compiles the shared core with Android's AGSL compiler (layoutlib) - catches syntax errors. */
    @Test
    fun coreCompilesAsAgsl() {
        RuntimeShader(HoloShaders.agsl(HoloShaders.core(paparazzi.context)))
    }

    /**
     * Writes the exact OpenGL ES shaders the app uses to build/holo/ so they can be compiled and
     * rendered in WebGL (tools/holo-preview/gl-check.js).
     */
    @Test
    fun writeGlShaders() {
        val dir = java.io.File("build/holo").apply { mkdirs() }
        java.io.File(dir, "card.vert").writeText(HoloShaders.VERTEX)
        java.io.File(dir, "card.frag").writeText(HoloShaders.fragment(HoloShaders.core(paparazzi.context)))
        java.io.File(dir, "shadow.frag").writeText(HoloShaders.SHADOW)
    }

    /** Renders the generated pack wrapper (also saved for the GL tear preview). */
    @Test
    fun packArt() {
        // Uses real artwork if one was downloaded to build/holo/art.jpg, otherwise a stand-in.
        val real = java.io.File("build/holo/art.jpg").takeIf { it.exists() }
            ?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
        val art = real?.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            ?: android.graphics.Bitmap.createBitmap(624, 624, android.graphics.Bitmap.Config.ARGB_8888)
        if (real == null) drawStandIn(art)
        val bmp = com.senjumomo.cards.packs.PackArt.draw(
            com.senjumomo.cards.packs.CardSet("ROTA", "Rage of the Abyss", 101, "2024-10-10", null), art)
        java.io.File("build/holo").mkdirs()
        java.io.FileOutputStream("build/holo/pack.png").use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val view = android.widget.ImageView(paparazzi.context).apply {
            setImageBitmap(bmp); setBackgroundColor(android.graphics.Color.rgb(20, 20, 26))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; setPadding(80, 80, 80, 80)
        }
        // Only the stand-in is a committed snapshot; real artwork is just written out for gl-pack.js.
        if (real == null) paparazzi.snapshot(view)
    }

    private fun drawStandIn(art: android.graphics.Bitmap) {
        val c = android.graphics.Canvas(art)
        c.drawPaint(android.graphics.Paint().apply {
            shader = android.graphics.RadialGradient(312f, 280f, 420f,
                intArrayOf(android.graphics.Color.rgb(250, 210, 255), android.graphics.Color.rgb(140, 60, 200), android.graphics.Color.rgb(30, 20, 70)),
                null, android.graphics.Shader.TileMode.CLAMP)
        })
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(255, 240, 200) }
        for (i in 0 until 9) c.drawCircle(120f + i * 48f, 250f + (i % 3) * 60f, 26f, p)
    }

    @Test
    fun cardBack() {
        val view = android.widget.ImageView(paparazzi.context).apply {
            setImageBitmap(com.senjumomo.cards.packs.CardBack.get())
            setBackgroundColor(android.graphics.Color.rgb(20, 20, 26))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; setPadding(60, 60, 60, 60)
        }
        paparazzi.snapshot(view)
    }
}
