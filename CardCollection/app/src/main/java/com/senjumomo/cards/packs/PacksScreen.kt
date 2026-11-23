package com.senjumomo.cards.packs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.senjumomo.cards.R
import com.senjumomo.cards.data.Card
import com.senjumomo.cards.ui.Money

/**
 * The Packs tab: your pulls (stats, luck, best pulls, set binders) on top, then every booster set,
 * the ones you own cards from first. Tap a set to open a pack.
 */
class PacksScreen(
    root: View,
    private val onOpen: (CardSet) -> Unit,
    private val onRetry: () -> Unit,
    private val onBinder: (CardSet) -> Unit = {},
    private val onPull: (Pull) -> Unit = {},
) {
    private val list: RecyclerView = root.findViewById(R.id.packList)
    private val message: View = root.findViewById(R.id.packMessage)
    private val progress: View = root.findViewById(R.id.packProgress)
    private val messageText: TextView = root.findViewById(R.id.packMessageText)
    private val retry: MaterialButton = root.findViewById(R.id.packRetry)
    private val header = HeaderAdapter()
    private val adapter = Adapter()

    private var sets: List<CardSet> = emptyList()
    private var owned: Map<String, Int> = emptyMap()
    private var history: PullSummary = PullSummary.EMPTY
    private var query = ""

    val loaded get() = sets.isNotEmpty()

    init {
        val span = if (root.resources.configuration.screenWidthDp >= 600) 5 else 3
        list.layoutManager = GridLayoutManager(root.context, span).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (position < header.itemCount) span else 1
            }
        }
        list.adapter = ConcatAdapter(header, adapter)
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

    fun setSets(sets: List<CardSet>) { this.sets = sets; refresh(); header.notifyItemChanged(0) }

    /** Count of cards you own per set, so those sets come first. */
    fun setCollection(cards: List<Card>) {
        owned = cards.filter { !it.wishlist }.groupingBy { it.setName.lowercase() }.fold(0) { a, c -> a + c.quantity }
        refresh()
    }

    /** What you've pulled in the simulator. */
    fun setHistory(summary: PullSummary) {
        history = summary
        header.notifyItemChanged(0)
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    fun filter(text: String) { query = text.trim().lowercase(); refresh() }

    /** The set by name, from the set list (or a stand-in with what the history knows). */
    private fun setFor(name: String, code: String) =
        sets.firstOrNull { it.name.equals(name, true) } ?: CardSet(code, name, 0, "", null)

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

    // --- "Your pulls" header -----------------------------------------------------------------------

    private inner class HeaderAdapter : RecyclerView.Adapter<HeaderAdapter.H>() {
        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            H(LayoutInflater.from(parent.context).inflate(R.layout.view_pull_header, parent, false))
        override fun onBindViewHolder(h: H, position: Int) = h.bind(history)

        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            private val best: RecyclerView = v.findViewById(R.id.bestPulls)
            private val binders: RecyclerView = v.findViewById(R.id.setProgress)
            private val bestAdapter = BestAdapter()
            private val binderAdapter = BinderAdapter()

            init {
                best.layoutManager = LinearLayoutManager(v.context, RecyclerView.HORIZONTAL, false)
                best.adapter = bestAdapter
                binders.layoutManager = LinearLayoutManager(v.context, RecyclerView.HORIZONTAL, false)
                binders.adapter = binderAdapter
            }

            fun bind(s: PullSummary) {
                val empty = s.packs == 0
                itemView.findViewById<View>(R.id.pullsEmpty).visibility = if (empty) View.VISIBLE else View.GONE
                itemView.findViewById<View>(R.id.pullsContent).visibility = if (empty) View.GONE else View.VISIBLE
                if (empty) return
                itemView.findViewById<TextView>(R.id.statPacks).text = s.packs.toString()
                itemView.findViewById<TextView>(R.id.statValue).text = Money.format(s.valueCents, s.currency).substringBefore('.')
                val luck = s.luck()
                itemView.findViewById<TextView>(R.id.statLuck).text = luck?.let { "%.1f×".format(it.first) } ?: "—"
                itemView.findViewById<TextView>(R.id.luckText).text = when {
                    luck == null -> "Open a few more packs to see how lucky you are."
                    luck.first >= 1.15 -> "Luckier than the odds: ${luck.second}."
                    luck.first <= 0.85 -> "Unluckier than the odds so far: ${luck.second}."
                    else -> "Right on the odds: ${luck.second}."
                }
                bestAdapter.items = s.best
                bestAdapter.currency = s.currency
                bestAdapter.notifyDataSetChanged()
                binderAdapter.items = s.recentSets.map { (name, code) -> setFor(name, code) }
                binderAdapter.notifyDataSetChanged()
            }
        }
    }

    private inner class BestAdapter : RecyclerView.Adapter<BestAdapter.H>() {
        var items: List<Pull> = emptyList()
        var currency = "ZAR"
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            H(LayoutInflater.from(parent.context).inflate(R.layout.item_best_pull, parent, false))
        override fun onBindViewHolder(h: H, position: Int) = h.bind(items[position])

        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            fun bind(p: Pull) {
                itemView.findViewById<ShineImageView>(R.id.image).apply {
                    setImageResource(R.drawable.card_placeholder) // at once, not when the loader gets to it
                    load(PackArt.cardSmallUrl(p.artworkId)) { placeholder(R.drawable.card_placeholder) }
                    shine = p.tier >= Tier.SUPER.ordinal
                }
                itemView.findViewById<TextView>(R.id.rarity).apply {
                    text = CardRarity.short(p.rarity)
                    val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    setTextColor(PackOpenActivity.tierColorOn(Tier.entries[p.tier], light = !night))
                }
                itemView.findViewById<TextView>(R.id.value).text = p.valueCents?.let { Money.format(it, currency) } ?: ""
                itemView.contentDescription = "${p.name}, ${p.rarity}"
                itemView.setOnClickListener { onPull(p) }
            }
        }
    }

    private inner class BinderAdapter : RecyclerView.Adapter<BinderAdapter.H>() {
        var items: List<CardSet> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            H(LayoutInflater.from(parent.context).inflate(R.layout.item_set_progress, parent, false))
        override fun onBindViewHolder(h: H, position: Int) = h.bind(items[position])

        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            fun bind(s: CardSet) {
                val got = history.progress[s.name.lowercase()] ?: 0
                itemView.findViewById<TextView>(R.id.name).text = s.name
                itemView.findViewById<TextView>(R.id.count).text =
                    if (s.cardCount > 0) "$got of ${s.cardCount} cards" else "$got cards"
                itemView.findViewById<LinearProgressIndicator>(R.id.bar).apply {
                    max = s.cardCount.coerceAtLeast(got).coerceAtLeast(1)
                    progress = got
                }
                itemView.setOnClickListener { onBinder(s) }
            }
        }
    }

    // --- Set grid -----------------------------------------------------------------------------------

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
                val backdrop = itemView.findViewById<ImageView>(R.id.packBackdrop)
                val tile = itemView.findViewById<TextView>(R.id.packCodeTile)
                if (s.imageUrl != null) {
                    tile.visibility = View.GONE
                    image.visibility = View.VISIBLE
                    backdrop.visibility = View.VISIBLE
                    image.setImageDrawable(null)
                    backdrop.setImageDrawable(null)
                    image.load(s.imageUrl) { crossfade(true) }
                    // The same picture, blurred, fills the frame behind it (boxes and odd shapes sit neatly).
                    backdrop.load(s.imageUrl) { crossfade(true) }
                    if (android.os.Build.VERSION.SDK_INT >= 31) backdrop.setRenderEffect(
                        android.graphics.RenderEffect.createBlurEffect(28f, 28f, android.graphics.Shader.TileMode.CLAMP)
                    )
                } else {
                    image.visibility = View.GONE
                    backdrop.visibility = View.GONE
                    tile.visibility = View.VISIBLE
                    tile.text = s.code
                }
                itemView.findViewById<TextView>(R.id.packName).text = s.name
                itemView.findViewById<TextView>(R.id.packDate).text =
                    if (s.date.length >= 4) "${s.date.take(4)} · ${s.cardCount} cards" else "${s.cardCount} cards"
                val count = owned[s.name.lowercase()] ?: 0
                itemView.findViewById<TextView>(R.id.packOwned).apply {
                    visibility = if (count > 0) View.VISIBLE else View.GONE
                    text = "✓ $count owned"
                }
                val pulled = history.progress[s.name.lowercase()] ?: 0
                itemView.findViewById<View>(R.id.packProgressRow).visibility = if (pulled > 0) View.VISIBLE else View.GONE
                if (pulled > 0) {
                    itemView.findViewById<LinearProgressIndicator>(R.id.packProgress).apply {
                        max = s.cardCount.coerceAtLeast(pulled).coerceAtLeast(1); progress = pulled
                    }
                    itemView.findViewById<TextView>(R.id.packProgressText).text = "$pulled/${s.cardCount}"
                }
                itemView.setOnClickListener { onOpen(s) }
            }
        }
    }
}

/** Short rarity names for tight spaces ("1st Edition Quarter Century Secret Rare" → "QC Secret"). */
object CardRarity {
    fun short(rarity: String): String {
        val r = com.senjumomo.cards.data.CardLookup.baseRarity(rarity)
        return when {
            r.startsWith("Quarter Century", true) -> "QC Secret"
            r.startsWith("Prismatic", true) -> "Prismatic"
            r.startsWith("Platinum", true) -> "Platinum"
            else -> r.removeSuffix(" Rare").ifEmpty { r }
        }
    }
}
