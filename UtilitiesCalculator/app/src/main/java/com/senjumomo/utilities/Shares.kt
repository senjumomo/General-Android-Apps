package com.senjumomo.utilities

import java.math.BigDecimal
import java.math.RoundingMode

/** How a bill splits between debt, water and electricity, as fractions that add up to 1. */
object Shares {
    /** Negative or missing amounts count as zero. When everything is zero there is nothing to draw. */
    fun of(vararg amounts: BigDecimal?): FloatArray {
        val values = amounts.map { (it ?: BigDecimal.ZERO).max(BigDecimal.ZERO) }
        val total = values.fold(BigDecimal.ZERO, BigDecimal::add)
        if (total.signum() == 0) return FloatArray(values.size)
        return FloatArray(values.size) { values[it].divide(total, 6, RoundingMode.HALF_UP).toFloat() }
    }
}

/** The − / + buttons next to the token count. */
object Stepper {
    /** [text] moved by [delta], never below zero. Empty or invalid text counts as zero. */
    fun step(text: CharSequence?, delta: Int): String {
        val current = Bill.parse(text) ?: BigDecimal.ZERO
        return Bill.num((current + BigDecimal(delta)).max(BigDecimal.ZERO))
    }
}
