package com.senjumomo.cards.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.senjumomo.cards.R
import com.senjumomo.cards.data.Card
import com.senjumomo.cards.data.CardDatabase
import com.senjumomo.cards.data.CardImages
import com.senjumomo.cards.data.CardLookup
import com.senjumomo.cards.data.CollectionJson
import com.senjumomo.cards.data.Printing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Add a card by searching TCGplayer's catalogue as you type, then picking the exact printing.
 * With [EXTRA_LINK_ID], picks a printing for an existing card instead (to give it a price source).
 */
class AddCardActivity : AppCompatActivity() {

    private val dao by lazy { CardDatabase.get(this).cards() }
    private lateinit var adapter: PrintingAdapter
    private lateinit var search: TextInputEditText
    private lateinit var progress: View
    private lateinit var message: View
    private lateinit var messageText: TextView
    private lateinit var manual: MaterialButton

    private var searchJob: Job? = null
    private var cards: List<Card> = emptyList()
    private var currency = "ZAR"
    private var usdRate: Double? = null
    private var linkCard: Card? = null
    private val defaultWishlist by lazy { intent.getBooleanExtra(EXTRA_WISHLIST, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        search = findViewById(R.id.search)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)
        messageText = findViewById(R.id.messageText)
        manual = findViewById(R.id.manual)
        val results = findViewById<RecyclerView>(R.id.results)
        results.padForSystemBars()

        adapter = PrintingAdapter(::onPick)
        results.layoutManager = LinearLayoutManager(this)
        results.adapter = adapter

        manual.setOnClickListener {
            startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_WISHLIST, defaultWishlist))
            finish()
        }
        search.doAfterTextChanged { scheduleSearch(it?.toString().orEmpty()) }
        search.setOnEditorActionListener { _, _, _ -> scheduleSearch(search.text.toString(), now = true); true }

        val linkId = intent.getStringExtra(EXTRA_LINK_ID)
        toolbar.title = when {
            linkId != null -> "Find on TCGplayer"
            defaultWishlist -> "Add to wishlist"
            else -> "Add card"
        }
        showMessage("Type a card name, or a set code like MAMA-EN081.", showManual = linkId == null)

        lifecycleScope.launch {
            cards = dao.all()
            currency = cards.firstOrNull()?.currency ?: "ZAR"
            adapter.owned = cards
            linkCard = linkId?.let { dao.get(it) }
            linkCard?.let { search.setText(it.name); search.setSelection(it.name.length) }
            usdRate = CardLookup.usdRate(currency)
            adapter.setPricing(usdRate, currency)
        }
        search.requestFocus()
    }

    private fun scheduleSearch(text: String, now: Boolean = false) {
        searchJob?.cancel()
        val q = text.trim()
        if (q.length < 3) {
            adapter.submit(emptyList())
            progress.visibility = View.INVISIBLE
            showMessage("Type a card name, or a set code like MAMA-EN081.", showManual = linkCard == null)
            return
        }
        searchJob = lifecycleScope.launch {
            if (!now) delay(350) // wait until typing pauses
            progress.visibility = View.VISIBLE
            val result = runCatching { CardLookup.search(q) }
            progress.visibility = View.INVISIBLE
            result.onSuccess { found ->
                adapter.submit(found)
                if (found.isEmpty()) showMessage("No cards found for “$q”.", showManual = linkCard == null)
                else message.visibility = View.GONE
            }
            result.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                adapter.submit(emptyList())
                showMessage("Couldn't search. Check your internet connection.", showManual = linkCard == null)
            }
        }
    }

    private fun showMessage(text: String, showManual: Boolean) {
        message.visibility = View.VISIBLE
        messageText.text = text
        manual.visibility = if (showManual) View.VISIBLE else View.GONE
    }

    private fun onPick(p: Printing) {
        val link = linkCard
        if (link != null) confirmLink(link, p) else showAddSheet(p)
    }

    private fun confirmLink(card: Card, p: Printing) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Use this printing?")
            .setMessage("${p.name}\n${p.code} · ${p.setName} · ${p.rarity}\n\nYour quantity, condition and paid price stay the same.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use it") { _, _ ->
                lifecycleScope.launch {
                    dao.upsert(CardLookup.link(card, p, usdRate))
                    finish()
                }
            }
            .show()
    }

    private fun showAddSheet(p: Printing) {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_add, null)
        sheet.setContentView(v)

        CardImages.load(v.findViewById(R.id.image), p.imageUrl)
        v.findViewById<TextView>(R.id.name).text = p.name
        v.findViewById<TextView>(R.id.meta).text = "${p.code} · ${p.setName}\n${p.rarity}"
        v.findViewById<TextView>(R.id.price).text = adapter.priceLabel(p)?.let { "$it each · TCGplayer" }
            ?: "No TCGplayer price yet"
        val firstEdition = v.findViewById<MaterialSwitch>(R.id.firstEdition)
        val condition = v.findViewById<MaterialAutoCompleteTextView>(R.id.condition)
        condition.setSimpleItems(CardForm.CONDITIONS.toTypedArray())
        condition.setText("Near Mint", false)
        val paid = v.findViewById<TextInputEditText>(R.id.paid)
        v.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.paidLayout).prefixText =
            Money.symbol(currency).trim()
        val owned = v.findViewById<TextView>(R.id.owned)
        val qtyView = v.findViewById<TextView>(R.id.quantity)
        val minus = v.findViewById<MaterialButton>(R.id.minus)
        var qty = 1
        fun update() {
            qtyView.text = qty.toString()
            minus.isEnabled = qty > 1
            val rarity = CardLookup.rarityLabel(p.rarity, firstEdition.isChecked)
            val have = CardLookup.findOwned(cards, p.code, rarity, wishlist = false)
            val wanted = CardLookup.findOwned(cards, p.code, rarity, wishlist = true)
            owned.text = when {
                have != null -> "You already own ×${have.quantity}. Adding more updates that card."
                wanted != null -> "This printing is on your wishlist."
                else -> ""
            }
            owned.visibility = if (owned.text.isEmpty()) View.GONE else View.VISIBLE
        }
        minus.setOnClickListener { qty--; update() }
        v.findViewById<MaterialButton>(R.id.plus).setOnClickListener { qty++; update() }
        firstEdition.setOnCheckedChangeListener { _, _ -> update() }
        update()

        val addCollection = v.findViewById<MaterialButton>(R.id.addCollection)
        val addWishlist = v.findViewById<MaterialButton>(R.id.addWishlist)
        if (defaultWishlist) {
            // Coming from the wishlist: make that the main action.
            addCollection.text = "Collection"
            addWishlist.text = "Add to wishlist"
        }
        fun add(wishlist: Boolean) {
            val paidCents = paid.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(CollectionJson::cents)
            lifecycleScope.launch {
                val card = CardLookup.toCard(
                    p, firstEdition.isChecked, wishlist, cards, usdRate, currency,
                    quantity = qty, condition = condition.text.toString(), paidPerUnitCents = paidCents,
                )
                dao.upsert(card)
                cards = dao.all()
                adapter.owned = cards
                sheet.dismiss()
                val where = if (wishlist) "wishlist" else "collection"
                Snackbar.make(findViewById(R.id.root), "Added ${card.name} to your $where (×${card.quantity})", Snackbar.LENGTH_LONG)
                    .setAction("Done") { finish() }
                    .show()
            }
        }
        addCollection.setOnClickListener { add(wishlist = false) }
        addWishlist.setOnClickListener { add(wishlist = true) }
        sheet.show()
    }

    companion object {
        const val EXTRA_WISHLIST = "wishlist"
        /** Link this existing card to a TCGplayer printing instead of adding a new one. */
        const val EXTRA_LINK_ID = "link"
    }
}

/** Search results: one row per printing, with its TCGplayer price and whether you own it. */
class PrintingAdapter(private val onClick: (Printing) -> Unit) : RecyclerView.Adapter<PrintingAdapter.Holder>() {
    private var items: List<Printing> = emptyList()
    private var usdRate: Double? = null
    private var currency = "ZAR"

    var owned: List<Card> = emptyList()
        set(v) { field = v; notifyItemRangeChanged(0, itemCount) }

    fun submit(list: List<Printing>) {
        items = list
        notifyDataSetChanged()
    }

    fun setPricing(rate: Double?, currency: String) {
        usdRate = rate
        this.currency = currency
        notifyItemRangeChanged(0, itemCount)
    }

    fun priceLabel(p: Printing): String? {
        val usd = p.priceUsd ?: return null
        return usdRate?.let { Money.format((usd * it * 100).toLong(), currency) } ?: "$%.2f".format(usd)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_printing, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val image: android.widget.ImageView = view.findViewById(R.id.image)
        private val name: TextView = view.findViewById(R.id.name)
        private val meta: TextView = view.findViewById(R.id.meta)
        private val rarity: TextView = view.findViewById(R.id.rarity)
        private val price: TextView = view.findViewById(R.id.price)
        private val ownedView: TextView = view.findViewById(R.id.owned)

        fun bind(p: Printing) {
            CardImages.load(image, p.imageUrl)
            name.text = p.name
            meta.text = "${p.code} · ${p.setName}"
            rarity.text = p.rarity
            price.text = priceLabel(p) ?: "—"
            val count = owned.filter {
                !it.wishlist && it.code.equals(p.code, true) && CardLookup.baseRarity(it.rarity).equals(p.rarity, true)
            }.sumOf { it.quantity }
            ownedView.text = if (count > 0) "Own ×$count" else ""
            ownedView.visibility = if (count > 0) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onClick(p) }
        }
    }
}
