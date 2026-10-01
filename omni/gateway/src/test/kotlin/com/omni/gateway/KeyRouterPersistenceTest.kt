package com.omni.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeyRouter.restoreUsage] / [KeyRouter.snapshotAll]: usage counters survive
 * a simulated app restart, and stale calendar markers roll over instead of
 * resurrecting yesterday's budget consumption.
 */
class KeyRouterPersistenceTest {

    private fun dayOf(year: Int, month0: Int, day: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(year, month0, day, 12, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun `snapshot then restore preserves counters`() {
        var now = dayOf(2026, 9, 30) // Oct 1 2026
        val router = KeyRouter(clock = { now })
        val key = KeyInstance("k1", "p", "A", "ref")
        router.recordSuccess("k1", 1_000, 500, 0.05)

        val snaps = router.snapshotAll()
        assertEquals(1, snaps.size)
        assertEquals(1_500L, snaps[0].tokensToday)
        assertEquals(0.05, snaps[0].spendMonthUsd, 1e-12)

        // Simulated restart: fresh router, same day.
        val fresh = KeyRouter(clock = { now })
        assertTrue(fresh.isEligible(key.copy(dailyTokenBudget = 1_000_000)))
        fresh.restoreUsage(snaps[0])

        val snap = fresh.usageSnapshot("k1")
        assertEquals(1_500L, snap.tokensToday)
        assertEquals(0.05, snap.spendMonthUsd, 1e-12)
    }

    @Test
    fun `restore from a previous day rolls counters over`() {
        val yesterday = dayOf(2026, 8, 30) // Sep 30 2026 (months are 0-based)
        val today = dayOf(2026, 9, 1) // Oct 1 2026
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = yesterday }
        val stale = PersistedKeyUsage(
            keyId = "k1",
            tokensToday = 999_999,
            spendMonthUsd = 99.0,
            dayOfYear = cal.get(java.util.Calendar.DAY_OF_YEAR),
            monthKey = 2026 * 12 + 8 // September
        )

        val fresh = KeyRouter(clock = { today })
        fresh.restoreUsage(stale)

        // Both the day and the month rolled over: counters start fresh.
        val snap = fresh.usageSnapshot("k1")
        assertEquals(0L, snap.tokensToday)
        assertEquals(0.0, snap.spendMonthUsd, 1e-12)
    }

    @Test
    fun `restore keeps monthly spend when only the day rolled over`() {
        val sep15 = dayOf(2026, 8, 15) // Sep 15 2026 (months are 0-based)
        val sep16 = dayOf(2026, 8, 16) // Sep 16 2026
        val cal15 = java.util.Calendar.getInstance().apply { timeInMillis = sep15 }
        val snapSep15 = PersistedKeyUsage(
            keyId = "k1",
            tokensToday = 5_000,
            spendMonthUsd = 1.5,
            dayOfYear = cal15.get(java.util.Calendar.DAY_OF_YEAR),
            monthKey = 2026 * 12 + 8 // September
        )
        val fresh = KeyRouter(clock = { sep16 })
        fresh.restoreUsage(snapSep15)

        val snap = fresh.usageSnapshot("k1")
        assertEquals(0L, snap.tokensToday) // daily counter reset...
        assertEquals(1.5, snap.spendMonthUsd, 1e-12) // ...monthly spend kept
    }
}
