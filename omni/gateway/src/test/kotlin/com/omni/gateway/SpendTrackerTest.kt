package com.omni.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpendTrackerTest {

    @Test
    fun `unknown model price records nothing`() {
        val tracker = SpendTracker()
        tracker.record(1_000_000, 1_000_000, null)
        assertEquals(0.0, tracker.totalUsd(), 1e-9)
    }

    @Test
    fun `totals accumulate correctly`() {
        val tracker = SpendTracker()
        tracker.record(1_000_000, 500_000, 2.0 to 4.0) // 2.00 + 2.00
        tracker.record(500_000, 0, 2.0 to 4.0) // +1.00
        assertEquals(5.0, tracker.totalUsd(), 1e-9)
    }

    @Test
    fun `budget cap blocks when exceeded`() {
        val tracker = SpendTracker()
        tracker.record(1_000_000, 0, 2.0 to 4.0)
        assertTrue(tracker.checkBudget(2.0))
        assertTrue(tracker.checkBudget(10.0))
        assertFalse(tracker.checkBudget(1.99))
    }

    @Test
    fun `reset clears the total`() {
        val tracker = SpendTracker()
        tracker.record(1_000_000, 0, 2.0 to 4.0)
        tracker.reset()
        assertEquals(0.0, tracker.totalUsd(), 1e-9)
        assertTrue(tracker.checkBudget(0.0))
    }
}
