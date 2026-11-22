package com.senjumomo.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrendTest {
    private fun entry(month: String, used: String) = HistoryEntry(
        month,
        Bill("0".toBigDecimal(), "0".toBigDecimal(), used.toBigDecimal(), "70".toBigDecimal(),
            "1".toBigDecimal(), "100".toBigDecimal()),
        0L,
    )

    @Test
    fun summarisesWaterUse() {
        // Deliberately out of order.
        val t = Trend.of(
            listOf(entry("2026-10", "12"), entry("2026-11", "15"), entry("2026-09", "9")),
        ) { it.waterUsed }!!

        assertEquals(listOf("2026-09", "2026-10", "2026-11"), t.series.map { it.month })
        assertEquals("12.00".toBigDecimal(), t.average)
        assertEquals("2026-09", t.lowest.month)
        assertEquals("2026-11", t.highest.month)
        assertEquals("2026-11", t.latest.month)
        // 15 vs average of 9 and 12 (10.5) = +43%
        assertEquals("43".toBigDecimal(), t.latestVsOthersPercent)
    }

    @Test
    fun singleMonthHasNoComparison() {
        val t = Trend.of(listOf(entry("2026-11", "10"))) { it.waterUsed }!!
        assertNull(t.latestVsOthersPercent)
        assertEquals(t.lowest, t.highest)
        assertNull(Trend.of(emptyList()) { it.waterUsed })
    }

    @Test
    fun monthMaths() {
        assertEquals("2027-01", Months.shift("2026-12", 1))
        assertEquals("2025-12", Months.shift("2026-01", -1))
        assertEquals(24, Months.choices().size)
        assertEquals(Months.shift(Months.current(), 1), Months.choices().first())
    }
}
