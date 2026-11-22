package com.senjumomo.utilities

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        padForSystemBars(findViewById(R.id.settingsContent))

        val prices = PriceStore(getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE))
        val water = findViewById<TextInputEditText>(R.id.waterPrice)
        val token = findViewById<TextInputEditText>(R.id.tokenPrice)
        water.setText(prices.waterPrice?.let(Bill::num).orEmpty())
        token.setText(prices.tokenPrice?.let(Bill::num).orEmpty())
        water.doAfterTextChanged { prices.waterPrice = Bill.parse(it) }
        token.doAfterTextChanged { prices.tokenPrice = Bill.parse(it) }

        if (intent.getStringExtra(EXTRA_FOCUS) == FOCUS_TOKEN) token.requestFocus()
        else water.requestFocus()
    }

    companion object {
        const val EXTRA_FOCUS = "focus"
        const val FOCUS_WATER = "water"
        const val FOCUS_TOKEN = "token"
    }
}

/** Keeps a scrolling page's content clear of the navigation bar and the keyboard. */
fun padForSystemBars(content: View, includeNavBar: Boolean = true) {
    val base = content.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
        val nav = if (includeNavBar) insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom else 0
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        v.updatePadding(bottom = base + maxOf(nav, ime))
        insets
    }
}
