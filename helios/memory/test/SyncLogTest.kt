package com.omni.memory

import org.junit.Assert.*
import org.junit.Test

class SyncLogTest {
    private fun rec(id: String, value: String, version: Long = 1) =
        MemoryRecord(id, MemoryScope.OBSERVATION, "key-$id", value, 0.5, 1000L, 1000L, version, "test")

    @Test fun `clean merge has no conflicts`() {
        val a = SyncLog("devA")
        val b = SyncLog("devB")
        a.append(SyncOpType.PUT, "r1", 1, rec("r1", "alpha"))
        b.append(SyncOpType.PUT, "r2", 1, rec("r2", "beta"))
        val res = a.merge(b)
        assertEquals(1, res.appliedOps.size)
        assertTrue(res.conflicts.isEmpty())
        assertEquals(0, res.losersPreserved)
        assertEquals("alpha", a.winningOp("r1")!!.record!!.value)
        assertEquals("beta", a.winningOp("r2")!!.record!!.value)
    }

    @Test fun `concurrent conflict preserves loser whole`() {
        val a = SyncLog("devA")
        val b = SyncLog("devB")
        val recA = rec("r", "value A")
        val recB = rec("r", "value B")
        a.append(SyncOpType.PUT, "r", 1, recA)                       // lamport 1
        b.append(SyncOpType.PUT, "dummy", 1, rec("dummy", "x"))      // lamport 1
        b.append(SyncOpType.PUT, "r", 1, recB)                       // lamport 2 -> wins
        val res = a.merge(b)
        assertEquals(1, res.conflicts.size)
        assertEquals(1, res.losersPreserved)
        val loser = res.conflicts[0]
        assertEquals("devA", loser.deviceId)
        assertEquals(recA, loser.record) // full copy preserved
        assertEquals(recB, a.winningOp("r")!!.record) // higher lamportTs won
    }

    @Test fun `delete vs put conflict preserves both`() {
        val a = SyncLog("devA")
        val b = SyncLog("devB")
        val putRec = rec("r", "keep me", version = 5)
        a.append(SyncOpType.PUT, "r", 5, putRec)      // lamport 1
        b.append(SyncOpType.DELETE, "r", 5, null)     // lamport 1, tie -> devB wins
        val res = a.merge(b)
        assertEquals(1, res.conflicts.size)
        assertEquals(putRec, res.conflicts[0].record) // the PUT survives as loser
        assertEquals(SyncOpType.DELETE, a.winningOp("r")!!.type)
        assertNull(a.winningOp("r")!!.record)
    }

    @Test fun `higher version wins without conflict`() {
        val a = SyncLog("devA")
        val b = SyncLog("devB")
        a.append(SyncOpType.PUT, "r", 1, rec("r", "old", version = 1))
        b.append(SyncOpType.PUT, "r", 2, rec("r", "new", version = 2))
        val res = a.merge(b)
        assertTrue(res.conflicts.isEmpty())
        assertEquals("new", a.winningOp("r")!!.record!!.value)
    }

    @Test fun `merge is idempotent`() {
        val a = SyncLog("devA")
        val b = SyncLog("devB")
        a.append(SyncOpType.PUT, "r1", 1, rec("r1", "a"))
        b.append(SyncOpType.PUT, "r2", 1, rec("r2", "b"))
        val first = a.merge(b)
        assertEquals(1, first.appliedOps.size)
        val second = a.merge(b)
        assertTrue(second.appliedOps.isEmpty())
        assertTrue(second.conflicts.isEmpty())
        assertEquals(2, a.ops().size)
    }
}
