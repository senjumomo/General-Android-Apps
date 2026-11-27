package com.senjumomo.utilities

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes

/** One measure's colours. Fixed (not wallpaper-driven) so water is always blue, power always amber. */
data class Palette(
    @ColorRes val accent: Int,
    @ColorRes val container: Int,
    @ColorRes val onContainer: Int,
    @DrawableRes val card: Int,
) {
    companion object {
        val WATER = Palette(R.color.water_accent, R.color.water_container, R.color.water_on_container, R.drawable.card_water)
        val ELECTRICITY = Palette(R.color.elec_accent, R.color.elec_container, R.color.elec_on_container, R.drawable.card_elec)
        val DEBT = Palette(R.color.debt_accent, R.color.debt_container, R.color.debt_on_container, R.drawable.card_debt)
        val TOTAL = Palette(R.color.total_accent, R.color.total_container, R.color.total_on_container, R.drawable.card_total)
    }
}
