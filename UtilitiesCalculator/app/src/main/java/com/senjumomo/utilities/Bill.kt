package com.senjumomo.utilities

import java.math.BigDecimal
import java.math.RoundingMode

/** One month's utilities. All money in rands. */
data class Bill(
    val debt: BigDecimal,
    val waterPrev: BigDecimal,
    val waterCurr: BigDecimal,
    val pricePerKl: BigDecimal,
    val tokens: BigDecimal,
    val pricePerToken: BigDecimal,
) {
    val waterUsed: BigDecimal get() = waterCurr - waterPrev
    val waterCost: BigDecimal get() = waterUsed * pricePerKl
    val electricityCost: BigDecimal get() = tokens * pricePerToken
    val owing: BigDecimal get() = debt + waterCost + electricityCost

    fun message(): String = buildString {
        appendLine("Previous Month's Debt: ${money(debt)}")
        appendLine()
        appendLine(
            "Water: ${num(waterCurr)}kl - ${num(waterPrev)}kl = ${num(waterUsed)}kl" +
                " x ${money(pricePerKl)} = ${money(waterCost)}"
        )
        appendLine()
        appendLine(
            "Electricity: ${num(tokens)} ${tokenWord(tokens)} x ${money(pricePerToken)}" +
                " = ${money(electricityCost)}"
        )
        appendLine()
        append("Owing = ${money(owing)}")
    }

    companion object {
        /** "810" / "10.5" - no trailing zeros. */
        fun num(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

        /** Whole rands stay whole (R700); otherwise two decimals (R12.50). */
        fun money(value: BigDecimal): String {
            val rounded = value.setScale(2, RoundingMode.HALF_UP)
            val text = if (rounded.stripTrailingZeros().scale() <= 0) {
                rounded.setScale(0).toPlainString()
            } else {
                rounded.toPlainString()
            }
            return "R$text"
        }

        fun tokenWord(count: BigDecimal) =
            if (count.compareTo(BigDecimal.ONE) == 0) "token" else "tokens"

        /** Accepts "12,5" as well as "12.5". Null when empty or invalid. */
        fun parse(text: CharSequence?): BigDecimal? =
            text?.toString()?.trim()?.replace(',', '.')?.toBigDecimalOrNull()
    }
}
