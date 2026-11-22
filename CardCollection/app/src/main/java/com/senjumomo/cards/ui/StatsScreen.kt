package com.senjumomo.cards.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.senjumomo.cards.R
import com.senjumomo.cards.data.Card
import com.senjumomo.cards.data.CardImages

/** Value broken down by set and rarity, for building the Stats page charts. */
data class Breakdown(val key: String, val valueCents: Long, val copies: Int) {
    companion object {
        /** Largest first; anything beyond [limit] is folded into one "Other" row (key = null). */
        fun of(cards: List<Card>, limit: Int, by: (Card) -> String): Pair<List<Breakdown>, Breakdown?> {
            val groups = cards.groupBy { by(it).ifBlank { "Unknown" } }
                .map { (k, v) -> Breakdown(k, v.sumOf { it.valueTotalCents }, v.sumOf { it.quantity }) }
                .sortedWith(compareByDescending<Breakdown> { it.valueCents }.thenBy { it.key })
            if (groups.size <= limit) return groups to null
            val rest = groups.drop(limit - 1)
            return groups.take(limit - 1) to
                Breakdown("Other (${rest.size})", rest.sumOf { it.valueCents }, rest.sumOf { it.copies })
        }
    }
}

/** Binds the Stats page (view_stats.xml). */
class StatsScreen(
    root: View,
    private val onSet: (String) -> Unit,
    private val onRarity: (String) -> Unit,
    private val onCard: (Card) -> Unit,
) {
    private val charts: View = root.findViewById(R.id.statsCharts)
    private val empty: View = root.findViewById(R.id.statsEmpty)
    private val setChart: HBarChartView = root.findViewById(R.id.setChart)
    private val rarityChart: HBarChartView = root.findViewById(R.id.rarityChart)
    private val setSub: TextView = root.findViewById(R.id.setSub)
    private val raritySub: TextView = root.findViewById(R.id.raritySub)
    private val topList: ViewGroup = root.findViewById(R.id.topList)

    init {
        setChart.onRowClick = { row -> row.key?.let(onSet) }
        rarityChart.onRowClick = { row -> row.key?.let(onRarity) }
    }

    fun show(cards: List<Card>) {
        empty.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
        charts.visibility = if (cards.isEmpty()) View.GONE else View.VISIBLE
        if (cards.isEmpty()) return
        val currency = cards.first().currency

        val setCount = cards.map { it.setName }.distinct().size
        setSub.text = "$setCount sets · tap one to see its cards"
        setChart.setRows(rows(Breakdown.of(cards, 12) { it.setName }, currency))

        val rarityCount = cards.map { it.rarity }.distinct().size
        raritySub.text = "$rarityCount rarities · tap one to see its cards"
        rarityChart.setRows(rows(Breakdown.of(cards, 12) { it.rarity }, currency))

        topList.removeAllViews()
        val inflater = LayoutInflater.from(topList.context)
        cards.sortedByDescending { it.valueTotalCents }.take(10).forEachIndexed { i, card ->
            val v = inflater.inflate(R.layout.item_top, topList, false)
            v.findViewById<TextView>(R.id.rank).text = "${i + 1}"
            CardImages.load(v.findViewById<ImageView>(R.id.image), CardImages.small(card))
            v.findViewById<TextView>(R.id.name).text = card.name
            v.findViewById<TextView>(R.id.meta).text =
                listOf(card.code, card.rarity).filter { it.isNotBlank() }.joinToString(" · ") +
                    if (card.quantity > 1) " · ×${card.quantity}" else ""
            v.findViewById<TextView>(R.id.value).text = Money.format(card.valueTotalCents, card.currency)
            v.setOnClickListener { onCard(card) }
            topList.addView(v)
        }
    }

    private fun rows(data: Pair<List<Breakdown>, Breakdown?>, currency: String) =
        (data.first.map { it to it.key } + listOfNotNull(data.second?.let { it to null })).map { (b, key) ->
            HBarChartView.Row(
                label = b.key,
                detail = "${b.copies} ${if (b.copies == 1) "card" else "cards"}",
                value = b.valueCents.toFloat(),
                display = Money.format(b.valueCents, currency),
                key = key,
            )
        }
}
