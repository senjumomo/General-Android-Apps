package com.senjumomo.utilities

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import java.math.BigDecimal
import java.math.RoundingMode
import com.google.android.material.R as M

/** Binds the History page (view_history.xml): trend cards, then the saved months. */
class HistoryScreen(root: View, private val onClick: (HistoryEntry) -> Unit) {

    private val trends: ViewGroup = root.findViewById(R.id.trends)
    private val list: ViewGroup = root.findViewById(R.id.historyList)
    private val monthsHeader: View = root.findViewById(R.id.monthsHeader)
    private val empty: View = root.findViewById(R.id.historyEmpty)
    private val inflater = LayoutInflater.from(root.context)

    fun show(entries: List<HistoryEntry>) {
        trends.removeAllViews()
        list.removeAllViews()
        val has = entries.isNotEmpty()
        empty.visibility = if (has) View.GONE else View.VISIBLE
        monthsHeader.visibility = if (has) View.VISIBLE else View.GONE
        if (!has) return

        showTrends(entries.sortedByDescending { it.month }.take(MAX_MONTHS))
        entries.forEach { list.addView(monthCard(it)) }
    }

    private fun showTrends(recent: List<HistoryEntry>) {
        val period = if (recent.size == 1) "1 month" else "last ${recent.size} months"

        Trend.of(recent) { it.waterUsed }?.let { t ->
            val avgCost = Trend.of(recent) { it.waterCost }!!.average
            addTrend(
                t, R.drawable.ic_water, "Water used",
                "kl per month, $period · avg cost ${Bill.money(avgCost)}",
                M.attr.colorPrimary, M.attr.colorPrimaryContainer, M.attr.colorOnPrimaryContainer,
            ) { "${round1(it)} kl" }
        }
        Trend.of(recent) { it.tokens }?.let { t ->
            val avgSpend = Trend.of(recent) { it.electricityCost }!!.average
            addTrend(
                t, R.drawable.ic_bolt, "Electricity tokens",
                "Tokens per month, $period · avg spend ${Bill.money(avgSpend)}",
                M.attr.colorTertiary, M.attr.colorTertiaryContainer, M.attr.colorOnTertiaryContainer,
            ) { "${round1(it)} ${Bill.tokenWord(it)}" }
        }
        Trend.of(recent) { it.owing }?.let { t ->
            val sum = recent.fold(BigDecimal.ZERO) { acc, e -> acc + e.bill.owing }
            addTrend(
                t, R.drawable.ic_wallet, "Total owing",
                "Per month, $period · ${Bill.money(sum)} in all",
                M.attr.colorSecondary, M.attr.colorSecondaryContainer, M.attr.colorOnSecondaryContainer,
            ) { Bill.money(it) }
        }
    }

    private fun addTrend(
        t: Trend, icon: Int, title: String, subtitle: String,
        barAttr: Int, badgeAttr: Int, onBadgeAttr: Int,
        format: (BigDecimal) -> String,
    ) {
        val card = inflater.inflate(R.layout.item_trend, trends, false)
        card.findViewById<ImageView>(R.id.icon).apply {
            setImageResource(icon)
            backgroundTintList = android.content.res.ColorStateList.valueOf(MaterialColors.getColor(this, badgeAttr))
            imageTintList = android.content.res.ColorStateList.valueOf(MaterialColors.getColor(this, onBadgeAttr))
        }
        card.findViewById<TextView>(R.id.title).text = title
        card.findViewById<TextView>(R.id.subtitle).text = subtitle

        card.findViewById<BarChartView>(R.id.chart).apply {
            barColor = MaterialColors.getColor(this, barAttr)
            setData(
                t.series.map {
                    BarChartView.Bar(Months.short(it.month), it.value.toFloat(), format(it.value), Months.label(it.month))
                },
                average = if (t.series.size > 1) t.average.toFloat() else null,
                averageDisplay = format(t.average),
            )
        }

        val latestCaption = when {
            t.latestVsOthersPercent == null -> "First month saved"
            t.latestVsOthersPercent.signum() == 0 -> "Same as other months"
            t.latestVsOthersPercent.signum() > 0 -> "▲ ${t.latestVsOthersPercent}% vs other months"
            else -> "▼ ${t.latestVsOthersPercent.negate()}% vs other months"
        }
        tile(card, R.id.statAverage, "Average", format(t.average), "per month")
        tile(card, R.id.statLatest, "Latest", format(t.latest.value), latestCaption)
        tile(card, R.id.statLowest, "Lowest", format(t.lowest.value), Months.medium(t.lowest.month))
        tile(card, R.id.statHighest, "Highest", format(t.highest.value), Months.medium(t.highest.month))
        trends.addView(card)
    }

    private fun tile(card: View, id: Int, label: String, value: String, caption: String) {
        val tile = card.findViewById<View>(id)
        tile.findViewById<TextView>(R.id.label).text = label
        tile.findViewById<TextView>(R.id.value).text = value
        tile.findViewById<TextView>(R.id.caption).text = caption
    }

    private fun monthCard(entry: HistoryEntry): View {
        val b = entry.bill
        val card = inflater.inflate(R.layout.item_history, list, false)
        val iconSize = (18 * card.resources.displayMetrics.density).toInt()
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
            card.findViewById<TextView>(id).compoundDrawablesRelative[0]?.setBounds(0, 0, iconSize, iconSize)
        }
        card.setOnClickListener { onClick(entry) }
        return card
    }

    private fun round1(v: BigDecimal) = Bill.num(v.setScale(1, RoundingMode.HALF_UP))

    companion object {
        /** Trends cover at most the last year of saved months. */
        const val MAX_MONTHS = 12
    }
}
