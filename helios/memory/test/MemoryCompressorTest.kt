package com.omni.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryCompressorTest {
    private val c = MemoryCompressor()
    private val bySalience: RecordRanker = { it.salience }

    private fun rec(
        id: String, salience: Double, pinned: Boolean = false,
        scope: MemoryScope = MemoryScope.OBSERVATION, value: String = "v",
    ) = MemoryRecord(id, scope, "key-$id", value, salience, 1000L, 1000L, 1L, "test", pinned)

    @Test fun `pinned always kept verbatim even over budget`() {
        val pinnedVal = "P".repeat(400)
        val pinned = rec("p1", salience = 0.0, pinned = true, value = pinnedVal)
        val others = (1..3).map { rec("o$it", salience = 0.9, value = "O".repeat(400)) }
        val res = c.compress(listOf(pinned) + others, tokenBudget = 10, bySalience)
        val keptPinned = res.kept.first { it.id == "p1" }
        assertEquals(pinnedVal, keptPinned.value) // verbatim, not summarized
        assertEquals(0, res.dropped)
    }

    @Test fun `budget respected and highest salience kept`() {
        val rs = listOf(
            rec("low", 0.1, value = "L".repeat(400)),
            rec("high", 0.9, value = "H".repeat(400)),
            rec("mid", 0.5, value = "M".repeat(400)),
        )
        val budget = 250
        val res = c.compress(rs, budget, bySalience)
        assertEquals(setOf("high", "mid"), res.kept.map { it.id }.toSet())
        val keptTokens = res.kept.sumOf { c.tokensOf(it) }
        assertTrue("kept $keptTokens tokens over budget $budget", keptTokens <= budget)
        assertEquals(0, res.dropped)
    }

    @Test fun `dropped is always zero even with zero budget`() {
        val rs = (1..10).map { rec("r$it", 0.5, value = "v$it") }
        val res = c.compress(rs, tokenBudget = 0, bySalience)
        assertEquals(0, res.dropped)
        assertTrue(res.kept.isEmpty())
        assertEquals(10, res.summaries.sumOf {
            it.value.substringAfter("count=").substringBefore("\n").toInt()
        })
    }

    @Test fun `one summary per folded scope referencing every folded id`() {
        val rs = listOf(
            rec("a1", 0.9, scope = MemoryScope.PROJECT_STATE, value = "A1".repeat(100)),
            rec("a2", 0.8, scope = MemoryScope.PROJECT_STATE, value = "A2".repeat(100)),
            rec("b1", 0.7, scope = MemoryScope.PREFERENCE, value = "B1".repeat(100)),
            rec("keep", 1.0, scope = MemoryScope.PREFERENCE, value = "K"),
        )
        val res = c.compress(rs, tokenBudget = 50, bySalience)
        assertTrue(res.kept.any { it.id == "keep" })
        val foldedIds = rs.map { it.id }.toSet() - res.kept.map { it.id }.toSet()
        assertEquals(setOf("a1", "a2", "b1"), foldedIds)
        assertEquals(2, res.summaries.size) // one per scope
        val mentioned = res.summaries.flatMap { s ->
            foldedIds.filter { id -> s.value.contains(id) }
        }.toSet()
        assertEquals(foldedIds, mentioned)
        val ps = res.summaries.first { it.scope == MemoryScope.PROJECT_STATE }
        assertTrue(ps.value.contains("count=2"))
        assertTrue(ps.value.contains("a1:")) // extractive excerpt present
    }

    @Test fun `kept plus folded accounts for every record`() {
        val rs = (1..7).map { rec("r$it", salience = it / 10.0, value = "V".repeat(200)) }
        val res = c.compress(rs, tokenBudget = 150, bySalience)
        val foldedCount = res.summaries.sumOf {
            it.value.substringAfter("count=").substringBefore("\n").toInt()
        }
        assertEquals(rs.size, res.kept.size + foldedCount)
        assertEquals(0, res.dropped)
    }
}
