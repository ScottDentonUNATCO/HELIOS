package com.omni.memory

import org.junit.Assert.*
import org.junit.Test

class AdaptiveRankerTest {
    private val now = 1_700_000_000_000L
    private val dayMs = 86_400_000L

    private fun rec(
        id: String, salience: Double = 0.0, updatedAtMs: Long = now,
        pinned: Boolean = false, scope: MemoryScope = MemoryScope.OBSERVATION,
    ) = MemoryRecord(id, scope, "key-$id", "v", salience, now, updatedAtMs, 1L, "test", pinned)

    @Test fun `higher salience ranks first`() {
        val lo = rec("lo", salience = 0.1)
        val hi = rec("hi", salience = 0.9)
        val ranked = AdaptiveRanker.rank(listOf(lo, hi), now, emptyMap())
        assertEquals(listOf("hi", "lo"), ranked.map { it.id })
    }

    @Test fun `thirty day decay halves recency contribution`() {
        val fresh = rec("fresh", updatedAtMs = now)
        val old = rec("old", updatedAtMs = now - 30 * dayMs)
        // salience 0, no accesses, unpinned, OBSERVATION -> score = 0.3 * recency
        assertEquals(0.3, AdaptiveRanker.score(fresh, now, emptyMap()), 1e-9)
        assertEquals(0.15, AdaptiveRanker.score(old, now, emptyMap()), 1e-9)
        val older = rec("older", updatedAtMs = now - 60 * dayMs)
        assertEquals(0.075, AdaptiveRanker.score(older, now, emptyMap()), 1e-9)
    }

    @Test fun `pinned records immune to recency decay`() {
        val pinnedOld = rec("po", updatedAtMs = now - 90 * dayMs, pinned = true)
        val pinnedFresh = rec("pf", updatedAtMs = now, pinned = true)
        // recency forced to 1.0 + 0.5 pinBoost, regardless of age
        assertEquals(0.8, AdaptiveRanker.score(pinnedOld, now, emptyMap()), 1e-9)
        assertEquals(0.8, AdaptiveRanker.score(pinnedFresh, now, emptyMap()), 1e-9)
    }

    @Test fun `project state and procedure get scope boost`() {
        val proj = rec("proj", scope = MemoryScope.PROJECT_STATE)
        val proc = rec("proc", scope = MemoryScope.PROCEDURE)
        val obs = rec("obs", scope = MemoryScope.OBSERVATION)
        assertEquals(0.4, AdaptiveRanker.score(proj, now, emptyMap()), 1e-9)
        assertEquals(0.4, AdaptiveRanker.score(proc, now, emptyMap()), 1e-9)
        assertEquals(0.3, AdaptiveRanker.score(obs, now, emptyMap()), 1e-9)
        assertEquals(
            listOf("proj", "proc", "obs"),
            AdaptiveRanker.rank(listOf(obs, proj, proc), now, emptyMap()).map { it.id }.take(3),
        )
    }

    @Test fun `access frequency boosts re-read records`() {
        val hot = rec("hot")
        val cold = rec("cold")
        val accesses = mapOf("hot" to 9)
        val diff = AdaptiveRanker.score(hot, now, accesses) - AdaptiveRanker.score(cold, now, accesses)
        assertEquals(0.2, diff, 1e-9) // ln(10)/ln(10) = 1.0 * 0.2
        assertTrue(AdaptiveRanker.rank(listOf(cold, hot), now, accesses).first().id == "hot")
    }
}
