package com.omni.gateway

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Integration tests for the smart-router key layer, exercising the FULL
 * routing path through the real client code:
 *
 *   KeyRouter.pickKey → OpenAiCompatClient.streamChat → MockOpenAiBackend
 *       → KeyRouter.record429 / recordSuccess
 *
 * The mock is an in-process OkHttp interceptor (the same seam the repo's
 * [OpenAiCompatClientTest] uses): it observes the exact HTTP request the real
 * client builds and returns scripted status codes / SSE bodies, so request
 * construction, SSE parsing, and HTTP-error mapping are all covered for real
 * with zero sockets. (A `com.sun.net.httpserver` mock was tried first but
 * this sandbox blocks direct TCP connections, even loopback.)
 *
 * [AiGateway] is not yet key-aware (it routes at the provider level), so the
 * [routedGenerate] harness below is the deepest real seam for key routing: it
 * is the exact loop production wiring must implement (pick → call → record
 * 429/success → fail over to the next key without waiting). One extra test
 * runs [AiGateway] itself end-to-end against two mock providers.
 *
 * All keys are DUMMY values ("sk-test-dummy-00N"). No real credentials exist
 * anywhere in this file; nothing leaves the process.
 */
class KeyRouterIntegrationTest {

    companion object {
        const val MAT_A = "sk-test-dummy-001"
        const val MAT_B = "sk-test-dummy-002"
        const val MAT_C = "sk-test-dummy-003"
        const val PROVIDER = "mock-openai"
        const val MODEL = "mock-model"
        const val PROMPT_TOKENS = 60L
        const val COMPLETION_TOKENS = 60L
        val SPEND_PER_CALL =
            (PROMPT_TOKENS / 1_000.0) * 1.0 + (COMPLETION_TOKENS / 1_000.0) * 2.0
    }

    private data class RoutedResult(
        val keyId: String,
        val promptTokens: Long,
        val completionTokens: Long
    )

    private lateinit var backend: MockOpenAiBackend
    private lateinit var client: OpenAiCompatClient
    private lateinit var config: ProviderConfig

    private fun key(id: String, label: String = id) = KeyInstance(
        id = id, providerId = PROVIDER, label = label, vaultRef = "vault:$id"
    )

    private val materials = mapOf("key-a" to MAT_A, "key-b" to MAT_B, "key-c" to MAT_C)

    private fun okResponse() = MockHttpResponse(
        200, MockOpenAiBackend.sseBody(PROMPT_TOKENS, COMPLETION_TOKENS), "text/event-stream"
    )

    @Before
    fun setUp() {
        backend = MockOpenAiBackend()
        backend.handler = { okResponse() }
        client = OpenAiCompatClient(backend.newClient())
        // Base URL is never hit: the interceptor short-circuits every call.
        config = ProviderConfig(PROVIDER, "http://mock.invalid", "vault:unused", listOf(MODEL))
    }

    /**
     * The key-level routing loop: pick the next eligible key, call it, feed
     * the outcome back into the router. 429s cool the key down and the loop
     * immediately tries the next key without waiting; other retryable errors
     * fail over too but do not trigger backoff.
     */
    private suspend fun routedGenerate(
        router: KeyRouter,
        keys: List<KeyInstance>,
        request: ChatRequest = ChatRequest(MODEL, listOf(ChatMessage("user", "ping"))),
        tracker: SpendTracker? = null
    ): RoutedResult {
        var attempts = 0
        var lastError: GatewayException? = null
        while (attempts < keys.size) {
            val key = router.pickKey(PROVIDER, keys) ?: break
            val material = materials[key.id] ?: error("no dummy key material for ${key.id}")
            try {
                val events = client.streamChat(config, material, request).toList()
                val done = events.filterIsInstance<ChatEvent.Done>().single()
                val spend = (done.promptTokens / 1_000.0) * 1.0 +
                    (done.completionTokens / 1_000.0) * 2.0
                router.recordSuccess(key.id, done.promptTokens, done.completionTokens, spend)
                tracker?.record(done.promptTokens, done.completionTokens, 1.0 to 2.0)
                return RoutedResult(key.id, done.promptTokens, done.completionTokens)
            } catch (e: GatewayException) {
                lastError = e
                if (e.message?.contains("HTTP 429") == true) router.record429(key.id)
                else router.recordFailure(key.id)
                attempts++
                if (!e.retryable) throw e
            }
        }
        throw lastError ?: GatewayException("no keys available", retryable = false)
    }

    @Test
    fun `round robin distributes calls evenly across three keys`() = runBlocking {
        val router = KeyRouter()
        val keys = listOf(key("key-a"), key("key-b"), key("key-c"))

        repeat(90) { routedGenerate(router, keys) }

        assertEquals(30, backend.countForKey(MAT_A))
        assertEquals(30, backend.countForKey(MAT_B))
        assertEquals(30, backend.countForKey(MAT_C))
        // strict rotation order: A, B, C, A, B, C, ...
        val order = backend.calls().take(6).map { it.key }
        assertEquals(listOf(MAT_A, MAT_B, MAT_C, MAT_A, MAT_B, MAT_C), order)
        // the client really sent the Authorization header and streamed JSON
        val first = backend.calls().first()
        assertEquals("Bearer $MAT_A", "Bearer ${first.key}")
        assertEquals("/v1/chat/completions", first.path)
        assertTrue(first.requestBody.contains("\"stream\":true"))
    }

    @Test
    fun `429 fails over to next key and cooled key rejoins`() = runBlocking {
        val router = KeyRouter(KeyRouterConfig(baseCooldownMs = 200, maxCooldownMs = 2_000))
        val keys = listOf(key("key-a"), key("key-b"))
        backend.handler = { call ->
            if (call.key == MAT_A) MockHttpResponse(429, MockOpenAiBackend.errorBody("slow down"))
            else okResponse()
        }

        repeat(10) { routedGenerate(router, keys) }

        // Key A served exactly the one call that 429'd; B served all 10 routed calls.
        assertEquals(1, backend.countForKey(MAT_A))
        assertEquals(10, backend.countForKey(MAT_B))
        assertTrue(router.status(keys[0]).startsWith("rate-limited"))

        // Let A's backoff expire, then restore health for both keys.
        Thread.sleep(400)
        backend.handler = { okResponse() }
        repeat(6) { routedGenerate(router, keys) }

        val aTotal = backend.countForKey(MAT_A)
        assertTrue("key A should rejoin after cooldown, got $aTotal/17", aTotal >= 3)
        assertEquals(17, backend.totalCalls())
        assertEquals("ready", router.status(keys[0]))
    }

    @Test
    fun `daily token budget blocks exhausted key`() = runBlocking {
        val router = KeyRouter()
        // 120 tokens/call; budget 300 → exhausted after exactly 3 successful calls.
        val keys = listOf(key("key-a").copy(dailyTokenBudget = 300), key("key-b"))

        repeat(9) { routedGenerate(router, keys) }

        assertEquals(3, backend.countForKey(MAT_A))
        assertEquals(6, backend.countForKey(MAT_B))
        assertEquals("daily budget exhausted", router.status(keys[0]))
        // Per-instance counter matches the 3 routed calls exactly.
        assertEquals(3 * (PROMPT_TOKENS + COMPLETION_TOKENS), router.usageSnapshot("key-a").tokensToday)
    }

    @Test
    fun `monthly spend budget blocks exhausted key`() = runBlocking {
        val router = KeyRouter()
        // $0.00018/call; budget 2.5 calls' worth → exhausted after exactly 3 calls.
        val keys = listOf(key("key-a").copy(monthlySpendBudgetUsd = 2.5 * SPEND_PER_CALL), key("key-b"))

        repeat(7) { routedGenerate(router, keys) }

        assertEquals(3, backend.countForKey(MAT_A))
        assertEquals(4, backend.countForKey(MAT_B))
        assertEquals("monthly budget exhausted", router.status(keys[0]))
        val spend = router.usageSnapshot("key-a").spendMonthUsd
        assertTrue("spend=$spend", kotlin.math.abs(spend - 3 * SPEND_PER_CALL) < 1e-12)
    }

    @Test
    fun `per-instance attribution counters match routed calls`() = runBlocking {
        val router = KeyRouter()
        val tracker = SpendTracker()
        val keys = listOf(key("key-a"), key("key-b"), key("key-c"))

        repeat(30) { routedGenerate(router, keys, tracker = tracker) }

        for (id in listOf("key-a", "key-b", "key-c")) {
            val snap = router.usageSnapshot(id)
            assertEquals(10 * (PROMPT_TOKENS + COMPLETION_TOKENS), snap.tokensToday)
            assertTrue(
                "spend=${snap.spendMonthUsd}",
                kotlin.math.abs(snap.spendMonthUsd - 10 * SPEND_PER_CALL) < 1e-12
            )
        }
        // Global tracker reconciles with the sum of per-key attribution.
        val expected = 30 * SPEND_PER_CALL
        assertTrue("tracker=${tracker.totalUsd()}", kotlin.math.abs(tracker.totalUsd() - expected) < 1e-9)
    }

    @Test
    fun `disabled key is skipped`() = runBlocking {
        val router = KeyRouter()
        val keys = listOf(key("key-a"), key("key-b"), key("key-c").copy(enabled = false))

        repeat(30) { routedGenerate(router, keys) }

        assertEquals(15, backend.countForKey(MAT_A))
        assertEquals(15, backend.countForKey(MAT_B))
        assertEquals(0, backend.countForKey(MAT_C))
    }

    @Test
    fun `http 500 fails over without cooling the key`() = runBlocking {
        val router = KeyRouter()
        val keys = listOf(key("key-a"), key("key-b"))
        backend.handler = { call ->
            if (call.key == MAT_A) MockHttpResponse(500, MockOpenAiBackend.errorBody("boom"))
            else okResponse()
        }

        repeat(10) { routedGenerate(router, keys) }

        // A keeps being picked (no 429 backoff) and failing; B serves every call.
        assertEquals(10, backend.countForKey(MAT_A))
        assertEquals(10, backend.countForKey(MAT_B))
        assertEquals("ready", router.status(keys[0]))
    }

    @Test
    fun `concurrent stress keeps totals consistent`() {
        val router = KeyRouter()
        val keys = listOf(key("key-a"), key("key-b"), key("key-c"))
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val results = Collections.synchronizedList(mutableListOf<RoutedResult>())

        val threads = (1..8).map {
            Thread {
                try {
                    repeat(25) {
                        results += runBlocking { routedGenerate(router, keys) }
                    }
                } catch (t: Throwable) {
                    errors += t
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(60_000) }

        assertTrue("threads still alive", threads.none { it.isAlive })
        assertTrue("errors: ${errors.map { it.toString() }}", errors.isEmpty())
        assertEquals(200, results.size)
        assertEquals(200, backend.totalCalls())
        // Round-robin is exact even under contention: 67/67/66 in some order.
        val counts = listOf(MAT_A, MAT_B, MAT_C).map { backend.countForKey(it) }
        assertEquals(200, counts.sum())
        assertTrue("counts=$counts", counts.all { it in 66..68 })
        // No lost updates: per-key attribution matches the call log exactly.
        val mats = mapOf("key-a" to MAT_A, "key-b" to MAT_B, "key-c" to MAT_C)
        for ((id, mat) in mats) {
            val snap = router.usageSnapshot(id)
            val expectedTokens = backend.countForKey(mat) * (PROMPT_TOKENS + COMPLETION_TOKENS)
            assertEquals("tokens for $id", expectedTokens, snap.tokensToday)
        }
        val totalTokens = counts.sum() * (PROMPT_TOKENS + COMPLETION_TOKENS)
        val snapTotal = mats.keys.sumOf { router.usageSnapshot(it).tokensToday }
        assertEquals(totalTokens, snapTotal)
    }

    @Test
    fun `aigateway full path fails over between providers`() = runBlocking {
        backend.handler = { call ->
            if (call.host == "mock-p1.invalid") {
                MockHttpResponse(500, MockOpenAiBackend.errorBody("bad"))
            } else okResponse()
        }
        val p1 = ProviderConfig("p1", "http://mock-p1.invalid", "ref-p1", listOf(MODEL))
        val p2 = ProviderConfig("p2", "http://mock-p2.invalid", "ref-p2", listOf(MODEL))
        val gateway = AiGateway(
            listOf(p1, p2),
            CredentialStore { MAT_A }, // dummy key for every ref
            client,
            Router(),
            SpendTracker()
        )

        val events = gateway.generate(MODEL, listOf(ChatMessage("user", "hi"))).toList()

        val done = events.filterIsInstance<ChatEvent.Done>().single()
        assertEquals(PROMPT_TOKENS, done.promptTokens)
        assertEquals(COMPLETION_TOKENS, done.completionTokens)
        assertTrue(events.any { it is ChatEvent.Token })
        assertEquals(1, backend.calls().count { it.host == "mock-p1.invalid" })
        assertEquals(1, backend.calls().count { it.host == "mock-p2.invalid" })
    }
}
