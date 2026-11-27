package com.senjumomo.utilities

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Binds the History page and its header: the average month with a little line chart on top,
 * then one trend card per measure, then the saved months. [root] is the whole screen because
 * the header lives in the app bar.
 */
class HistoryScreen(private val root: View, private val onClick: (HistoryEntry) -> Unit) {

    private val trends: ViewGroup = root.findViewById(R.id.trends)
    private val list: ViewGroup = root.findViewById(R.id.historyList)
    private val monthsHeader: View = root.findViewById(R.id.monthsHeader)
    private val empty: View = root.findViewById(R.id.historyEmpty)
    private val avgView: TextView = root.findViewById(R.id.historyAvg)
    private val captionView: TextView = root.findViewById(R.id.historyCaption)
    private val sparkline: SparklineView = root.findViewById(R.id.sparkline)
    private val inflater = LayoutInflater.from(root.context)
    private val context = root.context

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    fun show(entries: List<HistoryEntry>) {
        trends.removeAllViews()
        list.removeAllViews()
        val has = entries.isNotEmpty()
        empty.visibility = if (has) View.GONE else View.VISIBLE
        monthsHeader.visibility = if (has) View.VISIBLE else View.GONE
        if (!has) {
            avgView.text = "—"
            captionView.text = "Save a month to start your trends"
            sparkline.setData(emptyList())
            return
        }

        val recent = entries.sortedByDescending { it.month }.take(MAX_MONTHS)
        showHeader(recent)
        showTrends(recent)
        entries.forEach { list.addView(monthCard(it)) }
    }

    /** Fades the cards in, draws the little line and grows the bars. Called when the tab opens. */
    fun playIntro() {
        sparkline.animateIn()
        val cards = (0 until trends.childCount).map { trends.getChildAt(it) } +
            (0 until list.childCount).map { list.getChildAt(it) }
        Motion.riseIn(cards, firstDelay = 60, step = 70)
        for (i in 0 until trends.childCount) {
            trends.getChildAt(i).findViewById<BarChartView>(R.id.chart)?.animateIn()
        }
    }

    private fun showHeader(recent: List<HistoryEntry>) {
        val owing = Trend.of(recent) { it.owing }!!
        avgView.text = Bill.money(owing.average)
        captionView.text = if (recent.size == 1) {
            "Based on ${Months.label(recent.first().month)}"
        } else {
            "Over the last ${recent.size} months · latest ${Bill.money(owing.latest.value)}"
        }
        sparkline.setData(owing.series.map { it.value.toFloat() })
    }

    private fun showTrends(recent: List<HistoryEntry>) {
        val period = if (recent.size == 1) "1 month" else "last ${recent.size} months"

        Trend.of(recent) { it.waterUsed }?.let { t ->
            val avgCost = Trend.of(recent) { it.waterCost }!!.average
            addTrend(
                t, R.drawable.ic_water, "Water used",
                "kl per month, $period · avg cost ${Bill.money(avgCost)}", Palette.WATER,
            ) { "${round1(it)} kl" }
        }
        Trend.of(recent) { it.tokens }?.let { t ->
            val avgSpend = Trend.of(recent) { it.electricityCost }!!.average
            addTrend(
                t, R.drawable.ic_bolt, "Electricity tokens",
                "Tokens per month, $period · avg spend ${Bill.money(avgSpend)}", Palette.ELECTRICITY,
            ) { "${round1(it)} ${Bill.tokenWord(it)}" }
        }
        Trend.of(recent) { it.owing }?.let { t ->
            val sum = recent.fold(BigDecimal.ZERO) { acc, e -> acc + e.bill.owing }
            addTrend(
                t, R.drawable.ic_wallet, "Total owing",
                "Per month, $period · ${Bill.money(sum)} in all", Palette.TOTAL,
            ) { Bill.money(it) }
        }
    }

    private fun addTrend(
        t: Trend, icon: Int, title: String, subtitle: String, palette: Palette,
        format: (BigDecimal) -> String,
    ) {
        val card = inflater.inflate(R.layout.item_trend, trends, false)
        card.setBackgroundResource(palette.card)
        card.findViewById<ImageView>(R.id.icon).apply {
            setImageResource(icon)
            backgroundTintList = ColorStateList.valueOf(color(palette.container))
            imageTintList = ColorStateList.valueOf(color(palette.accent))
        }
        card.findViewById<TextView>(R.id.title).text = title
        card.findViewById<TextView>(R.id.subtitle).text = subtitle

        card.findViewById<BarChartView>(R.id.chart).apply {
            barColor = color(palette.accent)
            setData(
                t.series.map {
                    BarChartView.Bar(Months.short(it.month), it.value.toFloat(), format(it.value), Months.label(it.month))
                },
                average = if (t.series.size > 1) t.average.toFloat() else null,
                averageDisplay = format(t.average),
            )
        }

        val pct = t.latestVsOthersPercent
        val latestCaption = when {
            pct == null -> "First month saved"
            pct.signum() == 0 -> "Same as other months"
            pct.signum() > 0 -> "▲ $pct% vs others"
            else -> "▼ ${pct.negate()}% vs others"
        }
        tile(card, R.id.statAverage, "Average", format(t.average), "per month")
        val latest = tile(card, R.id.statLatest, "Latest", format(t.latest.value), latestCaption)
        // For bills, less is good: down is green, up is red.
        if (pct != null && pct.signum() != 0) {
            val good = pct.signum() < 0
            latest.setTextColor(color(if (good) R.color.trend_good else R.color.trend_bad))
            latest.setBackgroundResource(if (good) R.drawable.chip_good else R.drawable.chip_bad)
            val h = (8 * resources().displayMetrics.density).toInt()
            val v = (2 * resources().displayMetrics.density).toInt()
            latest.setPadding(h, v, h, v)
        }
        tile(card, R.id.statLowest, "Lowest", format(t.lowest.value), Months.medium(t.lowest.month))
        tile(card, R.id.statHighest, "Highest", format(t.highest.value), Months.medium(t.highest.month))
        trends.addView(card)
    }

    private fun resources() = context.resources

    /** Fills a stat tile and returns its caption (so the caller can colour it). */
    private fun tile(card: View, id: Int, label: String, value: String, caption: String): TextView {
        val tile = card.findViewById<View>(id)
        tile.findViewById<TextView>(R.id.label).text = label
        tile.findViewById<TextView>(R.id.value).text = value
        return tile.findViewById<TextView>(R.id.caption).also { it.text = caption }
    }

    private fun monthCard(entry: HistoryEntry): View {
        val b = entry.bill
        val card = inflater.inflate(R.layout.item_history, list, false)
        card.findViewById<TextView>(R.id.month).text = Months.label(entry.month)
        card.findViewById<TextView>(R.id.owing).text = Bill.money(b.owing)
        card.findViewById<SplitBarView>(R.id.split).apply {
            colors = intArrayOf(color(R.color.debt_accent), color(R.color.water_accent), color(R.color.elec_accent))
            trackColor = color(R.color.tile_bg)
            setShares(Shares.of(b.debt, b.waterCost, b.electricityCost), animate = false)
            contentDescription =
                "Debt ${Bill.money(b.debt)}, water ${Bill.money(b.waterCost)}, electricity ${Bill.money(b.electricityCost)}"
        }
        card.findViewById<TextView>(R.id.waterValue).text = "${Bill.num(b.waterUsed)} kl"
        card.findViewById<TextView>(R.id.waterCost).text = Bill.money(b.waterCost)
        card.findViewById<TextView>(R.id.elecValue).text = "${Bill.num(b.tokens)} ${Bill.tokenWord(b.tokens)}"
        card.findViewById<TextView>(R.id.elecCost).text = Bill.money(b.electricityCost)
        card.findViewById<TextView>(R.id.debtValue).text = Bill.money(b.debt)
        card.findViewById<View>(R.id.debtColumn).visibility = if (b.debt.signum() == 0) View.GONE else View.VISIBLE
        card.setOnClickListener { onClick(entry) }
        return card
    }

    private fun round1(v: BigDecimal) = Bill.num(v.setScale(1, RoundingMode.HALF_UP))

    companion object {
        /** Trends cover at most the last year of saved months. */
        const val MAX_MONTHS = 12
    }
}
