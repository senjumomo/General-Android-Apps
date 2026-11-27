package com.senjumomo.utilities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var calculatePage: View
    private lateinit var historyPage: View
    private lateinit var heroArea: CollapsingHero
    private lateinit var heroCalc: View
    private lateinit var heroHistory: View
    private lateinit var compactCalc: View
    private lateinit var compactHistory: View

    private var keyboardShown = false
    private var collapsedForKeyboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // The header is a deep gradient in both light and dark, so the status bar icons stay light.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prices = PriceStore(prefs)
        history = HistoryStore(prefs)

        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        bottomNav = findViewById(R.id.bottomNav)
        calculatePage = findViewById(R.id.calculatePage)
        historyPage = findViewById(R.id.historyPage)
        heroArea = findViewById(R.id.heroArea)
        heroCalc = findViewById(R.id.heroCalc)
        heroHistory = findViewById(R.id.heroHistory)
        compactCalc = findViewById(R.id.compactCalc)
        compactHistory = findViewById(R.id.compactHistory)

        // The bottom navigation already sits above the system nav bar; only the keyboard matters.
        padForSystemBars(findViewById(R.id.content), includeNavBar = false)
        padForSystemBars(findViewById(R.id.historyContent), includeNavBar = false)

        screen = BillScreen(root, prefs)
        historyScreen = HistoryScreen(root, ::showEntry)

        // With the keyboard up there is little room, so the big header folds down to its thin strip
        // (the total stays visible while you type) and comes back when the keyboard closes.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val shown = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (shown == keyboardShown) return@addOnGlobalLayoutListener
            keyboardShown = shown
            if (shown) {
                if (heroArea.progress < 1f) {
                    collapsedForKeyboard = true
                    heroArea.animateTo(1f)
                }
            } else if (collapsedForKeyboard) {
                collapsedForKeyboard = false
                heroArea.animateTo(0f)
            }
        }

        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_new_month -> confirmNewMonth()
                R.id.action_settings -> openSettings(SettingsActivity.FOCUS_WATER)
            }
            true
        }
        findViewById<MaterialButton>(R.id.copy).setOnClickListener { copyMessage(it) }
        findViewById<MaterialButton>(R.id.share).setOnClickListener {
            Motion.confirm(it)
            screen.bill?.let { bill -> share(bill) }
        }
        findViewById<MaterialButton>(R.id.saveHistory).setOnClickListener { saveToHistory() }
        findViewById<View>(R.id.monthEdit).setOnClickListener { chooseMonth() }
        findViewById<View>(R.id.waterRateEdit).setOnClickListener {
            openSettings(SettingsActivity.FOCUS_WATER)
        }
        findViewById<View>(R.id.tokenRateEdit).setOnClickListener {
            openSettings(SettingsActivity.FOCUS_TOKEN)
        }

        bottomNav.setOnItemSelectedListener { showTab(it.itemId); true }
        val tab = savedInstanceState?.getInt(STATE_TAB) ?: R.id.tab_calculate
        bottomNav.selectedItemId = tab
        showTab(tab, animate = false)

        // Cards rise in when the app opens (not when the screen is merely rotated).
        if (savedInstanceState == null) {
            root.post {
                val cards = listOf(R.id.debtCard, R.id.waterCard, R.id.elecCard, R.id.messageCard)
                    .map { findViewById<View>(it) }
                    .filter { it.visibility == View.VISIBLE }
                Motion.riseIn(cards, firstDelay = 100)
            }
        }
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

    private fun showTab(id: Int, animate: Boolean = true) {
        val calc = id == R.id.tab_calculate
        val incoming = if (calc) calculatePage else historyPage
        val outgoing = if (calc) historyPage else calculatePage
        outgoing.visibility = View.GONE
        if (incoming.visibility != View.VISIBLE) {
            incoming.visibility = View.VISIBLE
            if (animate && Motion.enabled(incoming)) {
                incoming.alpha = 0f
                incoming.animate().alpha(1f).setDuration(200).start()
            } else {
                incoming.alpha = 1f
            }
        }
        heroCalc.visibility = if (calc) View.VISIBLE else View.GONE
        heroHistory.visibility = if (calc) View.GONE else View.VISIBLE
        compactCalc.visibility = if (calc) View.VISIBLE else View.GONE
        compactHistory.visibility = if (calc) View.GONE else View.VISIBLE
        // Each tab starts with the full header.
        if (animate) heroArea.animateTo(0f) else heroArea.progress = 0f
        toolbar.title = if (calc) "Utilities" else "History"
        toolbar.menu.findItem(R.id.action_new_month).isVisible = calc
        if (!calc) historyScreen.playIntro()
    }

    private fun openSettings(focus: String) {
        startActivity(
            Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_FOCUS, focus)
        )
    }

    private fun snackbar(text: String) =
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).setAnchorView(bottomNav)

    private fun copyMessage(button: View) {
        val text = screen.bill?.message() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Utilities", text))
        Motion.confirm(button)
        snackbar("Copied, ready to paste").show()
    }

    private fun share(bill: Bill) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, bill.message())
        }
        startActivity(Intent.createChooser(send, "Send to landlord"))
    }

    private fun chooseMonth() {
        val months = Months.choices().toMutableList()
        if (screen.month !in months) months.add(screen.month)
        val labels = months.map {
            Months.label(it) + if (history.has(it)) "  (saved)" else ""
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Which month is this for?")
            .setSingleChoiceItems(labels, months.indexOf(screen.month)) { dialog, which ->
                screen.month = months[which]
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveToHistory() {
        val bill = screen.bill ?: return
        val month = screen.month
        val save = {
            history.save(HistoryEntry(month, bill, System.currentTimeMillis()))
            historyScreen.show(history.all())
            snackbar("Saved ${Months.label(month)}")
                .setAction("Start new month") { startNewMonth() }
                .show()
            celebrateSaved()
        }
        if (!history.has(month)) return save()
        MaterialAlertDialogBuilder(this)
            .setTitle("Replace ${Months.label(month)}?")
            .setMessage("You already saved this month. Saving again replaces it.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Replace") { _, _ -> save() }
            .show()
    }

    /** The Save button turns into a tick for a moment and the total gives a little pop. */
    private fun celebrateSaved() {
        val button = findViewById<MaterialButton>(R.id.saveHistory)
        Motion.confirm(button)
        screen.celebrate()
        button.text = "Saved"
        button.setIconResource(R.drawable.ic_check)
        button.postDelayed({
            button.text = "Save to history"
            button.setIconResource(R.drawable.ic_save)
        }, 1800)
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
                "Moves on to ${Months.label(Months.shift(screen.month, 1))}. This month's water " +
                    "reading becomes last month's; debt and tokens are cleared. " +
                    "Save this month to History first if you want to keep it."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ -> startNewMonth() }
            .show()
    }

    private fun startNewMonth() {
        val ok = screen.startNewMonth()
        bottomNav.selectedItemId = R.id.tab_calculate
        snackbar(
            if (ok) "Ready for ${Months.label(screen.month)}" else "Enter this month's water reading first"
        ).show()
    }

    companion object {
        const val PREFS = "utilities"
        private const val STATE_TAB = "tab"
    }
}
