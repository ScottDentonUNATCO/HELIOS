package com.omni.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryRecordTest {
    private fun rec(id: String = "r1", key: String = "k", salience: Double = 0.5) =
        MemoryRecord(id, MemoryScope.OBSERVATION, key, "v", salience, 1000L, 1000L, 1L, "test")

    @Test fun `blank id rejected`() {
        try { rec(id = ""); fail("expected") } catch (e: IllegalArgumentException) {}
        try { rec(id = "   "); fail("expected") } catch (e: IllegalArgumentException) {}
    }

    @Test fun `blank key rejected`() {
        try { rec(key = ""); fail("expected") } catch (e: IllegalArgumentException) {}
        try { rec(key = "  "); fail("expected") } catch (e: IllegalArgumentException) {}
    }

    @Test fun `salience below zero rejected`() {
        try { rec(salience = -0.1); fail("expected") } catch (e: IllegalArgumentException) {}
    }

    @Test fun `salience above one rejected`() {
        try { rec(salience = 1.1); fail("expected") } catch (e: IllegalArgumentException) {}
    }

    @Test fun `salience NaN rejected`() {
        try { rec(salience = Double.NaN); fail("expected") } catch (e: IllegalArgumentException) {}
    }

    @Test fun `salience boundaries accepted`() {
        rec(salience = 0.0); rec(salience = 1.0)
    }

    @Test fun `bump increments version and refreshes timestamp`() {
        val r = rec()
        Thread.sleep(3)
        val b = r.bump()
        assertEquals(r.version + 1, b.version)
        assertTrue(b.updatedAtMs >= r.updatedAtMs)
        assertEquals(r.createdAtMs, b.createdAtMs)
        assertEquals(r.id, b.id)
        assertEquals(r.value, b.value)
    }
}
