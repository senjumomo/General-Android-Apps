package com.senjumomo.cards.ui

import com.senjumomo.cards.data.Card
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

enum class Sort(val label: String) {
    NAME("Name"),
    VALUE("Value"),
    RECENT("Recently added"),
    SET("Set"),
    QUANTITY("Quantity"),
}

/** What the list is currently showing. Null set/rarity = all. */
data class Filter(
    val query: String = "",
    val set: String? = null,
    val rarity: String? = null,
    val sort: Sort = Sort.NAME,
) {
    val isActive get() = query.isNotBlank() || set != null || rarity != null

    fun apply(cards: List<Card>): List<Card> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val filtered = cards.filter { c ->
            (set == null || c.setName == set) &&
                (rarity == null || c.rarity == rarity) &&
                terms.all { t ->
                    c.name.lowercase().contains(t) || c.setName.lowercase().contains(t) ||
                        c.code.lowercase().contains(t) || c.rarity.lowercase().contains(t)
                }
        }
        val byName = compareBy<Card, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.code }
        return when (sort) {
            Sort.NAME -> filtered.sortedWith(byName)
            Sort.VALUE -> filtered.sortedWith(compareByDescending<Card> { it.valueTotalCents }.then(byName))
            Sort.RECENT -> filtered.sortedWith(compareByDescending<Card> { it.dateAdded }.then(byName))
            Sort.SET -> filtered.sortedWith(compareBy<Card> { it.setName }.thenBy { it.code })
            Sort.QUANTITY -> filtered.sortedWith(compareByDescending<Card> { it.quantity }.then(byName))
        }
    }
}

data class Summary(
    val copies: Int,
    val unique: Int,
    val valueCents: Long,
    /** Paid for the cards that have a paid price. */
    val paidCents: Long,
    /** Value of the cards that have a paid price, so gain compares like with like. */
    val valueOfPaidCents: Long,
    val currency: String,
) {
    val gainCents get() = valueOfPaidCents - paidCents

    companion object {
        fun of(cards: List<Card>) = Summary(
            copies = cards.sumOf { it.quantity },
            unique = cards.size,
            valueCents = cards.sumOf { it.valueTotalCents },
            paidCents = cards.sumOf { it.paidTotalCents ?: 0 },
            valueOfPaidCents = cards.filter { it.paidPerUnitCents != null }.sumOf { it.valueTotalCents },
            currency = cards.firstOrNull()?.currency ?: "ZAR",
        )
    }
}

object Money {
    private val format get() = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))

    fun symbol(currency: String) = when (currency.uppercase()) {
        "ZAR" -> "R"
        "USD" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        else -> "$currency "
    }

    /** 984350 -> "R9,843.50" */
    fun format(cents: Long, currency: String = "ZAR"): String {
        val sign = if (cents < 0) "−" else ""
        return sign + symbol(currency) + format.format(BigDecimal(kotlin.math.abs(cents)).movePointLeft(2))
    }

    /** Signed, for gains: "+R380.30" / "−R12.00" */
    fun signed(cents: Long, currency: String = "ZAR") =
        if (cents > 0) "+" + format(cents, currency) else format(cents, currency)
}
