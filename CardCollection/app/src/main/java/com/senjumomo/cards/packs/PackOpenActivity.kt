package com.senjumomo.cards.packs

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.senjumomo.cards.R
import com.senjumomo.cards.data.Card
import com.senjumomo.cards.data.CardDatabase
import com.senjumomo.cards.data.CardLookup
import com.senjumomo.cards.data.Dates
import com.senjumomo.cards.holo.HoloGLView
import com.senjumomo.cards.holo.HoloViewerActivity
import com.senjumomo.cards.holo.TiltTracker
import com.senjumomo.cards.net.Exchange
import com.senjumomo.cards.ui.Money
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * Pack opening: a 3D foil pack you tear open, then the cards one at a time (the big pull last, with
 * a glow that hints at its rarity), then a summary of what you pulled and what it's worth.
 * It's a simulator: nothing is added to the collection unless you choose to.
 */
class PackOpenActivity : AppCompatActivity() {

    private val repo by lazy { PackRepository(this) }
    private val dao by lazy { CardDatabase.get(this).cards() }
    private lateinit var haptics: Haptics
    private lateinit var tilt: TiltTracker
    private lateinit var set: CardSet

    private lateinit var packView: PackGLView
    private lateinit var holo: HoloGLView
    private lateinit var current: ImageView
    private lateinit var nextBack: ImageView
    private lateinit var glow: View

    private var setCards: List<SetCard> = emptyList()
    private var packBitmap: Bitmap? = null
    private var pack: List<SetCard> = emptyList()
    private var index = 0
    private var revealed = false
    private var busy = false
    private var lastTick = 0
    private var usdRate: Double? = null
    private var currency = "ZAR"
    private var collection: List<Card> = emptyList()
    private val bigImages = mutableMapOf<Long, Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pack)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        set = CardSet(
            code = intent.getStringExtra(EXTRA_CODE).orEmpty(),
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            cardCount = intent.getIntExtra(EXTRA_COUNT, 0),
            date = intent.getStringExtra(EXTRA_DATE).orEmpty(),
            imageUrl = null,
        )
        haptics = Haptics(this)
        tilt = TiltTracker(this)
        packView = findViewById(R.id.pack)
        holo = findViewById(R.id.holo)
        current = findViewById(R.id.current)
        nextBack = findViewById(R.id.nextBack)
        glow = findViewById(R.id.glow)
        if (tilt.available) {
            packView.externalTilt = { tilt.x to tilt.y }
            holo.externalTilt = { tilt.x to tilt.y }
        }

        val close = findViewById<View>(R.id.close)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            close.translationY = bars.top.toFloat()
            findViewById<View>(R.id.packControls).updatePadding(bottom = bars.bottom + dp(28))
            findViewById<View>(R.id.summaryButtons).updatePadding(bottom = bars.bottom + dp(16))
            findViewById<View>(R.id.summaryStage).updatePadding(top = bars.top + dp(56))
            insets
        }
        close.setOnClickListener { finish() }

        packView.onTearProgress = { p ->
            val step = (p * 10).toInt()
            if (step > lastTick) { lastTick = step; haptics.tick() }
            if (p == 0f) lastTick = 0
        }
        packView.onRip = {
            haptics.rip()
            findViewById<View>(R.id.tearHint).animate().alpha(0f).setDuration(150).start()
            findViewById<View>(R.id.openButton).visibility = View.INVISIBLE
        }
        packView.onOpened = ::startReveal
        packView.onError = { msg -> showLoadingMessage("The pack couldn't be drawn on this phone.\n$msg") }
        findViewById<MaterialButton>(R.id.openButton).setOnClickListener { packView.rip() }
        findViewById<View>(R.id.revealStage).setOnClickListener { advance() }
        findViewById<MaterialButton>(R.id.openAnother).setOnClickListener { newPack() }
        findViewById<MaterialButton>(R.id.choosePack).setOnClickListener { finish() }

        findViewById<TextView>(R.id.setName).text = set.name
        load()
    }

    override fun onResume() {
        super.onResume()
        tilt.start()
        packView.onResume()
        holo.onResume()
    }

    override fun onPause() {
        super.onPause()
        tilt.stop()
        packView.onPause()
        holo.onPause()
    }

    private fun load() {
        lifecycleScope.launch {
            val result = runCatching { repo.cards(set) }
            setCards = result.getOrDefault(emptyList())
            if (!PackGenerator.canOpen(setCards)) {
                showLoadingMessage(
                    if (result.isFailure) "Couldn't load this set. Check your internet connection."
                    else "This set doesn't have enough cards to make a pack."
                )
                return@launch
            }
            collection = dao.all()
            currency = collection.firstOrNull()?.currency ?: "ZAR"
            usdRate = runCatching { Exchange.usdTo(currency) }.getOrNull()
            val art = PackArt.featured(setCards)?.let { fetch(PackArt.croppedArtUrl(it.artworkId)) }
            packBitmap = PackArt.draw(set, art)
            newPack()
        }
    }

    /** Roll a new pack and show it sealed. */
    private fun newPack() {
        val bmp = packBitmap ?: return
        pack = PackGenerator.open(setCards)
        index = 0
        lastTick = 0
        bigImages.clear()
        prefetch()
        findViewById<View>(R.id.loading).visibility = View.GONE
        findViewById<View>(R.id.summaryStage).visibility = View.GONE
        findViewById<View>(R.id.revealStage).visibility = View.GONE
        findViewById<View>(R.id.packStage).visibility = View.VISIBLE
        findViewById<View>(R.id.tearHint).alpha = 1f
        findViewById<View>(R.id.openButton).visibility = View.VISIBLE
        packView.visibility = View.VISIBLE
        packView.setPack(bmp)
    }

    /** Download the pack's images now so reveals are instant. */
    private fun prefetch() {
        val loader = imageLoader
        loader.enqueue(ImageRequest.Builder(this).data(PackArt.CARD_BACK).build())
        pack.forEach { c ->
            loader.enqueue(ImageRequest.Builder(this).data(PackArt.cardSmallUrl(c.artworkId)).build())
            if (c.tier >= Tier.ULTRA) lifecycleScope.launch {
                fetch(PackArt.cardUrl(c.artworkId))?.let { bigImages[c.artworkId] = it }
            }
        }
    }

    // --- Reveal -----------------------------------------------------------------------------------

    private fun startReveal() {
        findViewById<View>(R.id.packStage).visibility = View.GONE
        packView.visibility = View.GONE
        findViewById<View>(R.id.revealStage).visibility = View.VISIBLE
        val area = findViewById<View>(R.id.cardArea)
        area.translationY = dp(220).toFloat()
        area.alpha = 0f
        area.animate().translationY(0f).alpha(1f).setDuration(380).setInterpolator(DecelerateInterpolator()).start()
        showCard(0)
    }

    /** Commons come out face up; Rares and better start face down for a proper reveal. */
    private fun startsFaceDown(c: SetCard) = c.tier >= Tier.RARE

    private fun showCard(i: Int) {
        index = i
        val c = pack[i]
        revealed = !startsFaceDown(c)
        current.rotationY = 0f
        current.translationX = 0f
        current.rotation = 0f
        current.alpha = 1f
        current.visibility = View.VISIBLE
        if (revealed) current.load(PackArt.cardSmallUrl(c.artworkId)) else current.load(PackArt.CARD_BACK)
        nextBack.visibility = if (i + 1 < pack.size) View.VISIBLE else View.INVISIBLE
        nextBack.load(PackArt.CARD_BACK)
        findViewById<TextView>(R.id.counter).text = "${i + 1} / ${pack.size}"
        updateLabels(if (revealed) c else null)
        findViewById<TextView>(R.id.tapHint).text = if (revealed) "Tap for the next card" else "Tap to flip"
    }

    private fun updateLabels(c: SetCard?) {
        val name = findViewById<TextView>(R.id.pullName)
        val rarity = findViewById<TextView>(R.id.pullRarity)
        if (c == null) {
            name.text = if (pack[index].tier >= Tier.SUPER) "Something shiny…" else ""
            rarity.text = ""
            return
        }
        name.text = c.name
        val value = valueCents(c)?.let { " · " + Money.format(it, currency) } ?: ""
        rarity.text = c.rarity + value
        rarity.setTextColor(tierColor(c.tier))
    }

    private fun advance() {
        if (busy) return
        val c = pack[index]
        if (!revealed) { flip(c); return }
        if (holo.visibility == View.VISIBLE) holo.visibility = View.GONE
        if (index + 1 >= pack.size) { showSummary(); return }
        // Slide the card away to reveal the next.
        busy = true
        current.visibility = View.VISIBLE
        current.animate().translationX(-dp(420).toFloat()).rotation(-14f).alpha(0f).setDuration(240)
            .setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    current.animate().setListener(null)
                    busy = false
                    showCard(index + 1)
                }
            }).start()
    }

    private fun flip(c: SetCard) {
        busy = true
        val big = c.tier >= Tier.SUPER
        if (big) {
            // Glow in the rarity's colour, building up before the flip.
            glow.backgroundTintList = ColorStateList.valueOf(tierColor(c.tier))
            glow.alpha = 0f
            glow.scaleX = 0.6f; glow.scaleY = 0.6f
            glow.animate().alpha(if (c.tier >= Tier.SECRET) 1f else 0.8f).scaleX(1.1f).scaleY(1.1f)
                .setDuration(if (c.tier >= Tier.SECRET) 1300 else 800).start()
            haptics.buildUp()
            val shake = ObjectAnimator.ofFloat(current, View.ROTATION, 0f, -2f, 2f, -2f, 2f, 0f)
            shake.duration = if (c.tier >= Tier.SECRET) 1100 else 650
            shake.start()
            current.postDelayed({ doFlip(c, slow = true) }, shake.duration)
        } else {
            doFlip(c, slow = false)
        }
    }

    private fun doFlip(c: SetCard, slow: Boolean) {
        val half = if (slow) 260L else 140L
        current.animate().rotationY(90f).setDuration(half).setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    current.animate().setListener(null)
                    current.load(PackArt.cardSmallUrl(c.artworkId))
                    current.rotationY = -90f
                    current.animate().rotationY(0f).setDuration(half).setInterpolator(DecelerateInterpolator())
                        .withEndAction { afterFlip(c) }.start()
                }
            }).start()
        haptics.flip()
    }

    private fun afterFlip(c: SetCard) {
        revealed = true
        busy = false
        updateLabels(c)
        findViewById<TextView>(R.id.tapHint).text =
            if (index + 1 >= pack.size) "Tap to see your pulls" else "Tap for the next card"
        if (c.tier >= Tier.SUPER) haptics.bigReveal()
        glow.animate().alpha(if (c.tier >= Tier.ULTRA) 0.55f else 0f).setDuration(600).start()
        // Ultra Rares and better: show it shining in 3D.
        val bmp = bigImages[c.artworkId]
        if (c.tier >= Tier.ULTRA && bmp != null) {
            holo.setCard(bmp, c.rarity, isPhoto = false, pendulum = c.type.contains("Pendulum", true))
            holo.visibility = View.VISIBLE
            current.visibility = View.INVISIBLE
        }
    }

    // --- Summary ----------------------------------------------------------------------------------

    private fun showSummary() {
        glow.alpha = 0f
        holo.visibility = View.GONE
        findViewById<View>(R.id.revealStage).visibility = View.GONE
        findViewById<View>(R.id.summaryStage).visibility = View.VISIBLE
        findViewById<TextView>(R.id.summaryTitle).text = set.name
        val total = pack.mapNotNull(::valueCents).sum()
        findViewById<TextView>(R.id.summaryValue).text =
            if (usdRate == null) "Prices unavailable offline" else "This pack ≈ ${Money.format(total, currency)}"
        val list = findViewById<RecyclerView>(R.id.pulls)
        list.layoutManager = GridLayoutManager(this, 3)
        list.adapter = PullAdapter(pack.reversed())  // best first
    }

    private fun valueCents(c: SetCard): Long? {
        val rate = usdRate ?: return null
        return c.priceUsd?.let { (it * rate * 100).roundToLong() }
    }

    private fun owned(c: SetCard) = collection.any {
        !it.wishlist && it.code.equals(c.code, true) &&
            CardLookup.baseRarity(it.rarity).equals(CardLookup.baseRarity(c.rarity), true)
    }

    private fun addToWishlist(c: SetCard) {
        lifecycleScope.launch {
            val rate = usdRate
            dao.upsert(
                Card(
                    id = java.util.UUID.randomUUID().toString().replace("-", ""),
                    name = c.name, setName = set.name, code = c.code, rarity = c.rarity, quantity = 1,
                    valuePerUnitCents = valueCents(c) ?: 0, paidPerUnitCents = null, currency = currency,
                    wishlist = true, imageUrl = PackArt.cardSmallUrl(c.artworkId), dateAdded = Dates.now(),
                    priceUpdatedAt = if (rate != null && c.priceUsd != null) Dates.now() else null,
                )
            )
            collection = dao.all()
            Snackbar.make(findViewById(R.id.root), "Added ${c.name} to your wishlist", Snackbar.LENGTH_SHORT).show()
        }
    }

    private inner class PullAdapter(private val items: List<SetCard>) : RecyclerView.Adapter<PullAdapter.H>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            H(LayoutInflater.from(parent.context).inflate(R.layout.item_pull, parent, false))
        override fun onBindViewHolder(h: H, position: Int) = h.bind(items[position])

        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            fun bind(c: SetCard) {
                itemView.findViewById<ImageView>(R.id.image).load(PackArt.cardSmallUrl(c.artworkId)) {
                    placeholder(R.drawable.card_placeholder)
                }
                itemView.findViewById<TextView>(R.id.name).text = c.name
                itemView.findViewById<TextView>(R.id.rarity).apply { text = c.rarity; setTextColor(tierColor(c.tier)) }
                itemView.findViewById<TextView>(R.id.value).text = valueCents(c)?.let { Money.format(it, currency) } ?: "—"
                val tag = itemView.findViewById<TextView>(R.id.tag)
                val have = owned(c)
                tag.text = if (have) "✓ Owned" else "New"
                tag.backgroundTintList = ColorStateList.valueOf(if (have) Color.rgb(70, 70, 84) else Color.rgb(255, 196, 64))
                tag.setTextColor(if (have) Color.WHITE else Color.BLACK)
                itemView.findViewById<MaterialButton>(R.id.want).apply {
                    visibility = if (have) View.GONE else View.VISIBLE
                    setOnClickListener { addToWishlist(c) }
                }
                itemView.setOnClickListener {
                    startActivity(
                        Intent(this@PackOpenActivity, HoloViewerActivity::class.java)
                            .putExtra(HoloViewerActivity.EXTRA_NAME, c.name)
                            .putExtra(HoloViewerActivity.EXTRA_RARITY, c.rarity)
                            .putExtra(HoloViewerActivity.EXTRA_IMAGE, PackArt.cardSmallUrl(c.artworkId))
                    )
                }
            }
        }
    }

    // --- Helpers ----------------------------------------------------------------------------------

    private fun showLoadingMessage(text: String) {
        findViewById<View>(R.id.packStage).visibility = View.GONE
        findViewById<View>(R.id.loading).visibility = View.VISIBLE
        findViewById<TextView>(R.id.loadingText).text = text
    }

    private suspend fun fetch(url: String): Bitmap? {
        val request = ImageRequest.Builder(this).data(url).allowHardware(false).build()
        val result = imageLoader.execute(request) as? SuccessResult ?: return null
        return (result.drawable as? BitmapDrawable)?.bitmap
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_NAME = "name"
        const val EXTRA_COUNT = "count"
        const val EXTRA_DATE = "date"

        fun intent(context: Context, set: CardSet) = Intent(context, PackOpenActivity::class.java)
            .putExtra(EXTRA_CODE, set.code).putExtra(EXTRA_NAME, set.name)
            .putExtra(EXTRA_COUNT, set.cardCount).putExtra(EXTRA_DATE, set.date)

        /** Glow / label colour for a tier. */
        fun tierColor(t: Tier): Int = when (t) {
            Tier.COMMON -> Color.rgb(200, 200, 210)
            Tier.RARE -> Color.rgb(220, 225, 235)
            Tier.SUPER -> Color.rgb(120, 200, 255)
            Tier.ULTRA -> Color.rgb(255, 205, 80)
            Tier.SECRET -> Color.rgb(255, 120, 220)
            Tier.PREMIUM -> Color.rgb(170, 255, 240)
        }
    }
}
