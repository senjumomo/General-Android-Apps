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
}
