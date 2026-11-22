package com.senjumomo.utilities

import android.view.View
import androidx.core.view.allViews
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.junit.Rule
import org.junit.Test

class ScreenshotTest {
    private val device = DeviceConfig.PIXEL_6.copy(screenHeight = 3400, softButtons = false)

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = device, theme = "Theme.Utilities", maxPercentDifference = 0.1)

    private val example = mapOf(
        "debt" to "900", "waterPrev" to "800", "waterCurr" to "810", "tokens" to "10",
    )

    private fun inflate(layout: Int): View =
        paparazzi.inflate<View>(layout).also { root ->
            // Floating-label animations don't run in the renderer; jump straight to the end state.
            root.allViews.filterIsInstance<TextInputLayout>().forEach { it.isHintAnimationEnabled = false }
        }

    private fun calculate(values: Map<String, String>, priced: Boolean = true): View {
        val root = inflate(R.layout.activity_main)
        BillScreen(root.findViewById(R.id.calculatePage), null).apply {
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

    @Test fun error() = paparazzi.snapshot(calculate(example + ("waterCurr" to "790")))

    @Test fun historyList() = paparazzi.snapshot(history(sampleHistory))

    @Test fun historyEmpty() = paparazzi.snapshot(history(emptyList()))

    @Test fun settings() {
        val root = inflate(R.layout.activity_settings)
        root.findViewById<TextInputEditText>(R.id.waterPrice).setText("70")
        root.findViewById<TextInputEditText>(R.id.tokenPrice).setText("100")
        paparazzi.snapshot(root)
    }

    @Test fun filledDark() {
        paparazzi.unsafeUpdateConfig(device.copy(nightMode = NightMode.NIGHT))
        paparazzi.snapshot(calculate(example))
    }

    @Test fun historyDark() {
        paparazzi.unsafeUpdateConfig(device.copy(nightMode = NightMode.NIGHT))
        paparazzi.snapshot(history(sampleHistory))
    }
}
