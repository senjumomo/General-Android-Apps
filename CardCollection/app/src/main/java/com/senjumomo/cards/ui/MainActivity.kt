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

    private var cards: List<Card> = emptyList()
    private var loaded = false
    private var filter = Filter()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importFrom)
    }
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let(::exportTo)
        }
    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        EditActivity.takeDeleted()?.let { deleted ->
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
        list = findViewById(R.id.list)
        fab = findViewById(R.id.add)
        search = findViewById(R.id.search)
        chipSort = findViewById(R.id.chipSort)
        chipSet = findViewById(R.id.chipSet)
        chipRarity = findViewById(R.id.chipRarity)

        list.padForSystemBars()
        fab.marginForNavBar()

        val prefs = getSharedPreferences("cards", Context.MODE_PRIVATE)
        filter = filter.copy(sort = runCatching { Sort.valueOf(prefs.getString("sort", "")!!) }.getOrDefault(Sort.NAME))

        adapter = CardAdapter(::openCard)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8) fab.shrink() else if (dy < -8) fab.extend()
            }
        })

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_import -> importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                R.id.action_export -> exportLauncher.launch("card-collection-${today()}.json")
            }
            true
        }
        fab.setOnClickListener { editLauncher.launch(Intent(this, EditActivity::class.java)) }
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
                    cards = it
                    loaded = true
                    render()
                }
            }
        }
    }

    private fun render() {
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
            else listOf(Row.Header(Summary.of(shown), filter.isActive)) + shown.map { Row.Item(it) }
        )
        showEmpty(loaded && shown.isEmpty())
    }

    private fun showEmpty(show: Boolean) {
        val empty = findViewById<View>(R.id.empty)
        empty.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val title = findViewById<TextView>(R.id.emptyTitle)
        val body = findViewById<TextView>(R.id.emptyBody)
        val action = findViewById<MaterialButton>(R.id.emptyAction)
        if (cards.isEmpty()) {
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

    private fun openCard(card: Card) {
        editLauncher.launch(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_ID, card.id))
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
                if (cards.isEmpty()) return@onSuccess runImport(imported, replace = false)
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Import ${imported.size} cards?")
                    .setMessage(
                        "You already have ${cards.size} cards.\n\n" +
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
            if (replace) dao.replaceAll(imported) else dao.upsertAll(imported)
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
            snackbar(if (result.isSuccess) "Exported ${cards.size} cards" else "Export failed").show()
        }
    }

    private fun snackbar(text: String) = Snackbar.make(root, text, Snackbar.LENGTH_LONG).setAnchorView(fab)

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
