package com.senjumomo.cards.packs

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.senjumomo.cards.R
import com.senjumomo.cards.holo.HoloViewerActivity
import com.senjumomo.cards.ui.Money
import com.senjumomo.cards.ui.padForSystemBars
import kotlinx.coroutines.launch

/**
 * A set's pull binder: every card in the set, the ones you've pulled in the simulator in colour (with
 * the rarest version and how many), the rest greyed out. Simulator only, separate from the collection.
 */
class PullBinderActivity : AppCompatActivity() {

    private lateinit var set: CardSet
    private var entries: List<BinderEntry> = emptyList()
    private val adapter = BinderGridAdapter { e ->
        if (e.pulled) startActivity(
            Intent(this, HoloViewerActivity::class.java)
                .putExtra(HoloViewerActivity.EXTRA_NAME, e.name)
                .putExtra(HoloViewerActivity.EXTRA_RARITY, e.bestRarity)
                .putExtra(HoloViewerActivity.EXTRA_IMAGE, PackArt.cardSmallUrl(e.artworkId))
        ) else Snackbar.make(findViewById(R.id.root), "Not pulled yet: keep opening ${set.name}!", Snackbar.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_binder)
        set = CardSet(
            code = intent.getStringExtra(EXTRA_CODE).orEmpty(),
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            cardCount = intent.getIntExtra(EXTRA_COUNT, 0),
            date = intent.getStringExtra(EXTRA_DATE).orEmpty(),
            imageUrl = null,
        )
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = set.name
            setNavigationOnClickListener { finish() }
        }
        val grid = findViewById<RecyclerView>(R.id.grid)
        val span = if (resources.configuration.screenWidthDp >= 600) 6 else 3
        grid.layoutManager = GridLayoutManager(this, span)
        grid.adapter = adapter
        grid.padForSystemBars()
        findViewById<ChipGroup>(R.id.binderFilter).setOnCheckedStateChangeListener { _, _ -> show() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val cards = runCatching { PackRepository(this@PullBinderActivity).cards(set) }.getOrDefault(emptyList())
            val pulls = PullDatabase.get(this@PullBinderActivity).pulls().pullsFrom(set.name)
            entries = BinderEntry.build(cards, pulls)
            if (entries.isEmpty()) {
                findViewById<View>(R.id.binderProgress).visibility = View.GONE
                findViewById<TextView>(R.id.binderMessageText).text = "Couldn't load this set. Check your internet connection."
                return@launch
            }
            findViewById<View>(R.id.binderMessage).visibility = View.GONE
            val got = entries.count { it.pulled }
            findViewById<TextView>(R.id.binderCount).text = "$got of ${entries.size} cards"
            findViewById<TextView>(R.id.binderPercent).text = "${got * 100 / entries.size}%"
            findViewById<LinearProgressIndicator>(R.id.binderBar).apply { max = entries.size; setProgressCompat(got, true) }
            val packs = pulls.map { it.openingId }.distinct().size
            val value = pulls.mapNotNull { it.valueCents }.sum()
            val currency = com.senjumomo.cards.data.CardDatabase.get(this@PullBinderActivity).cards().all()
                .firstOrNull()?.currency ?: "ZAR"
            findViewById<TextView>(R.id.binderStats).text = buildString {
                append("$packs pack${if (packs == 1) "" else "s"} opened")
                if (value > 0) append(" · ${Money.format(value, currency)} pulled")
                val missing = entries.size - got
                if (missing in 1..5) append(" · only $missing to go!")
                if (missing == 0) append(" · set complete!")
            }
            show()
        }
    }

    private fun show() {
        val shown = when (findViewById<ChipGroup>(R.id.binderFilter).checkedChipId) {
            R.id.filterPulled -> entries.filter { it.pulled }
            R.id.filterMissing -> entries.filter { !it.pulled }
            else -> entries
        }
        adapter.submit(shown)
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_NAME = "name"
        const val EXTRA_COUNT = "count"
        const val EXTRA_DATE = "date"

        fun intent(context: Context, set: CardSet) = Intent(context, PullBinderActivity::class.java)
            .putExtra(EXTRA_CODE, set.code).putExtra(EXTRA_NAME, set.name)
            .putExtra(EXTRA_COUNT, set.cardCount).putExtra(EXTRA_DATE, set.date)
    }
}

/** The binder grid: pulled cards in colour with their best rarity, missing ones as faded grey ghosts. */
class BinderGridAdapter(private val onTap: (BinderEntry) -> Unit) : RecyclerView.Adapter<BinderGridAdapter.H>() {
    private var items: List<BinderEntry> = emptyList()
    fun submit(list: List<BinderEntry>) { items = list; notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        H(LayoutInflater.from(parent.context).inflate(R.layout.item_binder_card, parent, false))
    override fun onBindViewHolder(h: H, position: Int) = h.bind(items[position])

    inner class H(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(e: BinderEntry) {
            val image = itemView.findViewById<ImageView>(R.id.image)
            image.setImageResource(R.drawable.card_placeholder) // at once, not when the loader gets to it
            image.load(PackArt.cardSmallUrl(e.artworkId)) { placeholder(R.drawable.card_placeholder) }
            image.colorFilter = if (e.pulled) null else ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            image.alpha = if (e.pulled) 1f else 0.32f
            itemView.findViewById<TextView>(R.id.name).apply {
                text = e.name
                alpha = if (e.pulled) 1f else 0.6f
            }
            itemView.findViewById<TextView>(R.id.copies).apply {
                visibility = if (e.count > 1) View.VISIBLE else View.GONE
                text = "×${e.count}"
            }
            val res = itemView.resources
            val night = (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            itemView.findViewById<TextView>(R.id.rarity).apply {
                if (e.pulled) {
                    text = CardRarity.short(e.bestRarity.orEmpty())
                    setTextColor(PackOpenActivity.tierColorOn(e.bestTier ?: Tier.COMMON, light = !night))
                } else {
                    text = e.code
                    setTextColor(currentTextColor and 0x00FFFFFF or 0x88000000.toInt())
                }
            }
            itemView.contentDescription = if (e.pulled) "${e.name}, ${e.bestRarity}, pulled ${e.count}" else "${e.name}, not pulled yet"
            itemView.setOnClickListener { onTap(e) }
        }
    }
}

