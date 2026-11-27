package com.senjumomo.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun headerShrinksFasterThanTheFinger() {
        // 100 px of finger = 200 px of header at the default 2x.
        val (p, used) = Collapse.shrink(0f, 248, 100)
        assertEquals(100, used)
        assertEquals(200f / 248f, p, 1e-4f)
    }

    @Test fun onlyTheFingerTravelNeededIsUsedUp() {
        // 10% (25 px) of header is left: that takes 13 px of finger, the rest scrolls the page.
        val (p, used) = Collapse.shrink(0.9f, 248, 100)
        assertEquals(13, used)
        assertEquals(1f, p, 1e-4f)
    }

    @Test fun speedOfOneIsOneToOne() {
        val (p, used) = Collapse.shrink(0f, 248, 100, speed = 1f)
        assertEquals(100, used)
        assertEquals(100f / 248f, p, 1e-4f)
    }

    @Test fun pullingDownGrowsBackOnlyAsFarAsFull() {
        val (p, used) = Collapse.grow(1f, 248, 100)
        assertEquals(100, used)
        assertEquals(1f - 200f / 248f, p, 1e-4f)

        val (p2, used2) = Collapse.grow(0.1f, 248, 100)
        assertEquals(13, used2)
        assertEquals(0f, p2, 1e-4f)
    }

    @Test fun neverUsesMoreFingerThanWasMoved() {
        for (dy in 1..40) {
            assert(Collapse.shrink(0.37f, 248, dy).second <= dy)
            assert(Collapse.grow(0.37f, 248, dy).second <= dy)
        }
    }

    @Test fun noRangeMeansNothingMoves() {
        assertEquals(0f to 0, Collapse.shrink(0f, 0, 50))
        assertEquals(0f to 0, Collapse.grow(0f, 0, 50))
    }

    @Test fun wrongDirectionDoesNothing() {
        assertEquals(0.5f to 0, Collapse.shrink(0.5f, 248, -10))
        assertEquals(0.5f to 0, Collapse.grow(0.5f, 248, -10))
    }

    @Test fun halfOpenHeaderSettlesToTheNearerEnd() {
        assertEquals(1f, Collapse.settleTarget(0.7f))
        assertEquals(0f, Collapse.settleTarget(0.3f))
        assertNull(Collapse.settleTarget(0f))
        assertNull(Collapse.settleTarget(1f))
    }
}
