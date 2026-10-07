package com.omni.app.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * JVM tests for the design-gems UI logic (docs/design-gems.md). The Compose
 * screens themselves are verified by a successful app compile; these cover
 * every pure helper behind the new meters, signifiers, and expanders.
 */
class DesignGemsTest {

    private fun assertNear(expected: Float, actual: Float, eps: Float = 1e-6f) {
        assertTrue("expected ~$expected but was $actual", abs(expected - actual) <= eps)
    }

    // ---- budgetFraction (Tufte spend meter) ----

    @Test fun budgetFraction_halfSpent() = assertNear(0.5f, budgetFraction(2.5, 5.0))

    @Test fun budgetFraction_clampsOverCap() = assertNear(1f, budgetFraction(9.0, 5.0))

    @Test fun budgetFraction_zeroSpent() = assertNear(0f, budgetFraction(0.0, 5.0))

    @Test fun budgetFraction_negativeSpent() = assertNear(0f, budgetFraction(-1.0, 5.0))

    @Test fun budgetFraction_zeroCap_neverNaN() {
        val f = budgetFraction(3.0, 0.0)
        assertNear(0f, f)
        assertFalse(f.isNaN())
    }

    @Test fun budgetFraction_negativeCap_neverNaN() {
        val f = budgetFraction(3.0, -5.0)
        assertNear(0f, f)
        assertFalse(f.isNaN())
    }

    // ---- tokenBudgetFraction / spendBudgetFraction (per-key usage bars) ----

    @Test fun tokenBudgetFraction_nullBudget_isNull() =
        assertNull(tokenBudgetFraction(500L, null))

    @Test fun tokenBudgetFraction_zeroBudget_isZeroNotNaN() {
        val f = tokenBudgetFraction(500L, 0L)!!
        assertNear(0f, f)
        assertFalse(f.isNaN())
    }

    @Test fun tokenBudgetFraction_overBudget_clamps() =
        assertNear(1f, tokenBudgetFraction(2000L, 1000L)!!)

    @Test fun tokenBudgetFraction_partial() =
        assertNear(0.25f, tokenBudgetFraction(250L, 1000L)!!)

    @Test fun spendBudgetFraction_nullBudget_isNull() =
        assertNull(spendBudgetFraction(1.0, null))

    @Test fun spendBudgetFraction_partial() =
        assertNear(0.2f, spendBudgetFraction(2.0, 10.0)!!)

    // ---- formatting ----

    @Test fun formatCompactUsd_twoDecimals() {
        assertEquals("$0.42", formatCompactUsd(0.42))
        assertEquals("$12.50", formatCompactUsd(12.5))
        assertEquals("$0.00", formatCompactUsd(0.0))
    }

    @Test fun formatCompactTokens_plain() = assertEquals("842", formatCompactTokens(842))

    @Test fun formatCompactTokens_thousands() {
        assertEquals("1.5k", formatCompactTokens(1500))
        assertEquals("12k", formatCompactTokens(12_000))
    }

    @Test fun formatCompactTokens_millions() {
        assertEquals("2.4M", formatCompactTokens(2_400_000))
        assertEquals("1M", formatCompactTokens(1_000_000))
    }

    // ---- compressorSegments (Tufte memory meter) ----

    @Test fun compressorSegments_sumsToOne() {
        val (k, f, d) = compressorSegments(kept = 10, folded = 5, dropped = 5)
        assertNear(1f, k + f + d)
        assertNear(0.5f, k)
        assertNear(0.25f, f)
        assertNear(0.25f, d)
    }

    @Test fun compressorSegments_allZero_neverNaN() {
        val (k, f, d) = compressorSegments(0, 0, 0)
        assertNear(0f, k + f + d)
        assertFalse(k.isNaN() || f.isNaN() || d.isNaN())
    }

    // ---- isRateLimitedStatus (Norman: ticker only for live countdowns) ----

    @Test fun isRateLimitedStatus_trueForCountdown() =
        assertTrue(isRateLimitedStatus("rate-limited (12s)"))

    @Test fun isRateLimitedStatus_falseForOthers() {
        assertFalse(isRateLimitedStatus("ready"))
        assertFalse(isRateLimitedStatus("disabled"))
        assertFalse(isRateLimitedStatus("daily budget exhausted"))
        assertFalse(isRateLimitedStatus("monthly budget exhausted"))
    }

    // ---- defaultKeysExpanded (Zen of Palm: clutter only where setup is pending) ----

    @Test fun defaultKeysExpanded_opensWhenEmpty() =
        assertTrue(defaultKeysExpanded(0))

    @Test fun defaultKeysExpanded_collapsesWhenConfigured() {
        assertFalse(defaultKeysExpanded(1))
        assertFalse(defaultKeysExpanded(5))
    }
}
