package com.senjumomo.utilities

import java.math.BigDecimal
import java.math.RoundingMode

/** A value for one month. */
data class MonthValue(val month: String, val value: BigDecimal)

/** Summary of one measure (e.g. kl used) across saved months. */
data class Trend(
    /** Oldest first, for charting. */
    val series: List<MonthValue>,
    val average: BigDecimal,
    val lowest: MonthValue,
    val highest: MonthValue,
    val latest: MonthValue,
    /** Latest month vs the average of all other months, in percent. Null with < 2 months. */
    val latestVsOthersPercent: BigDecimal?,
) {
    companion object {
        /** Null when there are no entries. Entries may be in any order. */
        fun of(entries: List<HistoryEntry>, measure: (Bill) -> BigDecimal): Trend? {
            if (entries.isEmpty()) return null
            val series = entries.sortedBy { it.month }.map { MonthValue(it.month, measure(it.bill)) }
            val latest = series.last()
            val others = series.dropLast(1)
            val vsOthers = if (others.isEmpty()) null else {
                val othersAvg = average(others)
                if (othersAvg.signum() == 0) null
                else (latest.value - othersAvg)
                    .multiply(BigDecimal(100))
                    .divide(othersAvg, 0, RoundingMode.HALF_UP)
            }
            return Trend(
                series = series,
                average = average(series),
                // On ties, the most recent month wins.
                lowest = series.reversed().minBy { it.value },
                highest = series.reversed().maxBy { it.value },
                latest = latest,
                latestVsOthersPercent = vsOthers,
            )
        }

        private fun average(values: List<MonthValue>): BigDecimal =
            values.fold(BigDecimal.ZERO) { acc, v -> acc + v.value }
                .divide(BigDecimal(values.size), 2, RoundingMode.HALF_UP)
    }
}
