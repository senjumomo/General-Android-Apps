package com.senjumomo.cards

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.senjumomo.cards.holo.HoloCardView
import com.senjumomo.cards.holo.HoloStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HoloTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false), theme = "Theme.Cards.Holo")

    /** A made-up card: orange frame, name bar with lettering, colourful artwork, text box. */
    private fun fakeCard(): Bitmap {
        val w = 590; val h = 860
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(Color.rgb(196, 120, 60))
        p.color = Color.rgb(214, 160, 100); c.drawRect(w * 0.06f, h * 0.04f, w * 0.94f, h * 0.11f, p)
        p.color = Color.BLACK; p.textSize = 42f; p.isFakeBoldText = true
        c.drawText("HOLO TEST DRAGON", w * 0.09f, h * 0.093f, p)
        p.shader = LinearGradient(0f, h * 0.18f, w.toFloat(), h * 0.7f,
            intArrayOf(Color.rgb(30, 60, 160), Color.rgb(200, 60, 120), Color.rgb(250, 200, 80)), null, Shader.TileMode.CLAMP)
        c.drawRect(w * 0.119f, h * 0.18f, w * 0.881f, h * 0.705f, p)
        p.shader = null; p.color = Color.WHITE
        for (i in 0 until 12) c.drawCircle(w * (0.2f + 0.05f * i), h * (0.3f + 0.025f * (i % 5)), 18f, p)
        p.color = Color.rgb(235, 225, 200); c.drawRect(w * 0.07f, h * 0.74f, w * 0.93f, h * 0.95f, p)
        return bmp
    }

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

    /** Compiles the real AGSL with Android's shader compiler (layoutlib) - catches syntax errors. */
    @Test
    fun shaderCompiles() {
        val src = HoloCardView.shaderSource(paparazzi.context)
        RuntimeShader(src) // throws with the compiler's message if the AGSL is invalid
    }

    @Test
    fun viewer() {
        val row = LinearLayout(paparazzi.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(12, 12, 16))
            setPadding(24, 48, 24, 48)
        }
        listOf("1st Edition Secret Rare" to (-0.6f to -0.3f), "Ultra Rare" to (0.1f to 0f), "Starlight Rare" to (0.6f to 0.4f)).forEach { (rarity, t) ->
            val v = HoloCardView(paparazzi.context)
            v.setCard(fakeCard(), rarity, isPhoto = false, pendulum = false)
            v.freeze(t.first, t.second)
            row.addView(FrameLayout(paparazzi.context).apply {
                setPadding(16, 16, 16, 16)
                addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            assertTrue("full effect should be active", v.fullEffect)
        }
        paparazzi.snapshot(row)
    }
}
