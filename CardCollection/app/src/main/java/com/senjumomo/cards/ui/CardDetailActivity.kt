package com.senjumomo.cards.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.senjumomo.cards.R
import com.senjumomo.cards.data.Card
import com.senjumomo.cards.data.CardDatabase
import com.senjumomo.cards.data.CardImages
import com.senjumomo.cards.data.PriceRefresher
import com.senjumomo.cards.net.Tcgplayer
import kotlinx.coroutines.launch

/** Read-only view of a card. Editing happens only after tapping Edit. */
class CardDetailActivity : AppCompatActivity() {

    private val dao by lazy { CardDatabase.get(this).cards() }
    private lateinit var view: CardDetailView
    private var card: Card? = null
    private var refreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()

        findViewById<View>(R.id.content).padForSystemBars()
        view = CardDetailView(findViewById(R.id.root))
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_edit -> startActivity(
                    Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_ID, id)
                )
                R.id.action_delete -> confirmDelete()
            }
            true
        }

        view.image.setOnClickListener { card?.let(::showImage) }
        view.tcgplayer.setOnClickListener {
            card?.tcgplayerId?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Tcgplayer.productPage(it)))) }
        }
        view.priceAction.setOnClickListener {
            val c = card ?: return@setOnClickListener
            if (c.tcgplayerId != null) refreshPrice(c)
            else startActivity(Intent(this, AddCardActivity::class.java).putExtra(AddCardActivity.EXTRA_LINK_ID, c.id))
        }
        view.gotIt.setOnClickListener { card?.let(::moveToCollection) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.observe(id).collect { c ->
                    if (c == null) { finish(); return@collect }
                    card = c
                    view.bind(c, refreshing)
                }
            }
        }
    }

    private fun refreshPrice(c: Card) {
        refreshing = true
        view.bind(c, refreshing = true)
        lifecycleScope.launch {
            val result = runCatching { PriceRefresher(dao).refreshOne(c) }
            refreshing = false
            card?.let { view.bind(it) }
            val msg = when {
                result.isFailure -> "Couldn't reach TCGplayer. Check your connection."
                result.getOrNull() == null -> "TCGplayer has no market price for this card right now."
                else -> "Price updated: ${Money.format(result.getOrNull()!!.valuePerUnitCents, c.currency)} each"
            }
            Snackbar.make(findViewById(R.id.root), msg, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun moveToCollection(c: Card) {
        lifecycleScope.launch {
            // Merge into an existing copy of the same printing if there is one.
            val existing = dao.all().firstOrNull {
                !it.wishlist && it.code.equals(c.code, true) && it.rarity.equals(c.rarity, true) &&
                    it.condition == c.condition && it.id != c.id
            }
            if (existing != null) {
                dao.upsert(existing.copy(quantity = existing.quantity + c.quantity))
                dao.delete(c.id)
            } else {
                dao.upsert(c.copy(wishlist = false, dateAdded = com.senjumomo.cards.data.Dates.now()))
            }
            Snackbar.make(findViewById(R.id.root), "Moved to your collection", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete() {
        val c = card ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${c.name}?")
            .setMessage(
                if (c.quantity > 1) "This removes all ${c.quantity} copies of this printing."
                else "This removes the card from your ${if (c.wishlist) "wishlist" else "collection"}."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    lastDeleted = c
                    dao.delete(c.id) // the observer then closes this screen
                }
            }
            .show()
    }

    private fun showImage(c: Card) {
        val v = layoutInflater.inflate(R.layout.dialog_image, null)
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(v)
        CardImages.load(v.findViewById(R.id.image), CardImages.large(c))
        v.setOnClickListener { dialog.dismiss() }
        v.findViewById<View>(R.id.close).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    companion object {
        const val EXTRA_ID = "id"

        /** Handed back to the list so it can offer Undo. */
        private var lastDeleted: Card? = null
        fun takeDeleted(): Card? = lastDeleted.also { lastDeleted = null }
    }
}
