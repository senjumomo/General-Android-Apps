package com.senjumomo.cards.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.senjumomo.cards.R
import com.senjumomo.cards.data.Card
import com.senjumomo.cards.data.CardDatabase
import kotlinx.coroutines.launch

/** Add a card (no id extra) or view/edit one. */
class EditActivity : AppCompatActivity() {

    private lateinit var form: CardForm
    private var card: Card? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        val dao = CardDatabase.get(this).cards()
        val id = intent.getStringExtra(EXTRA_ID)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = if (id == null) "Add card" else "Edit card"
        toolbar.setNavigationOnClickListener { finish() }
        findViewById<android.view.View>(R.id.content).padForSystemBars()

        form = CardForm(findViewById(R.id.root))
        form.image.setOnClickListener { card?.let { showImage(it) } }
        form.tcgplayer.setOnClickListener {
            card?.tcgplayerId?.let {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.senjumomo.cards.net.Tcgplayer.productPage(it))))
            }
        }

        // Only fill the form the first time, so rotating the screen keeps unsaved edits.
        savedInstanceState?.getInt(STATE_QUANTITY)?.let { form.quantity = it }
        lifecycleScope.launch {
            val all = dao.all()
            card = id?.let { dao.get(it) }
            if (savedInstanceState == null) {
                form.bind(card, all.map { it.setName }.filter { it.isNotBlank() }.distinct().sorted(),
                    all.map { it.rarity }.filter { it.isNotBlank() }.distinct().sorted(),
                    newOnWishlist = intent.getBooleanExtra(EXTRA_WISHLIST, false))
            }
            if (card != null) {
                toolbar.inflateMenu(R.menu.edit)
                toolbar.setOnMenuItemClickListener { confirmDelete(); true }
            }
        }

        findViewById<MaterialButton>(R.id.save).setOnClickListener {
            val edited = form.read() ?: return@setOnClickListener
            lifecycleScope.launch {
                dao.upsert(edited)
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun showImage(card: Card) {
        val view = layoutInflater.inflate(R.layout.dialog_image, null)
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(view)
        com.senjumomo.cards.data.CardImages.load(view.findViewById(R.id.image), com.senjumomo.cards.data.CardImages.large(card))
        view.setOnClickListener { dialog.dismiss() }
        view.findViewById<android.view.View>(R.id.close).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_QUANTITY, form.quantity)
    }

    private fun confirmDelete() {
        val c = card ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${c.name}?")
            .setMessage("This removes all ${c.quantity} of this printing from your collection.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    CardDatabase.get(this@EditActivity).cards().delete(c.id)
                    lastDeleted = c
                    setResult(RESULT_OK)
                    finish()
                }
            }
            .show()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_WISHLIST = "wishlist"
        private const val STATE_QUANTITY = "quantity"

        /** Handed back to the list so it can offer Undo. */
        private var lastDeleted: Card? = null
        fun takeDeleted(): Card? = lastDeleted.also { lastDeleted = null }
    }
}
