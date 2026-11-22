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

    private fun main(cards: List<Card>, filter: Filter = Filter()): View {
        val root = inflate(R.layout.activity_main)
        val shown = filter.apply(cards)
        root.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = CardAdapter {}.apply {
                submit(listOf(Row.Header(Summary.of(shown), filter.isActive)) + shown.map { Row.Item(it) })
            }
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
            title = "Edit card"; inflateMenu(R.menu.edit)
        }
        CardForm(root).bind(Samples.cards[3], emptyList(), emptyList())
        paparazzi.snapshot(root)
    }

    @Test fun collectionDark() {
        paparazzi.unsafeUpdateConfig(device.copy(nightMode = NightMode.NIGHT))
        paparazzi.snapshot(main(Samples.cards))
    }
}
