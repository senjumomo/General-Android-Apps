package com.senjumomo.utilities

import org.junit.Assert.assertEquals
import org.junit.Test

class CollapseTest {
    @Test fun heightGoesFromFullToStrip() {
        assertEquals(300, Collapse.height(300, 52, 0f))
        assertEquals(176, Collapse.height(300, 52, 0.5f))
        assertEquals(52, Collapse.height(300, 52, 1f))
    }

    @Test fun heightIsClampedAndNeverBelowTheStrip() {
        assertEquals(52, Collapse.height(300, 52, 3f))
        assertEquals(300, Collapse.height(300, 52, -1f))
        assertEquals(40, Collapse.height(40, 52, 1f)) // nothing to shrink
    }

    @Test fun scrollingUpShrinksFirstAndOnlyUsesWhatIsLeft() {
        val (p, used) = Collapse.shrink(0f, 248, 100)
        assertEquals(100, used)
        assertEquals(100f / 248f, p, 1e-4f)

        val (p2, used2) = Collapse.shrink(0.9f, 248, 100)
        assertEquals(25, used2) // only 10% of 248 was left; the rest scrolls the page
        assertEquals(1f, p2, 1e-4f)
    }

    @Test fun pullingDownGrowsBackOnlyAsFarAsFull() {
        val (p, used) = Collapse.grow(1f, 248, 100)
        assertEquals(100, used)
        assertEquals(1f - 100f / 248f, p, 1e-4f)

        val (p2, used2) = Collapse.grow(0.1f, 248, 100)
        assertEquals(25, used2)
        assertEquals(0f, p2, 1e-4f)
    }

    @Test fun noRangeMeansNothingMoves() {
        assertEquals(0f to 0, Collapse.shrink(0f, 0, 50))
        assertEquals(0f to 0, Collapse.grow(0f, 0, 50))
    }

    @Test fun wrongDirectionDoesNothing() {
        assertEquals(0.5f to 0, Collapse.shrink(0.5f, 248, -10))
        assertEquals(0.5f to 0, Collapse.grow(0.5f, 248, -10))
    }
}
