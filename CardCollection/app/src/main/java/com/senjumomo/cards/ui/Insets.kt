package com.senjumomo.cards.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import android.view.ViewGroup

/** Keeps a scrolling view's content clear of the navigation bar and the keyboard. */
fun View.padForSystemBars() {
    val base = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val nav = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        v.updatePadding(bottom = base + maxOf(nav, ime))
        insets
    }
}

/** Lifts a floating view (e.g. the FAB) above the navigation bar. */
fun View.marginForNavBar() {
    val base = (layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val nav = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
        v.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = base + nav }
        insets
    }
}
