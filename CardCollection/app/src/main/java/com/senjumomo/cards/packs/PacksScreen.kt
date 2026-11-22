package com.senjumomo.cards.packs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.senjumomo.cards.R
import com.senjumomo.cards.data.Card
import com.senjumomo.cards.data.Dates

/** The Packs tab: every booster set, the ones you own cards from first. Tap one to open a pack. */
class PacksScreen(
    root: View,
    private val onOpen: (CardSet) -> Unit,
    private val onRetry: () -> Unit,
) {
    private val list: RecyclerView = root.findViewById(R.id.packList)
    private val message: View = root.findViewById(R.id.packMessage)
    private val progress: View = root.findViewById(R.id.packProgress)
    private val messageText: TextView = root.findViewById(R.id.packMessageText)
    private val retry: MaterialButton = root.findViewById(R.id.packRetry)
    private val adapter = Adapter()

    private var sets: List<CardSet> = emptyList()
    private var owned: Map<String, Int> = emptyMap()
    private var query = ""

    val loaded get() = sets.isNotEmpty()

    init {
        val span = if (root.resources.configuration.screenWidthDp >= 600) 5 else 3
        list.layoutManager = GridLayoutManager(root.context, span)
        list.adapter = adapter
        retry.setOnClickListener { onRetry() }
    }

    fun showLoading() {
        message.visibility = View.VISIBLE; progress.visibility = View.VISIBLE
        retry.visibility = View.GONE; messageText.text = "Loading sets…"
    }

    fun showError() {
        message.visibility = View.VISIBLE; progress.visibility = View.GONE
        retry.visibility = View.VISIBLE; messageText.text = "Couldn't load the sets. Check your internet connection."
    }

    fun setSets(sets: List<CardSet>) { this.sets = sets; refresh() }

    /** Count of cards you own per set, so those sets come first. */
    fun setCollection(cards: List<Card>) {
        owned = cards.filter { !it.wishlist }.groupingBy { it.setName.lowercase() }.fold(0) { a, c -> a + c.quantity }
        refresh()
    }

    fun filter(text: String) { query = text.trim().lowercase(); refresh() }

    private fun refresh() {
        if (sets.isEmpty()) return
        message.visibility = View.GONE
        val shown = sets.filter { query.isEmpty() || it.name.lowercase().contains(query) || it.code.lowercase().contains(query) }
            .sortedWith(compareByDescending<CardSet> { (owned[it.name.lowercase()] ?: 0) > 0 }.thenByDescending { it.date })
        adapter.submit(shown)
        if (shown.isEmpty()) {
            message.visibility = View.VISIBLE; progress.visibility = View.GONE; retry.visibility = View.GONE
            messageText.text = "No sets match “$query”."
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.H>() {
        private var items: List<CardSet> = emptyList()
        fun submit(list: List<CardSet>) { items = list; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            H(LayoutInflater.from(parent.context).inflate(R.layout.item_pack, parent, false))
        override fun onBindViewHolder(h: H, position: Int) = h.bind(items[position])

        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            fun bind(s: CardSet) {
                val image = itemView.findViewById<ImageView>(R.id.packImage)
                val tile = itemView.findViewById<TextView>(R.id.packCodeTile)
                if (s.imageUrl != null) {
                    tile.visibility = View.GONE
                    image.visibility = View.VISIBLE
                    image.load(s.imageUrl) { crossfade(true) }
                } else {
                    image.visibility = View.GONE
                    tile.visibility = View.VISIBLE
                    tile.text = s.code
                }
                itemView.findViewById<TextView>(R.id.packName).text = s.name
                itemView.findViewById<TextView>(R.id.packDate).text =
                    if (s.date.isNotBlank()) "${Dates.label(s.date)} · ${s.cardCount} cards" else "${s.cardCount} cards"
                val count = owned[s.name.lowercase()] ?: 0
                itemView.findViewById<TextView>(R.id.packOwned).apply {
                    visibility = if (count > 0) View.VISIBLE else View.GONE
                    text = "You own $count"
                }
                itemView.setOnClickListener { onOpen(s) }
            }
        }
    }
}
