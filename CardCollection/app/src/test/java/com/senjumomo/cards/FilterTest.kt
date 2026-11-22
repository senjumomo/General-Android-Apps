package com.senjumomo.cards

import com.senjumomo.cards.ui.Filter
import com.senjumomo.cards.ui.Money
import com.senjumomo.cards.ui.Sort
import com.senjumomo.cards.ui.Summary
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterTest {
    private val cards = Samples.cards

    @Test
    fun searchesAcrossFields() {
        assertEquals(2, Filter(query = "hero").apply(cards).size)
        assertEquals(1, Filter(query = "maze-en058").apply(cards).size)
        assertEquals(1, Filter(query = "hero mavens").apply(cards).size) // all words must match
        assertEquals(2, Filter(query = "secret").apply(cards).size)
    }

    @Test
    fun filtersAndSorts() {
        val mavens = Filter(set = "Magnificent Mavens").apply(cards)
        assertEquals(listOf("A Hero Lives", "Called by the Grave"), mavens.map { it.name })
        assertEquals("Ash Blossom & Joyous Spring", Filter(sort = Sort.VALUE).apply(cards).first().name)
        assertEquals("Called by the Grave", Filter(sort = Sort.RECENT).apply(cards).first().name)
        assertEquals("Dark Magician", Filter(sort = Sort.QUANTITY).apply(cards).first().name)
    }

    @Test
    fun summarises() {
        val s = Summary.of(cards)
        assertEquals(11, s.copies)
        assertEquals(7, s.unique)
        // Gain only counts cards with a paid price (Called by the Grave has none).
        assertEquals(s.valueOfPaidCents - s.paidCents, s.gainCents)
        assertEquals(s.valueCents - 1260, s.valueOfPaidCents)
    }

    @Test
    fun formatsMoney() {
        assertEquals("R9,843.50", Money.format(984350))
        assertEquals("+R3.80", Money.signed(380))
        assertEquals("−R12.00", Money.signed(-1200))
        assertEquals("R0.00", Money.format(0))
    }
}
