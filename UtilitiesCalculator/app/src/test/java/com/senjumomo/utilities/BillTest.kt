package com.senjumomo.utilities

import org.junit.Assert.assertEquals
import org.junit.Test

class BillTest {
    private fun bd(s: String) = s.toBigDecimal()

    @Test
    fun matchesTheExampleMessage() {
        val bill = Bill(bd("900"), bd("800"), bd("810"), bd("70"), bd("10"), bd("100"))
        assertEquals(
            """
            Previous Month's Debt: R900

            Water: 810kl - 800kl = 10kl x R70 = R700

            Electricity: 10 tokens x R100 = R1000

            Owing = R2600
            """.trimIndent(),
            bill.message(),
        )
    }

    @Test
    fun handlesCentsAndCommas() {
        assertEquals("R12.50", Bill.money(bd("12.5")))
        assertEquals("R0", Bill.money(bd("0")))
        assertEquals(bd("12.5"), Bill.parse("12,5"))
        assertEquals(null, Bill.parse(""))
        assertEquals("1 token", "1 ${Bill.tokenWord(bd("1"))}")
    }
}
