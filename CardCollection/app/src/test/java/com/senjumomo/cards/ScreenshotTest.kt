package com.senjumomo.cards

import android.view.View
import androidx.core.view.allViews
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputLayout
import com.senjumomo.cards.data.Card
import com.senjumomo.cards.ui.CardAdapter
import com.senjumomo.cards.ui.CardForm
import com.senjumomo.cards.ui.Filter
import com.senjumomo.cards.ui.Row
import com.senjumomo.cards.ui.Summary
import org.junit.Rule
import org.junit.Test

class ScreenshotTest {
    private val device = DeviceConfig.PIXEL_6.copy(softButtons = false)

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = device, theme = "Theme.Cards", maxPercentDifference = 0.1)

    private fun inflate(layout: Int): View = paparazzi.inflate<View>(layout).also { root ->
        // Floating-label animations don't run in the renderer; jump straight to the end state.
        root.allViews.filterIsInstance<TextInputLayout>().forEach { it.isHintAnimationEnabled = false }
    }

    private fun main(cards: List<Card>, filter: Filter = Filter(), grid: Boolean = false, wishlist: Boolean = false): View {
        val root = inflate(R.layout.activity_main)
        val shown = filter.apply(cards)
        root.findViewById<RecyclerView>(R.id.list).apply {
            val a = CardAdapter {}.apply {
                this.grid = grid
                submit(listOf(Row.Header(Summary.of(shown), filter.isActive, wishlist, "2026-11-22T10:00:00.000")) + shown.map { Row.Item(it) })
            }
            layoutManager = if (grid) androidx.recyclerview.widget.GridLayoutManager(context, 3).apply {
                spanSizeLookup = a.spanLookup(3)
            } else LinearLayoutManager(context)
            if (grid) setPadding((12 * resources.displayMetrics.density).toInt(), paddingTop, (12 * resources.displayMetrics.density).toInt(), paddingBottom)
            adapter = a
        }
        if (wishlist) {
            root.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.tab_wishlist
            root.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).title = "Wishlist"
            root.findViewById<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.add).text = "Add to wishlist"
        }
        filter.set?.let { root.findViewById<Chip>(R.id.chipSet).apply { text = it; isChecked = true } }
        return root
    }

    @Test fun collection() = paparazzi.snapshot(main(Samples.cards))

    @Test fun filtered() = paparazzi.snapshot(main(Samples.cards, Filter(set = "Maze of Memories")))

    @Test fun empty() {
        val root = inflate(R.layout.activity_main)
        root.findViewById<View>(R.id.empty).visibility = View.VISIBLE
        root.findViewById<android.widget.TextView>(R.id.emptyTitle).text = "Your binder is empty"
        root.findViewById<android.widget.TextView>(R.id.emptyBody).text =
            "Import your collection export, or add cards one at a time."
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.emptyAction).apply {
            text = "Import collection"; setIconResource(R.drawable.ic_import)
        }
        paparazzi.snapshot(root)
    }

    @Test fun edit() {
        paparazzi.unsafeUpdateConfig(device.copy(screenHeight = 3000))
        val root = inflate(R.layout.activity_edit)
        root.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).apply {
            title = "Edit card"
        }
        CardForm(root).bind(Samples.cards[3], emptyList(), emptyList())
        paparazzi.snapshot(root)
    }

    @Test fun grid() = paparazzi.snapshot(main(Samples.cards, grid = true))

    @Test fun wishlist() = paparazzi.snapshot(main(Samples.cards.take(3).map { it.copy(wishlist = true, paidPerUnitCents = null) }, wishlist = true))

    @Test fun stats() {
        paparazzi.unsafeUpdateConfig(device.copy(screenHeight = 3600))
        val root = inflate(R.layout.activity_main)
        root.findViewById<View>(R.id.listPage).visibility = View.GONE
        root.findViewById<View>(R.id.filterBar).visibility = View.GONE
        root.findViewById<View>(R.id.statsPage).visibility = View.VISIBLE
        root.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).title = "Stats"
        root.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.tab_stats
        com.senjumomo.cards.ui.StatsScreen(root, {}, {}, {}).show(Samples.cards)
        paparazzi.snapshot(root)
    }

    /** Made-up pull history: 14 packs from two sets, a few big pulls. */
    private fun pullHistory(): com.senjumomo.cards.packs.PullSummary {
        val openings = (1L..14L).map {
            com.senjumomo.cards.packs.PackOpening(it, if (it <= 10) "ROTA" else "MAZE", if (it <= 10) "Rage of the Abyss" else "Maze of Memories",
                it, "ZAR", 2400, 1900, expectedUltra = 0.29, expectedSecret = 0.125)
        }
        fun p(o: Long, set: String, code: String, name: String, rarity: String, cents: Long) = com.senjumomo.cards.packs.Pull(
            0, o, set, code, name, rarity, com.senjumomo.cards.packs.Tier.of(rarity).ordinal, 1, cents, false)
        val pulls = (1..63).map { p(1, "Rage of the Abyss", "ROTA-EN%03d".format(it), "Card $it", "Common", 30) } +
            (1..21).map { p(11, "Maze of Memories", "MAZE-EN%03d".format(it), "Card $it", "Common", 20) } + listOf(
                p(3, "Rage of the Abyss", "ROTA-EN001", "Abyss Dragon", "Quarter Century Secret Rare", 21450),
                p(5, "Rage of the Abyss", "ROTA-EN010", "Tidal Knight", "Secret Rare", 8900),
                p(7, "Rage of the Abyss", "ROTA-EN020", "Coral Sprite", "Ultra Rare", 2100),
                p(12, "Maze of Memories", "MAZE-EN005", "Maze Warden", "Ultra Rare", 1500),
                p(9, "Rage of the Abyss", "ROTA-EN030", "Deep Current", "Super Rare", 400),
            )
        return com.senjumomo.cards.packs.PullSummary.of(openings, pulls)
    }

    @Test fun packs() {
        paparazzi.unsafeUpdateConfig(device.copy(screenHeight = 2800))
        val root = inflate(R.layout.activity_main)
        root.findViewById<View>(R.id.listPage).visibility = View.GONE
        root.findViewById<View>(R.id.filterBar).visibility = View.GONE
        root.findViewById<View>(R.id.packsPage).visibility = View.VISIBLE
        root.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).title = "Open packs"
        root.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.tab_packs
        val screen = com.senjumomo.cards.packs.PacksScreen(root, {}, {})
        screen.setSets(listOf(
            com.senjumomo.cards.packs.CardSet("ROTA", "Rage of the Abyss", 101, "2024-10-10", null),
            com.senjumomo.cards.packs.CardSet("MAZE", "Maze of Memories", 100, "2024-01-25", null),
            com.senjumomo.cards.packs.CardSet("SUDA", "Supreme Darkness", 101, "2024-08-01", null),
            com.senjumomo.cards.packs.CardSet("LEDE", "Legacy of Destruction", 101, "2024-04-25", null),
            com.senjumomo.cards.packs.CardSet("PHNI", "Phantom Nightmare", 101, "2023-11-02", null),
            com.senjumomo.cards.packs.CardSet("AGOV", "Age of Overlord", 101, "2023-07-27", null),
        ))
        screen.setHistory(pullHistory())
        paparazzi.snapshot(root)
    }

    @Test fun packsEmpty() {
        val root = inflate(R.layout.activity_main)
        root.findViewById<View>(R.id.listPage).visibility = View.GONE
        root.findViewById<View>(R.id.filterBar).visibility = View.GONE
        root.findViewById<View>(R.id.packsPage).visibility = View.VISIBLE
        root.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).title = "Open packs"
        root.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.tab_packs
        com.senjumomo.cards.packs.PacksScreen(root, {}, {}).setSets(listOf(
            com.senjumomo.cards.packs.CardSet("ROTA", "Rage of the Abyss", 101, "2024-10-10", null),
            com.senjumomo.cards.packs.CardSet("MAZE", "Maze of Memories", 100, "2024-01-25", null),
            com.senjumomo.cards.packs.CardSet("SUDA", "Supreme Darkness", 101, "2024-08-01", null),
        ))
        paparazzi.snapshot(root)
    }

    @Test fun binder() {
        val root = inflate(R.layout.activity_binder)
        root.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).title = "Rage of the Abyss"
        root.findViewById<View>(R.id.binderMessage).visibility = View.GONE
        val set = (1..12).map { com.senjumomo.cards.packs.SetCard("Card $it", "ROTA-EN%03d".format(it), "Common", it.toLong(), null, "Effect Monster") }
        val pulls = listOf(1, 2, 2, 4, 5, 7, 9, 10, 10, 10).mapIndexed { i, n ->
            com.senjumomo.cards.packs.Pull(0, i.toLong(), "Rage of the Abyss", "ROTA-EN%03d".format(n), "Card $n",
                when (n) { 2 -> "Quarter Century Secret Rare"; 7 -> "Ultra Rare"; 9 -> "Super Rare"; else -> "Common" },
                com.senjumomo.cards.packs.Tier.of(when (n) { 2 -> "Quarter Century Secret Rare"; 7 -> "Ultra Rare"; 9 -> "Super Rare"; else -> "Common" }).ordinal,
                n.toLong(), 100, false)
        }
        val entries = com.senjumomo.cards.packs.BinderEntry.build(set, pulls)
        val got = entries.count { it.pulled }
        root.findViewById<android.widget.TextView>(R.id.binderCount).text = "$got of ${entries.size} cards"
        root.findViewById<android.widget.TextView>(R.id.binderPercent).text = "${got * 100 / entries.size}%"
        root.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.binderBar).apply {
            // Progress bars only draw progress set once attached (as the app does after loading).
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { max = entries.size; setProgressCompat(got, false) }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }
        root.findViewById<android.widget.TextView>(R.id.binderStats).text = "10 packs opened · R241.50 pulled · only 5 to go!"
        root.findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, 3)
            adapter = com.senjumomo.cards.packs.BinderGridAdapter {}.also { it.submit(entries) }
        }
        paparazzi.snapshot(root)
    }

    @Test fun detail() {
        paparazzi.unsafeUpdateConfig(device.copy(screenHeight = 3400))
        val root = inflate(R.layout.activity_detail)
        root.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).title = ""
        com.senjumomo.cards.ui.CardDetailView(root).bind(Samples.cards[3].copy(priceUpdatedAt = "2026-11-22T10:00:00.000"))
        paparazzi.snapshot(root)
    }

    @Test fun detailWishlistUnlinked() {
        val root = inflate(R.layout.activity_detail)
        com.senjumomo.cards.ui.CardDetailView(root).bind(Samples.cards[4].copy(wishlist = true, tcgplayerId = null))
        paparazzi.snapshot(root)
    }

    @Test fun addSearch() {
        val root = inflate(R.layout.activity_add)
        root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search).setText("ash blos")
        root.findViewById<View>(R.id.message).visibility = View.GONE
        root.findViewById<RecyclerView>(R.id.results).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = com.senjumomo.cards.ui.PrintingAdapter {}.apply {
                owned = Samples.cards
                setPricing(17.53, "ZAR")
                submit(listOf(
                    com.senjumomo.cards.data.Printing("Ash Blossom & Joyous Spring", "Maze of Memories", "MAZE-EN058", "Secret Rare", 10.81, "1", null),
                    com.senjumomo.cards.data.Printing("Ash Blossom & Joyous Spring", "Rarity Collection 5", "RA05-EN091", "Ultra Rare", 3.23, "2", null),
                    com.senjumomo.cards.data.Printing("Ash Blossom & Joyous Spring", "Legendary Modern Decks 2026", "L26D-ENM02", "Common", 1.26, "3", null),
                    com.senjumomo.cards.data.Printing("Ash Blossom & Joyous Spring", "Legendary Arc-V Decks", "LAVD-ENO08", "Common", 0.46, "4", null),
                ))
            }
        }
        paparazzi.snapshot(root)
    }

    @Test fun addSheet() {
        val v = inflate(R.layout.sheet_add)
        v.findViewById<android.widget.TextView>(R.id.name).text = "Ash Blossom & Joyous Spring"
        v.findViewById<android.widget.TextView>(R.id.meta).text = "RA05-EN091 · Rarity Collection 5\nUltra Rare"
        v.findViewById<android.widget.TextView>(R.id.price).text = "R56.62 each · TCGplayer"
        v.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.condition).setText("Near Mint", false)
        v.findViewById<View>(R.id.owned).visibility = View.GONE
        paparazzi.snapshot(v)
    }

    @Test fun collectionDark() {
        paparazzi.unsafeUpdateConfig(device.copy(nightMode = NightMode.NIGHT))
        paparazzi.snapshot(main(Samples.cards))
    }
}
