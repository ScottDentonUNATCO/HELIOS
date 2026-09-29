package com.omni.memory

import org.junit.Assert.*
import org.junit.Test

class HandoffPacketTest {
    private fun packet(stateJson: String) = HandoffPacket(
        taskId = "task-7",
        goal = "Build the boss fight",
        stateJson = stateJson,
        constraints = listOf("no network", "sideload only"),
        budgetTokens = 100_000L,
        attempts = 3,
        memoryRefIds = listOf("m1", "m2"),
        createdBy = "socket:openai",
        createdAtMs = 1_700_000_000_000L,
    )

    @Test fun `json round trip restores stateJson byte-exact`() {
        val state = "{\"code\":\"fun main() {\\n  println(\\\"hi \\\\ \\uD83D\\uDE00\\\")\\n}\",\"emoji\":\"\\uD83D\\uDE80\",\"nested\":{\"a\":[1,2,null]}}"
        val p = packet(state)
        val rt = HandoffPacket.fromJson(p.toJson())
        assertEquals(p, rt)
        assertEquals(state, rt.stateJson)
        assertEquals(p.constraints, rt.constraints)
        assertEquals(p.memoryRefIds, rt.memoryRefIds)
    }

    @Test fun `round trip with empty lists and unicode`() {
        val p = packet("{\"k\":\"日本語テスト\"}").copy(constraints = emptyList(), memoryRefIds = emptyList())
        assertEquals(p, HandoffPacket.fromJson(p.toJson()))
    }

    @Test fun `rejects wrong schemaVersion`() {
        val bad = """{"taskId":"t","schemaVersion":2,"goal":"g","stateJson":"{}","constraints":[],"budgetTokens":10,"attempts":0,"memoryRefIds":[],"createdBy":"c","createdAtMs":0}"""
        try {
            HandoffPacket.fromJson(bad); fail("expected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("schemaVersion"))
        }
    }

    @Test fun `rejects missing schemaVersion and bad direct construction`() {
        val missing = """{"taskId":"t","goal":"g","stateJson":"{}","constraints":[],"budgetTokens":10,"attempts":0,"memoryRefIds":[],"createdBy":"c","createdAtMs":0}"""
        try {
            HandoffPacket.fromJson(missing); fail("expected")
        } catch (e: IllegalArgumentException) {}
        try {
            packet("{}").copy(schemaVersion = 99); fail("expected")
        } catch (e: IllegalArgumentException) {}
    }
}
