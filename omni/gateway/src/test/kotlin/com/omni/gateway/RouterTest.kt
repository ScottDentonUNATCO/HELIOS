package com.omni.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {

    private fun cfg(id: String) = ProviderConfig(id, "https://$id.test", "ref-$id", listOf("m"))

    @Test
    fun `latency-based pick prefers lower avg latency`() {
        val router = Router()
        router.recordSuccess("p1", 500)
        router.recordSuccess("p2", 50)
        assertEquals("p2", router.pickRoute(listOf(cfg("p1"), cfg("p2")))?.id)
    }

    @Test
    fun `unknown latency sorts last`() {
        val router = Router()
        router.recordSuccess("p1", 100)
        assertEquals("p1", router.pickRoute(listOf(cfg("p2"), cfg("p1")))?.id)
    }

    @Test
    fun `preferred provider wins when eligible`() {
        val router = Router()
        router.recordSuccess("p1", 10)
        router.recordSuccess("p2", 500)
        assertEquals("p2", router.pickRoute(listOf(cfg("p1"), cfg("p2")), preferredId = "p2")?.id)
    }

    @Test
    fun `fallback chain order respected - fallbacks tried after router picks`() {
        val router = Router()
        router.recordSuccess("p1", 100)
        router.recordSuccess("p2", 5)
        val candidates = listOf(cfg("p1"), cfg("p2"))
        // Without fallbacks the faster provider wins.
        assertEquals("p2", router.pickRoute(candidates)?.id)
        // With p2 declared a fallback for the model, p1 is picked first.
        assertEquals("p1", router.pickRoute(candidates, mapOf("m" to listOf("p2")))?.id)
    }

    @Test
    fun `provider cooling down after allowedFails is skipped by pickRoute`() {
        var now = 0L
        val router = Router(RouterConfig(allowedFails = 2, cooldownMs = 1_000), clock = { now })
        router.recordFailure("p1")
        assertFalse(router.coolingDown("p1"))
        router.recordFailure("p1")
        assertTrue(router.coolingDown("p1"))
        assertEquals("p2", router.pickRoute(listOf(cfg("p1"), cfg("p2")))?.id)
    }

    @Test
    fun `recovers after cooldownMs elapses`() {
        var now = 0L
        val router = Router(RouterConfig(allowedFails = 1, cooldownMs = 1_000), clock = { now })
        router.recordFailure("p1")
        assertTrue(router.coolingDown("p1"))
        now = 1_001
        assertFalse(router.coolingDown("p1"))
        assertEquals("p1", router.pickRoute(listOf(cfg("p1")))?.id)
    }

    @Test
    fun `reset clears cooldown and failures`() {
        var now = 0L
        val router = Router(RouterConfig(allowedFails = 1, cooldownMs = 60_000), clock = { now })
        router.recordFailure("p1")
        assertTrue(router.coolingDown("p1"))
        router.reset("p1")
        assertFalse(router.coolingDown("p1"))
        assertEquals("p1", router.pickRoute(listOf(cfg("p1")))?.id)
    }

    @Test
    fun `success clears prior failure count`() {
        var now = 0L
        val router = Router(RouterConfig(allowedFails = 2, cooldownMs = 1_000), clock = { now })
        router.recordFailure("p1")
        router.recordSuccess("p1", 10)
        router.recordFailure("p1")
        assertFalse(router.coolingDown("p1"))
    }

    @Test
    fun `returns null when every candidate is cooling down`() {
        var now = 0L
        val router = Router(RouterConfig(allowedFails = 1, cooldownMs = 60_000), clock = { now })
        router.recordFailure("p1")
        router.recordFailure("p2")
        assertNull(router.pickRoute(listOf(cfg("p1"), cfg("p2"))))
    }

    @Test
    fun `avgLatency returns null with no samples`() {
        assertNull(Router().avgLatency("nope"))
    }
}
