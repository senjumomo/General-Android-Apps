package com.senjumomo.utilities

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.math.BigDecimal
import java.math.RoundingMode

/** Binds the History page (view_history.xml). */
class HistoryScreen(root: View, private val onClick: (HistoryEntry) -> Unit) {

    private val list: ViewGroup = root.findViewById(R.id.historyList)
    private val empty: View = root.findViewById(R.id.historyEmpty)
    private val summaryCard: View = root.findViewById(R.id.summaryCard)
    private val average: TextView = root.findViewById(R.id.summaryAverage)
    private val totalLabel: TextView = root.findViewById(R.id.summaryTotalLabel)
    private val total: TextView = root.findViewById(R.id.summaryTotal)

    fun show(entries: List<HistoryEntry>) {
        list.removeAllViews()
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        summaryCard.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        if (entries.isEmpty()) return

        val sum = entries.fold(BigDecimal.ZERO) { acc, e -> acc + e.bill.owing }
        average.text = Bill.money(sum.divide(BigDecimal(entries.size), 2, RoundingMode.HALF_UP))
        totalLabel.text = if (entries.size == 1) "Total, 1 month" else "Total, ${entries.size} months"
        total.text = Bill.money(sum)

        val inflater = LayoutInflater.from(list.context)
        val iconSize = (18 * list.resources.displayMetrics.density).toInt()
        entries.forEach { entry ->
            val b = entry.bill
            val card = inflater.inflate(R.layout.item_history, list, false)
            card.findViewById<TextView>(R.id.month).text = Months.label(entry.month)
            card.findViewById<TextView>(R.id.owing).text = Bill.money(b.owing)
            card.findViewById<TextView>(R.id.water).text =
                "Water: ${Bill.num(b.waterUsed)} kl × ${Bill.money(b.pricePerKl)} = ${Bill.money(b.waterCost)}"
            card.findViewById<TextView>(R.id.electricity).text =
                "Electricity: ${Bill.num(b.tokens)} ${Bill.tokenWord(b.tokens)} × " +
                    "${Bill.money(b.pricePerToken)} = ${Bill.money(b.electricityCost)}"
            card.findViewById<TextView>(R.id.debt).apply {
                text = "Previous debt: ${Bill.money(b.debt)}"
                visibility = if (b.debt.signum() == 0) View.GONE else View.VISIBLE
            }
            listOf(R.id.water, R.id.electricity, R.id.debt).forEach { id ->
                card.findViewById<TextView>(id).compoundDrawablesRelative[0]
                    ?.setBounds(0, 0, iconSize, iconSize)
            }
            card.setOnClickListener { onClick(entry) }
            list.addView(card)
        }
    }
}
