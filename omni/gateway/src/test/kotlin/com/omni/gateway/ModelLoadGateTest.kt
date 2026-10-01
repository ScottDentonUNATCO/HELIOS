package com.omni.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RAM-gate policy: one-at-a-time model loading bounded by real device RAM. */
class ModelLoadGateTest {

    private val total8 = 8L * 1024 * 1024 * 1024 // Moto-class phone
    private val free6 = 6L * 1024 * 1024 * 1024

    @Test fun `small model with headroom is allowed`() {
        val d = ModelLoadGate.decide(
            modelBytes = 500L * 1024 * 1024,
            totalMemBytes = total8,
            availMemBytes = free6,
        )
        assertTrue(d.reason, d.allowed)
    }

    @Test fun `model over half of total ram is denied`() {
        val d = ModelLoadGate.decide(
            modelBytes = 5L * 1024 * 1024 * 1024, // 5 GB of 8 GB total
            totalMemBytes = total8,
            availMemBytes = free6,
        )
        assertFalse(d.reason, d.allowed)
        assertTrue(d.reason.contains("50%"))
    }

    @Test fun `model that would starve the os is denied`() {
        val d = ModelLoadGate.decide(
            modelBytes = 2L * 1024 * 1024 * 1024,
            totalMemBytes = total8,
            availMemBytes = 2_200L * 1024 * 1024, // leaves < 512 MB free
        )
        assertFalse(d.reason, d.allowed)
        assertTrue(d.reason.contains("headroom"))
    }

    @Test fun `boundary model leaving exactly the headroom is allowed`() {
        val avail = 3L * 1024 * 1024 * 1024
        val model = avail - ModelLoadGate.MIN_FREE_HEADROOM_BYTES
        val d = ModelLoadGate.decide(model, total8, avail)
        assertTrue(d.reason, d.allowed)
    }

    @Test fun `boundary model one byte over half of total is denied`() {
        val model = (total8 * ModelLoadGate.MAX_MODEL_FRACTION).toLong() + 1
        val d = ModelLoadGate.decide(model, total8, free6)
        assertFalse(d.reason, d.allowed)
    }
}
