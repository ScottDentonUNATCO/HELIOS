package com.omni.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyRouterTest {

    private var now = 1_000_000L
    private fun router() = KeyRouter(clock = { now })

    private fun key(id: String, provider: String = "openai", label: String = id) =
        KeyInstance(id = id, providerId = provider, label = label, vaultRef = "vault:$id")

    @Test
    fun `round-robins across keys`() {
        val r = router()
        val keys = listOf(key("k1"), key("k2"), key("k3"))
        assertEquals("k1", r.pickKey("openai", keys)?.id)
        assertEquals("k2", r.pickKey("openai", keys)?.id)
        assertEquals("k3", r.pickKey("openai", keys)?.id)
        assertEquals("k1", r.pickKey("openai", keys)?.id) // wraps
    }

    @Test
    fun `429 skips key with backoff, next key tried immediately`() {
        val r = router()
        val keys = listOf(key("k1"), key("k2"))
        assertEquals("k1", r.pickKey("openai", keys)?.id)
        val cooldown = r.record429("k1")
        assertTrue(cooldown >= 30_000, "cooldown=$cooldown")
        // k1 is cooling down; k2 picked
        assertEquals("k2", r.pickKey("openai", keys)?.id)
        assertEquals("k2", r.pickKey("openai", keys)?.id) // k1 still down
    }

    @Test
    fun `429 backoff is exponential`() {
        val r = router()
        val c1 = r.record429("k1")
        now += c1 + 1 // let cooldown expire
        val c2 = r.record429("k1")
        assertTrue(c2 > c1, "c1=$c1 c2=$c2")
    }

    @Test
    fun `success clears 429 cooldown`() {
        val r = router()
        val keys = listOf(key("k1"), key("k2"))
        r.record429("k1")
        assertEquals("k2", r.pickKey("openai", keys)?.id)
        r.recordSuccess("k1", 10, 10, 0.001)
        // k1 healthy again; round-robin continues
        val picked = r.pickKey("openai", keys)?.id
        assertNotNull(picked)
    }

    @Test
    fun `daily token budget exhausts key`() {
        val r = router()
        val keys = listOf(key("k1").copy(dailyTokenBudget = 100))
        assertNotNull(r.pickKey("openai", keys))
        r.recordSuccess("k1", 60, 50, 0.0) // 110 tokens > 100 budget
        assertNull(r.pickKey("openai", keys), "key should be exhausted")
        assertEquals("daily budget exhausted", r.status(keys[0]))
    }

    @Test
    fun `monthly spend budget exhausts key`() {
        val r = router()
        val keys = listOf(key("k1").copy(monthlySpendBudgetUsd = 1.0))
        assertNotNull(r.pickKey("openai", keys))
        r.recordSuccess("k1", 100, 100, 1.5)
        assertNull(r.pickKey("openai", keys), "key should be exhausted")
        assertEquals("monthly budget exhausted", r.status(keys[0]))
    }

    @Test
    fun `disabled key is skipped`() {
        val r = router()
        val keys = listOf(key("k1").copy(enabled = false), key("k2"))
        assertEquals("k2", r.pickKey("openai", keys)?.id)
        assertEquals("disabled", r.status(keys[0]))
    }

    @Test
    fun `all keys down returns null`() {
        val r = router()
        val keys = listOf(key("k1"), key("k2"))
        r.record429("k1")
        r.record429("k2")
        assertNull(r.pickKey("openai", keys))
    }

    @Test
    fun `providers have independent cursors`() {
        val r = router()
        val oa = listOf(key("oa1", "openai"), key("oa2", "openai"))
        val an = listOf(key("an1", "anthropic"), key("an2", "anthropic"))
        assertEquals("oa1", r.pickKey("openai", oa)?.id)
        assertEquals("an1", r.pickKey("anthropic", an)?.id)
        assertEquals("oa2", r.pickKey("openai", oa)?.id)
        assertEquals("an2", r.pickKey("anthropic", an)?.id)
    }

    @Test
    fun `status reports rate-limited with seconds`() {
        val r = router()
        val k = key("k1")
        r.record429("k1")
        val s = r.status(k)
        assertTrue(s.startsWith("rate-limited"), "status=$s")
    }
}
