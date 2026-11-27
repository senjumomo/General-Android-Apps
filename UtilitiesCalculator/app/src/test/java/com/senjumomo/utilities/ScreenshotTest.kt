package com.senjumomo.utilities

import android.view.View
import android.widget.EditText
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

class ScreenshotTest {
    private val device = DeviceConfig.PIXEL_6.copy(screenHeight = 3400, softButtons = false)

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = device, theme = "Theme.Utilities", maxPercentDifference = 0.1)

    private val example = mapOf(
        "debt" to "900", "waterPrev" to "800", "waterCurr" to "810", "tokens" to "10",
    )

    private fun inflate(layout: Int): View = paparazzi.inflate<View>(layout)

    private fun calculate(values: Map<String, String>, priced: Boolean = true): View {
        val root = inflate(R.layout.activity_main)
        BillScreen(root, null, animate = false).apply {
            month = "2026-11"
            if (priced) setPrices("70".toBigDecimal(), "100".toBigDecimal())
            fill(values)
        }
        return root
    }

    private fun history(entries: List<HistoryEntry>): View {
        val root = inflate(R.layout.activity_main)
        root.findViewById<View>(R.id.calculatePage).visibility = View.GONE
        root.findViewById<View>(R.id.historyPage).visibility = View.VISIBLE
        root.findViewById<View>(R.id.heroCalc).visibility = View.GONE
        root.findViewById<View>(R.id.heroHistory).visibility = View.VISIBLE
        root.findViewById<View>(R.id.compactCalc).visibility = View.GONE
        root.findViewById<View>(R.id.compactHistory).visibility = View.VISIBLE
        root.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
            .selectedItemId = R.id.tab_history
        root.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).title = "History"
        HistoryScreen(root) {}.show(entries)
        return root
    }

    private fun entry(month: String, prev: String, curr: String, tokens: String, debt: String = "0") =
        HistoryEntry(
            month,
            Bill(debt.toBigDecimal(), prev.toBigDecimal(), curr.toBigDecimal(), "70".toBigDecimal(),
                tokens.toBigDecimal(), "100".toBigDecimal()),
            0L,
        )

    private val sampleHistory = listOf(
        entry("2026-11", "800", "810", "10", debt = "900"),
        entry("2026-10", "788", "800", "8"),
        entry("2026-09", "779", "788", "12", debt = "250"),
        entry("2026-08", "765", "779", "14"),
        entry("2026-07", "754", "765", "13"),
        entry("2026-06", "746", "754", "9"),
    )

    @Test fun historySingle() = paparazzi.snapshot(history(sampleHistory.take(1)))

    @Test fun empty() = paparazzi.snapshot(calculate(emptyMap()))

    @Test fun noPrices() = paparazzi.snapshot(calculate(example, priced = false))

    @Test fun filled() = paparazzi.snapshot(calculate(example))

    private fun collapsed(progress: Float, dark: Boolean = false): View {
        if (dark) paparazzi.unsafeUpdateConfig(device.copy(nightMode = NightMode.NIGHT))
        val root = calculate(example)
        root.findViewById<CollapsingHero>(R.id.heroArea).progress = progress
        return root
    }

    /** The header part-way through shrinking, and fully shrunk to its strip. */
    @Test fun headerHalf() = paparazzi.snapshot(collapsed(0.5f))

    @Test fun headerCollapsed() = paparazzi.snapshot(collapsed(1f))

    @Test fun headerCollapsedDark() = paparazzi.snapshot(collapsed(1f, dark = true))

    @Test fun historyCollapsed() {
        val root = history(sampleHistory)
        root.findViewById<CollapsingHero>(R.id.heroArea).progress = 1f
        paparazzi.snapshot(root)
    }

    /** Large system text (like the phone's): nothing in the header may be clipped. */
    @Test fun bigText() {
        paparazzi.unsafeUpdateConfig(device.copy(fontScale = 1.3f))
        paparazzi.snapshot(calculate(example))
    }

    @Test fun incomplete() = paparazzi.snapshot(calculate(mapOf("waterPrev" to "800", "waterCurr" to "812")))

    @Test fun icon() {
        val root = inflate(R.layout.icon_preview)
        paparazzi.snapshot(root)
    }

    @Test fun error() = paparazzi.snapshot(calculate(example + ("waterCurr" to "790")))

    @Test fun historyList() = paparazzi.snapshot(history(sampleHistory))

    @Test fun historyEmpty() = paparazzi.snapshot(history(emptyList()))

    @Test fun settings() {
        val root = inflate(R.layout.activity_settings)
        root.findViewById<EditText>(R.id.waterPrice).setText("70")
        root.findViewById<EditText>(R.id.tokenPrice).setText("100")
        paparazzi.snapshot(root)
    }

    @Test fun filledDark() {
        paparazzi.unsafeUpdateConfig(device.copy(nightMode = NightMode.NIGHT))
        paparazzi.snapshot(calculate(example))
    }

    @Test fun emptyDark() {
        paparazzi.unsafeUpdateConfig(device.copy(nightMode = NightMode.NIGHT))
        paparazzi.snapshot(calculate(emptyMap()))
    }

    @Test fun settingsDark() {
        paparazzi.unsafeUpdateConfig(device.copy(nightMode = NightMode.NIGHT))
        val root = inflate(R.layout.activity_settings)
        root.findViewById<EditText>(R.id.waterPrice).setText("70")
        root.findViewById<EditText>(R.id.tokenPrice).setText("100")
        paparazzi.snapshot(root)
    }

    @Test fun historyDark() {
        paparazzi.unsafeUpdateConfig(device.copy(nightMode = NightMode.NIGHT))
        paparazzi.snapshot(history(sampleHistory))
    }
}
