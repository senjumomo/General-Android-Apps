package com.senjumomo.cards.holo

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.senjumomo.cards.R
import com.senjumomo.cards.data.Card
import com.senjumomo.cards.data.CardDatabase
import com.senjumomo.cards.net.Tcgplayer
import com.senjumomo.cards.net.Ygoprodeck
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Full-screen card that reacts to tilting the phone, with a foil effect matching its rarity. */
class HoloViewerActivity : AppCompatActivity() {

    private lateinit var cardView: HoloGLView
    private lateinit var tilt: TiltTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_holo)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        val content = findViewById<View>(R.id.content)
        val close = findViewById<View>(R.id.close)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.updatePadding(top = bars.top + dp(64), bottom = bars.bottom + dp(28))
            close.translationY = bars.top.toFloat()
            insets
        }
        close.setOnClickListener { finish() }

        cardView = findViewById(R.id.card)
        tilt = TiltTracker(this)
        if (tilt.available) cardView.externalTilt = { tilt.x to tilt.y }
        cardView.onError = ::showError

        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        lifecycleScope.launch {
            val card = CardDatabase.get(this@HoloViewerActivity).cards().get(id) ?: return@launch finish()
            findViewById<TextView>(R.id.name).text = card.name
            findViewById<TextView>(R.id.style).text =
                "${card.rarity.ifBlank { "Common" }} · ${HoloStyle.label(HoloStyle.mode(card.rarity))}"
            load(card)
        }
    }

    override fun onResume() {
        super.onResume()
        tilt.start()
        cardView.onResume()
    }

    override fun onPause() {
        super.onPause()
        tilt.stop()
        cardView.onPause()
    }

    /**
     * Uses YGOPRODeck's high-res scan (813 × 1185) of this printing's artwork, matched by set code.
     * Falls back to TCGplayer's photo if there's no match.
     */
    private suspend fun load(card: Card) {
        val info = lifecycleScope.async { runCatching { Ygoprodeck.byName(card.name) }.getOrNull() }
        var bitmap: Bitmap? = null
        var isPhoto = false
        val small = card.imageUrl?.takeIf { it.isNotBlank() }
            ?: card.code.takeIf { it.isNotBlank() }?.let { code ->
                runCatching { Ygoprodeck.setPrinting(code) }.getOrNull()?.let { Ygoprodeck.smallImage(it.artworkId) }
            }
            ?: info.await()?.imageSmall
        small?.let { bitmap = fetch(Ygoprodeck.largeImage(it)) }
        if (bitmap == null) {
            card.tcgplayerId?.let { bitmap = fetch(Tcgplayer.largeImage(it)); isPhoto = true }
        }
        val pendulum = info.await()?.type?.contains("Pendulum", ignoreCase = true) == true

        findViewById<View>(R.id.loading).visibility = View.GONE
        val bmp = bitmap
        if (bmp == null) {
            findViewById<TextView>(R.id.hint).text = "Couldn't load the card image. Check your connection."
            return
        }
        shown = bmp
        if (failed) { showError(failedMessage); return }
        cardView.setCard(bmp, card.rarity, isPhoto, pendulum)
        cardView.visibility = View.VISIBLE
    }

    private var shown: Bitmap? = null
    private var failed = false
    private var failedMessage = ""

    /** OpenGL failed: show the plain image and the reason, so it can be reported. */
    private fun showError(message: String) {
        failed = true
        failedMessage = message
        cardView.visibility = View.GONE
        findViewById<android.widget.ImageView>(R.id.staticCard).apply {
            visibility = View.VISIBLE
            shown?.let(::setImageBitmap)
        }
        findViewById<TextView>(R.id.hint).text = "The foil effect couldn't start on this phone.\n$message"
    }

    private suspend fun fetch(url: String): Bitmap? {
        val request = ImageRequest.Builder(this).data(url).allowHardware(false).build()
        val result = imageLoader.execute(request) as? SuccessResult ?: return null
        return (result.drawable as? BitmapDrawable)?.bitmap
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ID = "id"
    }
}
