package com.omni.memory

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MemoryStoreTest {
    private lateinit var store: InMemoryStore

    @Before fun setup() { store = InMemoryStore() }

    private fun rec(
        id: String, value: String = "value", version: Long = 1,
        scope: MemoryScope = MemoryScope.OBSERVATION, salience: Double = 0.5,
        key: String = "key-$id",
    ) = MemoryRecord(id, scope, key, value, salience, 1000L, 1000L, version, "test")

    @Test fun `put and get round trip`() {
        store.put(rec("a", value = "hello"))
        assertEquals("hello", store.get("a")!!.value)
    }

    @Test fun `get missing returns null`() {
        assertNull(store.get("nope"))
    }

    @Test fun `upsert with bumped version wins`() {
        store.put(rec("a", value = "v1", version = 1))
        store.put(rec("a", value = "v2", version = 2))
        assertEquals("v2", store.get("a")!!.value)
    }

    @Test fun `stale version throws`() {
        store.put(rec("a", version = 2))
        try { store.put(rec("a", version = 2)); fail("expected") }
        catch (e: StaleVersionException) {}
        try { store.put(rec("a", version = 1)); fail("expected") }
        catch (e: StaleVersionException) {}
        assertEquals(2L, store.get("a")!!.version) // stored record untouched
    }

    @Test fun `delete removes and reports`() {
        store.put(rec("a"))
        assertTrue(store.delete("a"))
        assertNull(store.get("a"))
        assertFalse(store.delete("a"))
    }

    @Test fun `listByScope filters`() {
        store.put(rec("a", scope = MemoryScope.PREFERENCE))
        store.put(rec("b", scope = MemoryScope.OBSERVATION))
        store.put(rec("c", scope = MemoryScope.PREFERENCE))
        assertEquals(setOf("a", "c"), store.listByScope(MemoryScope.PREFERENCE).map { it.id }.toSet())
        assertEquals(listOf("b"), store.listByScope(MemoryScope.OBSERVATION).map { it.id })
    }

    @Test fun `search exact key match outranks body match`() {
        store.put(rec("exact", key = "photon-drive", value = "nothing relevant here"))
        store.put(rec("body", key = "misc-notes", value = "the photon-drive manual lives here"))
        store.put(rec("other", key = "unrelated", value = "no match at all"))
        val res = store.search("photon-drive")
        assertEquals(listOf("exact", "body"), res.map { it.id })
        // case-insensitive
        assertEquals(listOf("exact", "body"), store.search("PHOTON-DRIVE").map { it.id })
    }

    @Test fun `search blank query returns empty`() {
        store.put(rec("a"))
        assertTrue(store.search("").isEmpty())
        assertTrue(store.search("   ").isEmpty())
    }

    @Test fun `topK respects limit and ranker order`() {
        store.put(rec("a", salience = 0.1))
        store.put(rec("b", salience = 0.9))
        store.put(rec("c", salience = 0.5))
        val top = store.topK(2) { it.salience }
        assertEquals(listOf("b", "c"), top.map { it.id })
        assertEquals(3, store.topK(99) { it.salience }.size)
    }

    @Test fun `put auto-scrubs secrets from value`() {
        store.put(rec("s", value = "my key is sk-live-abc123 and ref vault:openai"))
        val v = store.get("s")!!.value
        assertTrue(v.contains("sk-[REDACTED]"))
        assertTrue(v.contains("vault:[REDACTED]"))
        assertFalse(v.contains("sk-live-abc123"))
        assertFalse(v.contains("vault:openai"))
    }
}
