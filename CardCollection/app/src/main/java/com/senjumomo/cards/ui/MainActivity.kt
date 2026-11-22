package com.senjumomo.cards.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.senjumomo.cards.R
import com.senjumomo.cards.data.Card
import com.senjumomo.cards.data.CardDao
import com.senjumomo.cards.data.CardDatabase
import com.senjumomo.cards.data.CollectionJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var dao: CardDao
    private lateinit var adapter: CardAdapter
    private lateinit var root: View
    private lateinit var list: RecyclerView
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var search: TextInputEditText
    private lateinit var chipSort: Chip
    private lateinit var chipSet: Chip
    private lateinit var chipRarity: Chip

    private lateinit var appBar: com.google.android.material.appbar.AppBarLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: com.google.android.material.bottomnavigation.BottomNavigationView
    private lateinit var stats: StatsScreen
    private lateinit var prefs: android.content.SharedPreferences

    /** Everything in the database (collection + wishlist). */
    private var allCards: List<Card> = emptyList()
    /** Cards on the current tab. */
    private var cards: List<Card> = emptyList()
    private var loaded = false
    private var filter = Filter()
    private var tab = R.id.tab_collection
    private val imageResolver by lazy { com.senjumomo.cards.data.ImageResolver(dao) }
    private var resolvingImages = false

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importFrom)
    }
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let(::exportTo)
        }
    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        CardDetailActivity.takeDeleted()?.let { deleted ->
            snackbar("Deleted ${deleted.name}")
                .setAction("Undo") { lifecycleScope.launch { dao.upsert(deleted) } }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        dao = CardDatabase.get(this).cards()

        root = findViewById(R.id.root)
        appBar = findViewById(R.id.appBar)
        toolbar = findViewById(R.id.toolbar)
        bottomNav = findViewById(R.id.bottomNav)
        list = findViewById(R.id.list)
        fab = findViewById(R.id.add)
        search = findViewById(R.id.search)
        chipSort = findViewById(R.id.chipSort)
        chipSet = findViewById(R.id.chipSet)
        chipRarity = findViewById(R.id.chipRarity)

        // The bottom navigation sits above the system nav bar; only the keyboard needs padding.
        keyboardPadding(list)
        keyboardPadding(findViewById(R.id.statsContent))

        prefs = getSharedPreferences("cards", Context.MODE_PRIVATE)
        filter = filter.copy(sort = runCatching { Sort.valueOf(prefs.getString("sort", "")!!) }.getOrDefault(Sort.NAME))

        adapter = CardAdapter(::openCard)
        list.adapter = adapter
        setGrid(prefs.getBoolean("grid", false))

        stats = StatsScreen(
            findViewById(R.id.statsPage),
            onSet = { showFiltered(set = it) },
            onRarity = { showFiltered(rarity = it) },
            onCard = ::openCard,
        )
        bottomNav.setOnItemSelectedListener { showTab(it.itemId); true }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8) fab.shrink() else if (dy < -8) fab.extend()
            }
        })

        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_scan -> startActivity(Intent(this, ScanActivity::class.java))
                R.id.action_view -> setGrid(!adapter.grid)
                R.id.action_refresh -> confirmRefresh()
                R.id.action_import -> importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                R.id.action_export -> exportLauncher.launch("card-collection-${today()}.json")
            }
            true
        }
        fab.setOnClickListener { chooseHowToAdd() }
        search.doAfterTextChanged { filter = filter.copy(query = it?.toString().orEmpty()); render() }

        listOf(chipSort, chipSet, chipRarity).forEach { chip ->
            chip.setOnCloseIconClickListener { chip.performClick() }
        }
        chipSort.setOnClickListener {
            choose("Sort by", Sort.entries.map { it.label }, Sort.entries.indexOf(filter.sort)) { i ->
                filter = filter.copy(sort = Sort.entries[i])
                prefs.edit().putString("sort", filter.sort.name).apply()
            }
        }
        chipSet.setOnClickListener {
            chooseValue("Set", "All sets", cards.map { it.setName }, filter.set) { filter = filter.copy(set = it) }
        }
        chipRarity.setOnClickListener {
            chooseValue("Rarity", "All rarities", cards.map { it.rarity }, filter.rarity) {
                filter = filter.copy(rarity = it)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.observeAll().collect {
                    allCards = it
                    loaded = true
                    render()
                    resolveImagesOnce()
                }
            }
        }
    }

    private fun render() {
        val wishlist = tab == R.id.tab_wishlist
        cards = allCards.filter { it.wishlist == wishlist }
        stats.show(allCards.filter { !it.wishlist })
        // Drop filters that no longer match anything (e.g. after deleting the last card of a set).
        if (filter.set != null && cards.none { it.setName == filter.set }) filter = filter.copy(set = null)
        if (filter.rarity != null && cards.none { it.rarity == filter.rarity }) filter = filter.copy(rarity = null)

        chipSort.text = "Sort: ${filter.sort.label}"
        chipSort.isChecked = false
        chipSet.text = filter.set ?: "All sets"
        chipSet.isChecked = filter.set != null
        chipRarity.text = filter.rarity ?: "All rarities"
        chipRarity.isChecked = filter.rarity != null

        val shown = filter.apply(cards)
        adapter.submit(
            if (shown.isEmpty()) emptyList()
            else listOf(
                Row.Header(Summary.of(shown), filter.isActive, wishlist, cards.mapNotNull { it.priceUpdatedAt }.maxOrNull())
            ) + shown.map { Row.Item(it) }
        )
        showEmpty(loaded && shown.isEmpty() && tab != R.id.tab_stats)
    }

    private fun showEmpty(show: Boolean) {
        val empty = findViewById<View>(R.id.empty)
        empty.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val title = findViewById<TextView>(R.id.emptyTitle)
        val body = findViewById<TextView>(R.id.emptyBody)
        val action = findViewById<MaterialButton>(R.id.emptyAction)
        if (cards.isEmpty() && tab == R.id.tab_wishlist) {
            title.text = "Your wishlist is empty"
            body.text = "Add cards you're after, then see what it would cost to get them all."
            action.text = "Add a card"
            action.setIconResource(R.drawable.ic_add)
            action.setOnClickListener { chooseHowToAdd() }
        } else if (cards.isEmpty()) {
            title.text = "Your binder is empty"
            body.text = "Import your collection export, or add cards one at a time."
            action.text = "Import collection"
            action.setIconResource(R.drawable.ic_import)
            action.setOnClickListener { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }
        } else {
            title.text = "No matching cards"
            body.text = "Try a different search, set or rarity."
            action.text = "Clear filters"
            action.icon = null
            action.setOnClickListener {
                filter = filter.copy(query = "", set = null, rarity = null)
                search.setText("")
                render()
            }
        }
    }

    private fun showTab(id: Int) {
        tab = id
        val isStats = id == R.id.tab_stats
        findViewById<View>(R.id.listPage).visibility = if (isStats) View.GONE else View.VISIBLE
        findViewById<View>(R.id.statsPage).visibility = if (isStats) View.VISIBLE else View.GONE
        findViewById<View>(R.id.filterBar).visibility = if (isStats) View.GONE else View.VISIBLE
        toolbar.title = when (id) {
            R.id.tab_wishlist -> "Wishlist"
            R.id.tab_stats -> "Stats"
            else -> "My cards"
        }
        toolbar.menu.findItem(R.id.action_view).isVisible = !isStats
        appBar.liftOnScrollTargetViewId = if (isStats) R.id.statsScroll else R.id.list
        fab.text = if (id == R.id.tab_wishlist) "Add to wishlist" else "Add card"
        filter = filter.copy(query = "", set = null, rarity = null)
        search.setText("")
        render()
        list.scrollToPosition(0)
    }

    /** From Stats: jump to the collection filtered to one set or rarity. */
    private fun showFiltered(set: String? = null, rarity: String? = null) {
        bottomNav.selectedItemId = R.id.tab_collection
        filter = filter.copy(set = set, rarity = rarity)
        render()
    }

    private fun setGrid(grid: Boolean) {
        adapter.grid = grid
        prefs.edit().putBoolean("grid", grid).apply()
        val span = if (resources.configuration.screenWidthDp >= 600) 5 else 3
        list.layoutManager = if (grid) {
            androidx.recyclerview.widget.GridLayoutManager(this, span).apply {
                spanSizeLookup = adapter.spanLookup(span)
            }
        } else {
            LinearLayoutManager(this)
        }
        // Grid cards carry their own spacing; trim the list's side padding to match.
        val side = (if (grid) 12 else 16) * resources.displayMetrics.density
        list.setPadding(side.toInt(), list.paddingTop, side.toInt(), list.paddingBottom)
        toolbar.menu.findItem(R.id.action_view)?.apply {
            setIcon(if (grid) R.drawable.ic_list else R.drawable.ic_grid)
            title = if (grid) "List view" else "Grid view"
        }
    }

    private fun chooseHowToAdd() {
        val wishlist = tab == R.id.tab_wishlist
        MaterialAlertDialogBuilder(this)
            .setTitle(if (wishlist) "Add to wishlist" else "Add a card")
            .setItems(arrayOf("Search by name", "Scan with camera")) { _, which ->
                if (which == 0) startActivity(
                    Intent(this, AddCardActivity::class.java).putExtra(AddCardActivity.EXTRA_WISHLIST, wishlist)
                )
                else startActivity(Intent(this, ScanActivity::class.java))
            }
            .show()
    }

    private fun confirmRefresh() {
        val priceable = allCards.count { it.tcgplayerId != null }
        if (priceable == 0) {
            snackbar("None of your cards have a TCGplayer link to price them from").show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Refresh prices?")
            .setMessage(
                "Gets the current TCGplayer market price for $priceable cards and converts it to rand " +
                    "at today's exchange rate. This takes a minute or so."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Refresh") { _, _ -> refreshPrices() }
            .show()
    }

    private fun refreshPrices() {
        val view = layoutInflater.inflate(R.layout.dialog_progress, null)
        val bar = view.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progress)
        val text = view.findViewById<TextView>(R.id.progressText)
        text.text = "Getting exchange rate…"
        var job: kotlinx.coroutines.Job? = null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Refreshing prices")
            .setView(view)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> job?.cancel() }
            .show()
        job = lifecycleScope.launch {
            val result = runCatching {
                com.senjumomo.cards.data.PriceRefresher(dao).refresh(allCards) { done, total ->
                    bar.isIndeterminate = false
                    bar.max = total
                    bar.setProgressCompat(done, true)
                    text.text = "$done of $total cards"
                }
            }
            dialog.dismiss()
            result.onSuccess { r ->
                val currency = allCards.firstOrNull()?.currency ?: "ZAR"
                val change = r.afterCents - r.beforeCents
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Prices updated")
                    .setMessage(buildString {
                        append("Total value: ${Money.format(r.beforeCents, currency)} → ${Money.format(r.afterCents, currency)}")
                        append(" (${Money.signed(change, currency)})\n\n")
                        append("${r.updated} prices changed, ${r.unchanged} the same.")
                        if (r.failed > 0) append("\n${r.failed} couldn't be priced (no TCGplayer market price).")
                        if (r.skipped > 0) append("\n${r.skipped} have no TCGplayer link.")
                    })
                    .setPositiveButton("OK", null)
                    .show()
            }
            result.onFailure {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Couldn't refresh prices")
                    .setMessage("Check your internet connection and try again.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /** Match cards to high-res images in the background (new cards too, as they're added). */
    private fun resolveImagesOnce() {
        if (resolvingImages || imageResolver.pending(allCards).isEmpty()) return
        resolvingImages = true
        lifecycleScope.launch {
            try { imageResolver.resolveMissing(allCards) } finally { resolvingImages = false }
        }
    }

    private fun keyboardPadding(view: View) {
        val base = view.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, base + ime)
            insets
        }
    }

    private fun openCard(card: Card) {
        editLauncher.launch(Intent(this, CardDetailActivity::class.java).putExtra(CardDetailActivity.EXTRA_ID, card.id))
    }

    private fun choose(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { d, which ->
                onPick(which)
                render()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Pick one of the distinct [values] (with counts), or "all". */
    private fun chooseValue(title: String, all: String, values: List<String>, current: String?, onPick: (String?) -> Unit) {
        val counts = values.filter { it.isNotBlank() }.groupingBy { it }.eachCount().toSortedMap()
        val keys = listOf<String?>(null) + counts.keys
        val labels = listOf(all) + counts.map { (k, n) -> "$k  ($n)" }
        choose(title, labels, keys.indexOf(current).coerceAtLeast(0)) { onPick(keys[it]) }
    }

    private fun importFrom(uri: Uri) {
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    CollectionJson.parse(text)
                }
            }
            parsed.onFailure {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Couldn't import")
                    .setMessage(
                        (it as? CollectionJson.ImportException)?.message
                            ?: "This file couldn't be read as a collection export."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
            parsed.onSuccess { imported ->
                if (imported.isEmpty()) return@onSuccess snackbar("No cards in that file").show()
                if (allCards.isEmpty()) return@onSuccess runImport(imported, replace = false)
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Import ${imported.size} cards?")
                    .setMessage(
                        "You already have ${allCards.size} cards.\n\n" +
                            "Merge adds new cards and updates ones you already have.\n" +
                            "Replace deletes your current collection first."
                    )
                    .setNeutralButton("Cancel", null)
                    .setNegativeButton("Replace") { _, _ -> runImport(imported, replace = true) }
                    .setPositiveButton("Merge") { _, _ -> runImport(imported, replace = false) }
                    .show()
            }
        }
    }

    private fun runImport(imported: List<Card>, replace: Boolean) {
        lifecycleScope.launch {
            val withLocal = CollectionJson.keepLocal(imported, allCards)
            if (replace) dao.replaceAll(withLocal) else dao.upsertAll(withLocal)
            val copies = imported.sumOf { it.quantity }
            snackbar("Imported ${imported.size} cards ($copies copies)").show()
        }
    }

    private fun exportTo(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = CollectionJson.write(dao.all())
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
                }
            }
            snackbar(if (result.isSuccess) "Exported ${allCards.size} cards" else "Export failed").show()
        }
    }

    private fun snackbar(text: String) = Snackbar.make(root, text, Snackbar.LENGTH_LONG)
        .setAnchorView(if (tab == R.id.tab_stats) bottomNav else fab)

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
