package com.senjumomo.utilities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var screen: BillScreen
    private lateinit var historyScreen: HistoryScreen
    private lateinit var prices: PriceStore
    private lateinit var history: HistoryStore

    private lateinit var root: View
    private lateinit var appBar: AppBarLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var calculatePage: View
    private lateinit var historyPage: View

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prices = PriceStore(prefs)
        history = HistoryStore(prefs)

        root = findViewById(R.id.root)
        appBar = findViewById(R.id.appBar)
        toolbar = findViewById(R.id.toolbar)
        bottomNav = findViewById(R.id.bottomNav)
        calculatePage = findViewById(R.id.calculatePage)
        historyPage = findViewById(R.id.historyPage)

        // The bottom navigation already sits above the system nav bar; only the keyboard matters.
        padForSystemBars(findViewById(R.id.content), includeNavBar = false)
        padForSystemBars(findViewById(R.id.historyContent), includeNavBar = false)

        screen = BillScreen(calculatePage, prefs)
        historyScreen = HistoryScreen(historyPage, ::showEntry)

        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_new_month -> confirmNewMonth()
                R.id.action_settings -> openSettings(SettingsActivity.FOCUS_WATER)
            }
            true
        }
        findViewById<MaterialButton>(R.id.copy).setOnClickListener { copyMessage() }
        findViewById<MaterialButton>(R.id.share).setOnClickListener { screen.bill?.let { share(it) } }
        findViewById<MaterialButton>(R.id.saveHistory).setOnClickListener { chooseMonthAndSave() }
        findViewById<MaterialButton>(R.id.waterRateEdit).setOnClickListener {
            openSettings(SettingsActivity.FOCUS_WATER)
        }
        findViewById<MaterialButton>(R.id.tokenRateEdit).setOnClickListener {
            openSettings(SettingsActivity.FOCUS_TOKEN)
        }

        bottomNav.setOnItemSelectedListener { showTab(it.itemId); true }
        val tab = savedInstanceState?.getInt(STATE_TAB) ?: R.id.tab_calculate
        bottomNav.selectedItemId = tab
        showTab(tab)
    }

    override fun onResume() {
        super.onResume()
        // Prices may have changed in Settings.
        screen.setPrices(prices.waterPrice, prices.tokenPrice)
        historyScreen.show(history.all())
    }

    override fun onPause() {
        super.onPause()
        screen.save()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, bottomNav.selectedItemId)
    }

    private fun showTab(id: Int) {
        val calc = id == R.id.tab_calculate
        calculatePage.visibility = if (calc) View.VISIBLE else View.GONE
        historyPage.visibility = if (calc) View.GONE else View.VISIBLE
        toolbar.title = if (calc) "Utilities" else "History"
        toolbar.menu.findItem(R.id.action_new_month).isVisible = calc
        appBar.liftOnScrollTargetViewId = if (calc) R.id.scroll else R.id.historyScroll
    }

    private fun openSettings(focus: String) {
        startActivity(
            Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_FOCUS, focus)
        )
    }

    private fun snackbar(text: String) =
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).setAnchorView(bottomNav)

    private fun copyMessage() {
        val text = screen.bill?.message() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Utilities", text))
        snackbar("Copied, ready to paste").show()
    }

    private fun share(bill: Bill) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, bill.message())
        }
        startActivity(Intent.createChooser(send, "Send to landlord"))
    }

    private fun chooseMonthAndSave() {
        val bill = screen.bill ?: return
        val months = Months.recent(6)
        val labels = months.map {
            Months.label(it) + if (history.has(it)) "  (replaces saved)" else ""
        }.toTypedArray()
        var chosen = 0
        MaterialAlertDialogBuilder(this)
            .setTitle("Save which month?")
            .setSingleChoiceItems(labels, chosen) { _, which -> chosen = which }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val month = months[chosen]
                history.save(HistoryEntry(month, bill, System.currentTimeMillis()))
                historyScreen.show(history.all())
                snackbar("Saved ${Months.label(month)}")
                    .setAction("Start new month") { startNewMonth() }
                    .show()
            }
            .show()
    }

    private fun showEntry(entry: HistoryEntry) {
        val view = layoutInflater.inflate(R.layout.dialog_entry, null)
        view.findViewById<android.widget.TextView>(R.id.message).text = entry.bill.message()
        MaterialAlertDialogBuilder(this)
            .setTitle(Months.label(entry.month))
            .setView(view)
            .setPositiveButton("Share") { _, _ -> share(entry.bill) }
            .setNegativeButton("Close", null)
            .setNeutralButton("Delete") { _, _ -> confirmDelete(entry) }
            .show()
    }

    private fun confirmDelete(entry: HistoryEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${Months.label(entry.month)}?")
            .setMessage("This removes the month from your history.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                history.delete(entry.month)
                historyScreen.show(history.all())
                snackbar("Deleted ${Months.label(entry.month)}")
                    .setAction("Undo") {
                        history.save(entry)
                        historyScreen.show(history.all())
                    }
                    .show()
            }
            .show()
    }

    private fun confirmNewMonth() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_new_month)
            .setTitle("Start a new month?")
            .setMessage(
                "This month's water reading becomes last month's. Debt and tokens are cleared. " +
                    "Save this month to History first if you want to keep it."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ -> startNewMonth() }
            .show()
    }

    private fun startNewMonth() {
        val ok = screen.startNewMonth()
        bottomNav.selectedItemId = R.id.tab_calculate
        snackbar(if (ok) "Ready for next month" else "Enter this month's water reading first").show()
    }

    companion object {
        const val PREFS = "utilities"
        private const val STATE_TAB = "tab"
    }
}
