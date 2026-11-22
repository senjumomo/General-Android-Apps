package com.senjumomo.utilities

import android.view.View
import androidx.core.view.allViews
import com.google.android.material.textfield.TextInputLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenHeight = 3400, softButtons = false),
        theme = "Theme.Utilities",
        maxPercentDifference = 0.1,
    )

    private val example = mapOf(
        "debt" to "900", "waterPrev" to "800", "waterCurr" to "810",
        "waterPrice" to "70", "tokens" to "10", "tokenPrice" to "100",
    )

    private fun render(values: Map<String, String>): View {
        val root = paparazzi.inflate<View>(R.layout.activity_main)
        // Floating-label animations don't run in the renderer; jump straight to the end state.
        root.allViews.filterIsInstance<TextInputLayout>().forEach { it.isHintAnimationEnabled = false }
        BillScreen(root, null).fill(values)
        return root
    }

    @Test fun empty() = paparazzi.snapshot(render(emptyMap()))

    @Test fun filled() = paparazzi.snapshot(render(example))

    @Test fun error() = paparazzi.snapshot(render(example + ("waterCurr" to "790")))

    @Test fun filledDark() {
        paparazzi.unsafeUpdateConfig(
            DeviceConfig.PIXEL_6.copy(screenHeight = 3400, softButtons = false, nightMode = NightMode.NIGHT)
        )
        paparazzi.snapshot(render(example))
    }
}
