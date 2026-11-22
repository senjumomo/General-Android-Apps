package com.senjumomo.cards.packs

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sin

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
    private lateinit var fxBack: PackFx
    private lateinit var fxFront: PackFx
    private lateinit var cardArea: View
    /** Holding the fast-forward button. */
    private var skipping = false

    private var setCards: List<SetCard> = emptyList()
    private var packBitmap: Bitmap? = null
    private var pack: List<SetCard> = emptyList()
    private var index = 0
    private var revealed = false
    private var busy = false
    private var lastTick = 0
    // Swipe gesture on the revealed card.
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var gestureIgnored = false
    private var velocity: VelocityTracker? = null
    private var usdRate: Double? = null
    private var currency = "ZAR"
    private var collection: List<Card> = emptyList()
    private val bigImages = mutableMapOf<Long, Bitmap>()
    /** The official card back once downloaded; the drawn stand-in until then. */
    private var back: Bitmap? = null
    private fun backImage(): Bitmap = back ?: CardBack.get()

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
        fxBack = findViewById(R.id.fxBack)
        fxFront = findViewById(R.id.fxFront)
        cardArea = findViewById(R.id.cardArea)
        current.cameraDistance = 9000 * resources.displayMetrics.density
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
            findViewById<View>(R.id.skip).translationY = -bars.bottom.toFloat()
            insets
        }
        close.setOnClickListener { finish() }

        packView.onTearProgress = { p ->
            val step = (p * 10).toInt()
            if (step > lastTick) { lastTick = step; haptics.tick() }
            if (p == 0f) lastTick = 0
            // The room goes dark as you cut, so the light from inside stands out.
            fxBack.dim(p * 0.6f, if (p == 0f) 250 else 60)
        }
        packView.onRip = {
            haptics.rip()
            val (x, y, w) = packView.tearLine().let { Triple(it[0], it[1], it[2]) }
            fxBack.dim(0f, 700)
            fxBack.burst(packView.left + x, packView.top + y, w)
            findViewById<View>(R.id.tearHint).animate().alpha(0f).setDuration(150).start()
            findViewById<View>(R.id.openButton).visibility = View.INVISIBLE
        }
        packView.onOpened = ::startReveal
        packView.onError = { msg -> showLoadingMessage("The pack couldn't be drawn on this phone.\n$msg") }
        findViewById<MaterialButton>(R.id.openButton).setOnClickListener { packView.rip() }
        val stage = findViewById<View>(R.id.revealStage)
        stage.setOnClickListener { onCardTap() }
        stage.setOnTouchListener { v, e -> onSwipeTouch(v, e) }
        // The holo view keeps its drag-to-tilt; the same gesture can also throw the card away.
        holo.setOnTouchListener { _, e -> onSwipeTouch(stage, e, consume = false) }
        findViewById<MaterialButton>(R.id.openAnother).setOnClickListener { newPack() }
        findViewById<MaterialButton>(R.id.skip).apply {
            setOnClickListener { skipStep() }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; skipping = true; skipTick.run() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; skipping = false }
                }
                true
            }
        }
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
        skipping = false
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
            launch { back = fetch(CardBack.OFFICIAL_URL) ?: withContextDefault { CardBack.get() } }
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
        fxBack.clear()
        fxFront.clear()
        skipping = false
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
        pack.forEach { c ->
            // Full-resolution scans (813 × 1185) so cards are sharp at full screen width.
            loader.enqueue(ImageRequest.Builder(this).data(PackArt.cardUrl(c.artworkId)).build())
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
        current.translationY = 0f
        current.rotation = 0f
        current.scaleX = 1f; current.scaleY = 1f
        current.alpha = 1f
        fxBack.rainbow(false)
        current.visibility = View.VISIBLE
        if (revealed) loadFace(c) else current.setImageBitmap(backImage())
        nextBack.visibility = if (i + 1 < pack.size) View.VISIBLE else View.INVISIBLE
        nextBack.setImageBitmap(backImage())
        findViewById<TextView>(R.id.counter).text = "${i + 1} / ${pack.size}"
        updateLabels(if (revealed) c else null, pop = revealed)
        findViewById<TextView>(R.id.tapHint).text = if (revealed) nextHint() else "Tap to flip"
        // Each card lands with a little twinkle.
        if (revealed) cardArea.post { if (index == i) fxFront.twinkle(cardRect(), count = 2) }
    }

    private fun nextHint() = if (index + 1 >= pack.size) "Swipe to see your pulls" else "Swipe for the next card"

    /** The card's bounds on screen (in the reveal stage's coordinates). */
    private fun cardRect() = RectF(
        cardArea.left.toFloat(), cardArea.top + current.top.toFloat(),
        cardArea.right.toFloat(), cardArea.top + current.bottom.toFloat(),
    )

    private fun updateLabels(c: SetCard?, pop: Boolean = false) {
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
        if (pop) {
            // The rarity pops in.
            rarity.alpha = 0f; rarity.scaleX = 0.4f; rarity.scaleY = 0.4f
            rarity.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380)
                .setInterpolator(OvershootInterpolator(if (c.tier >= Tier.SUPER) 3.5f else 2f)).start()
        }
    }

    /** Tap flips a face-down card; on a revealed card it nudges sideways to show it can be swiped. */
    private fun onCardTap() {
        if (busy) return
        if (!revealed) { flip(pack[index]); return }
        val v = swipeTarget()
        val nudge = dp(28).toFloat()
        ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, -nudge, nudge * 0.4f, 0f).setDuration(420).start()
        ObjectAnimator.ofFloat(v, View.ROTATION, 0f, -4f, 1.5f, 0f).setDuration(420).start()
    }

    /** The view showing the current card: the shining 3D one for big pulls, else the image. */
    private fun swipeTarget(): View = if (holo.visibility == View.VISIBLE) holo else current

    /**
     * The card follows your finger sideways (tilting a little) and is thrown off screen when dragged
     * far enough or flicked; otherwise it springs back. Face-down cards only give a little, so they
     * have to be flipped first. A touch that doesn't move is a tap.
     * [consume] is false for the holo view, which also uses the drag to tilt the card.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun onSwipeTouch(stage: View, e: MotionEvent, consume: Boolean = true): Boolean {
        val target = swipeTarget()
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureIgnored = busy
                swiping = false
                downX = e.rawX; downY = e.rawY
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureIgnored) return consume
                velocity?.addMovement(e)
                val dx = e.rawX - downX
                if (!swiping && abs(dx) > slop && abs(dx) > abs(e.rawY - downY)) swiping = true
                if (swiping) {
                    val x = if (revealed) dx else dx * 0.2f
                    target.translationX = x
                    target.rotation = x / stage.width * 18f
                }
            }
            MotionEvent.ACTION_UP -> {
                if (gestureIgnored) return consume
                velocity?.addMovement(e)
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val dx = e.rawX - downX
                val moved = abs(dx) > slop || abs(e.rawY - downY) > slop
                val flick = abs(vx) > dp(900) && sign(vx) == sign(dx)
                when {
                    !swiping && !moved -> if (consume) stage.performClick() else onCardTap()
                    swiping && revealed && (abs(dx) > stage.width * 0.28f || flick) -> throwCard(target, sign(dx), vx)
                    swiping -> springBack(target)
                }
                swiping = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (swiping) springBack(target)
                swiping = false
            }
        }
        return consume
    }

    private fun springBack(v: View) {
        v.animate().translationX(0f).rotation(0f).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    /** Fling the card off screen in [dir] (−1 left, 1 right), then show the next one. */
    private fun throwCard(v: View, dir: Float, vx: Float) {
        busy = true
        haptics.tick()
        val end = dir * (findViewById<View>(R.id.root).width + v.width) * 0.75f
        // Keep the flick's speed: faster throws leave quicker.
        val speed = maxOf(abs(vx), dp(1800).toFloat())
        val duration = (abs(end - v.translationX) / speed * 1000).toLong().coerceIn(120, 280)
        v.animate().translationX(end).rotation(dir * 24f).alpha(0f).setDuration(duration)
            .setInterpolator(AccelerateInterpolator(0.6f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    v.animate().setListener(null)
                    holo.visibility = View.GONE
                    holo.translationX = 0f; holo.rotation = 0f; holo.alpha = 1f
                    busy = false
                    if (index + 1 >= pack.size) showSummary() else showCard(index + 1)
                }
            }).start()
    }

    /** One step of fast-forward: flip the face-down card, or throw away the revealed one. */
    private fun skipStep(): Boolean {
        if (busy || findViewById<View>(R.id.revealStage).visibility != View.VISIBLE) return true
        val c = pack[index]
        if (!revealed) {
            // Never skip past a big pull: stop and let it be revealed properly.
            if (c.tier >= Tier.ULTRA) { onCardTap(); return false }
            busy = true
            doFlip(c, slow = false)
        } else {
            throwCard(swipeTarget(), -1f, dp(3200).toFloat())
        }
        return true
    }

    private val skipTick = object : Runnable {
        override fun run() {
            if (!skipping) return
            if (!skipStep()) { skipping = false; return }
            current.postDelayed(this, 110)
        }
    }

    private fun flip(c: SetCard) {
        busy = true
        val big = c.tier >= Tier.SUPER
        // Ultra Rares and better: the background floods with colour while it builds up.
        if (c.tier >= Tier.ULTRA) fxBack.rainbow(true)
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
            current.postDelayed({ if (c.tier >= Tier.ULTRA) spin(c) else doFlip(c, slow = true) }, shake.duration)
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
                    loadFace(c)
                    current.rotationY = -90f
                    current.animate().rotationY(0f).setDuration(half).setInterpolator(DecelerateInterpolator())
                        .withEndAction { afterFlip(c) }.start()
                }
            }).start()
        haptics.flip()
    }

    /** Big pulls spin round one and a half times, back to face, landing with a flare. */
    private fun spin(c: SetCard) {
        val face = bigImages[c.artworkId]
        var showingFace = false
        haptics.flip()
        ValueAnimator.ofFloat(0f, 540f).apply {
            duration = 1150
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                val angle = it.animatedValue as Float
                val m = angle % 360f
                val faceSide = m > 90f && m < 270f
                if (faceSide != showingFace) {
                    showingFace = faceSide
                    when {
                        faceSide && face != null -> current.setImageBitmap(face)
                        faceSide -> loadFace(c)          // big image not ready: stay on the face from here
                        face != null -> current.setImageBitmap(backImage())
                    }
                }
                // Keep the visible side within ±90° so it's never drawn mirrored.
                current.rotationY = (angle + 90f) % 180f - 90f
                val lift = 1f + 0.14f * sin(angle / 540f * PI.toFloat())
                current.scaleX = lift; current.scaleY = lift
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    current.rotationY = 0f
                    current.scaleX = 1f; current.scaleY = 1f
                    if (!showingFace) loadFace(c)
                    afterFlip(c)
                }
            })
            start()
        }
    }

    private fun afterFlip(c: SetCard) {
        revealed = true
        busy = false
        updateLabels(c, pop = true)
        findViewById<TextView>(R.id.tapHint).text = nextHint()
        val r = cardRect()
        if (c.tier >= Tier.SUPER) {
            haptics.bigReveal()
            fxFront.flare(r.right - r.width() * 0.12f, r.top + r.height() * 0.08f, tierColor(c.tier))
            fxFront.twinkle(r, count = if (c.tier >= Tier.ULTRA) 7 else 5, color = tierColor(c.tier))
        } else {
            fxFront.twinkle(r, count = 3)
        }
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
        skipping = false
        fxBack.rainbow(false)
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

    /** Full-res face; the small image (likely cached) shows first if the big one isn't ready. */
    private fun loadFace(c: SetCard) {
        current.load(PackArt.cardUrl(c.artworkId)) {
            placeholderMemoryCacheKey(PackArt.cardSmallUrl(c.artworkId))
            crossfade(false)
        }
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

    private suspend fun <T> withContextDefault(block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { block() }

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
