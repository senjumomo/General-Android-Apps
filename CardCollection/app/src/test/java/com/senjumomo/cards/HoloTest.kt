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

    /**
     * Pack opening light effects, frozen at moments: the room dimming as you cut, the burst of light
     * from the opening, a big pull landing (flare + sparkles), and the colour-flooded background.
     */
    @Test
    fun packEffects() {
        val ctx = paparazzi.context
        val back = com.senjumomo.cards.packs.CardBack.get()
        val pw = 412
        val ph = 860
        val pink = android.graphics.Color.rgb(255, 120, 220)

        fun panel(at: Long, setup: (com.senjumomo.cards.packs.PackFx, android.graphics.RectF) -> Unit): android.graphics.Bitmap {
            val frame = android.widget.FrameLayout(ctx)
            frame.setBackgroundResource(R.drawable.holo_bg)
            val fx = com.senjumomo.cards.packs.PackFx(ctx)
            var clock = 10_000L
            fx.now = { clock }
            val iv = android.widget.ImageView(ctx).apply { setImageBitmap(back); scaleType = android.widget.ImageView.ScaleType.FIT_XY }
            val cw = (pw * 0.68f).toInt()
            val ch = cw * back.height / back.width
            val top = (ph * 0.22f).toInt()
            frame.addView(fx, android.widget.FrameLayout.LayoutParams(-1, -1))
            frame.addView(iv, android.widget.FrameLayout.LayoutParams(cw, ch).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL; topMargin = top })
            val left = (pw - cw) / 2f
            setup(fx, android.graphics.RectF(left, top.toFloat(), left + cw, top + ch.toFloat()))
            clock = at
            frame.measure(android.view.View.MeasureSpec.makeMeasureSpec(pw, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(ph, android.view.View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, pw, ph)
            val bmp = android.graphics.Bitmap.createBitmap(pw, ph, android.graphics.Bitmap.Config.ARGB_8888)
            frame.draw(android.graphics.Canvas(bmp))
            return bmp
        }

        val frames = listOf(
            panel(10_000) { fx, _ -> fx.dim(0.6f) },                                           // cutting
            panel(10_380) { fx, r -> fx.burst(r.centerX(), r.top + 40f, r.width()) },           // the rip
            panel(10_220) { fx, r ->                                                            // big pull lands
                fx.rainbow(true)
                fx.flare(r.right - r.width() * 0.12f, r.top + r.height() * 0.08f, pink)
                fx.twinkle(r, 7, pink)
            },
            panel(12_000) { fx, _ -> fx.rainbow(true) },                                        // shining
        )
        val all = android.graphics.Bitmap.createBitmap(pw * frames.size, ph, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(all)
        frames.forEachIndexed { i, b -> c.drawBitmap(b, (i * pw).toFloat(), 0f, null) }
        java.io.File("build/holo").mkdirs()
        java.io.FileOutputStream("build/holo/pack-fx.png").use { all.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val view = android.widget.ImageView(ctx).apply { setImageBitmap(all); adjustViewBounds = true }
        paparazzi.snapshot(view)
    }

    /** The foil gleam on pack and best-pull images, frozen at a few points of its sweep. */
    @Test
    fun shine() {
        val ctx = paparazzi.context
        val row = android.widget.LinearLayout(ctx).apply { setBackgroundColor(android.graphics.Color.rgb(234, 221, 255)) }
        // Each view is ~0.09 later in the cycle than its left neighbour (screen position), so step 0.13.
        listOf(0.04f, 0.17f, 0.30f, 0.43f).forEach { phase ->
            val v = com.senjumomo.cards.packs.ShineImageView(ctx)
            v.setImageBitmap(com.senjumomo.cards.packs.CardBack.get())
            v.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            v.shapeAppearanceModel = v.shapeAppearanceModel.withCornerSize(16f)
            // Screen position offsets the phase; (0,0) here, so the clock alone sets it.
            v.clock = { (phase * com.senjumomo.cards.packs.ShineImageView.PERIOD_MS).toLong() + 10 * com.senjumomo.cards.packs.ShineImageView.PERIOD_MS }
            row.addView(v, android.widget.LinearLayout.LayoutParams(260, 380).apply { setMargins(12, 12, 12, 12) })
        }
        paparazzi.snapshot(row)
    }

    /** Pack summary tiles: a new card, one already wishlisted (filled heart), one owned (no heart). */
    @Test
    fun pullTiles() {
        val ctx = paparazzi.context
        val row = android.widget.LinearLayout(ctx).apply { setBackgroundColor(android.graphics.Color.rgb(21, 21, 28)); setPadding(24, 24, 24, 24) }
        listOf(Triple("Ashtraya Mikumari-no-Mikoto", "Super Rare", 0), Triple("Siren Vortex of Atlantis", "Super Rare", 1),
            Triple("Stratiae the Stratasmasher", "Common", 2)).forEach { (name, rarity, state) ->
            val v = android.view.LayoutInflater.from(ctx).inflate(R.layout.item_pull, row, false)
            v.findViewById<android.widget.ImageView>(R.id.image).setImageBitmap(com.senjumomo.cards.packs.CardBack.get())
            v.findViewById<android.widget.TextView>(R.id.name).text = name
            v.findViewById<android.widget.TextView>(R.id.rarity).apply {
                text = rarity; setTextColor(com.senjumomo.cards.packs.PackOpenActivity.tierColor(com.senjumomo.cards.packs.Tier.of(rarity)))
            }
            v.findViewById<android.widget.TextView>(R.id.value).text = "R8.06"
            v.findViewById<android.widget.TextView>(R.id.tag).apply {
                text = if (state == 2) "✓ Owned" else "New"
                backgroundTintList = android.content.res.ColorStateList.valueOf(if (state == 2) android.graphics.Color.rgb(70, 70, 84) else android.graphics.Color.rgb(255, 196, 64))
                setTextColor(if (state == 2) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            }
            v.findViewById<android.widget.ImageButton>(R.id.want).apply {
                visibility = if (state == 2) android.view.View.GONE else android.view.View.VISIBLE
                if (state == 1) {
                    setImageResource(R.drawable.ic_heart)
                    imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(255, 110, 170))
                }
            }
            row.addView(v, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        }
        paparazzi.snapshot(row)
    }

    /** Pack screen's top-right switches: vibration and sound, on and off. */
    @Test
    fun feedbackIcons() {
        val ctx = paparazzi.context
        val row = android.widget.LinearLayout(ctx).apply { setBackgroundColor(android.graphics.Color.rgb(21, 21, 28)); setPadding(24, 24, 24, 24) }
        listOf(R.drawable.ic_vibration, R.drawable.ic_volume_up, R.drawable.ic_vibration_off, R.drawable.ic_volume_off).forEach { icon ->
            row.addView(com.google.android.material.button.MaterialButton(ctx, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                setIconResource(icon)
                iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            })
        }
        paparazzi.snapshot(row)
    }

    /** Set tiles in the Packs tab: a pack image, a wide box image (sits on its blurred copy), no image. */
    @Test
    fun packTiles() {
        val ctx = paparazzi.context
        val row = android.widget.LinearLayout(ctx).apply { setBackgroundColor(android.graphics.Color.rgb(17, 19, 24)); setPadding(24, 24, 24, 24) }
        val pack = com.senjumomo.cards.packs.CardBack.get()
        val box = android.graphics.Bitmap.createBitmap(600, 380, android.graphics.Bitmap.Config.ARGB_8888).also {
            val c = android.graphics.Canvas(it); c.drawColor(android.graphics.Color.rgb(40, 30, 60))
            c.drawBitmap(pack, null, android.graphics.Rect(40, 40, 250, 340), null); c.drawBitmap(pack, null, android.graphics.Rect(330, 40, 540, 340), null)
        }
        listOf(Triple("Stardust Overdrive", pack, 44), Triple("Magnificent Mavens", box, 20), Triple("Quarter Century Bonanza", null, 5)).forEach { (name, img, owned) ->
            val v = android.view.LayoutInflater.from(ctx).inflate(R.layout.item_pack, row, false)
            if (img != null) {
                v.findViewById<android.widget.ImageView>(R.id.packImage).setImageBitmap(img)
                v.findViewById<android.widget.ImageView>(R.id.packBackdrop).setImageBitmap(img)
            } else {
                v.findViewById<android.view.View>(R.id.packImage).visibility = android.view.View.GONE
                v.findViewById<android.view.View>(R.id.packBackdrop).visibility = android.view.View.GONE
                v.findViewById<android.widget.TextView>(R.id.packCodeTile).apply { visibility = android.view.View.VISIBLE; text = "RA03" }
            }
            v.findViewById<android.widget.TextView>(R.id.packName).text = name
            v.findViewById<android.widget.TextView>(R.id.packDate).text = "2009 · 100 cards"
            v.findViewById<android.widget.TextView>(R.id.packOwned).text = "✓ $owned owned"
            v.findViewById<android.view.View>(R.id.packProgressRow).visibility = android.view.View.VISIBLE
            v.findViewById<android.widget.TextView>(R.id.packProgressText).text = "17/100"
            row.addView(v, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        }
        paparazzi.snapshot(row)
    }
}

