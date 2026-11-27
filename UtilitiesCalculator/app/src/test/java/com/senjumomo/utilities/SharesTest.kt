package com.senjumomo.utilities

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SharesTest {
    private fun d(v: String) = v.toBigDecimal()

    @Test fun splitsInProportionAndAddsUpToOne() {
        val s = Shares.of(d("900"), d("700"), d("1000"))
        assertEquals(0.3461538f, s[0], 1e-5f)
        assertEquals(0.2692308f, s[1], 1e-5f)
        assertEquals(0.3846154f, s[2], 1e-5f)
        assertEquals(1f, s.sum(), 1e-4f)
    }

    @Test fun nothingToDrawWhenEverythingIsZero() {
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), Shares.of(d("0"), null, d("0")), 0f)
    }

    @Test fun missingAndNegativeAmountsCountAsZero() {
        val s = Shares.of(d("-50"), null, d("200"))
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), s, 0f)
    }

    @Test fun oneAmountTakesTheWholeBar() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), Shares.of(d("900"), d("0"), d("0")), 0f)
    }
}

class StepperTest {
    @Test fun addsAndSubtractsWholeTokens() {
        assertEquals("11", Stepper.step("10", 1))
        assertEquals("9", Stepper.step("10", -1))
    }

    @Test fun emptyOrInvalidStartsFromZero() {
        assertEquals("1", Stepper.step("", 1))
        assertEquals("1", Stepper.step(null, 1))
        assertEquals("1", Stepper.step("abc", 1))
    }

    @Test fun neverGoesBelowZero() {
        assertEquals("0", Stepper.step("0", -1))
        assertEquals("0", Stepper.step("", -1))
        assertEquals("0", Stepper.step("0.5", -1))
    }

    @Test fun keepsDecimalsAndAcceptsCommas() {
        assertEquals("3.5", Stepper.step("2.5", 1))
        assertEquals("3.5", Stepper.step("2,5", 1))
    }
}
