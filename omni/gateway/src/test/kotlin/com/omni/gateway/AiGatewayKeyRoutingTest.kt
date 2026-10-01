package com.omni.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [AiGateway]'s key-level routing: multi-instance failover, budget
 * enforcement, per-key attribution, auto model resolution, and the legacy
 * single-key path. Fake clients use DUMMY key material only.
 */
class AiGatewayKeyRoutingTest {

    private fun key(id: String, label: String, ref: String, dailyTokens: Long? = null) =
        KeyInstance(id, "p1", label, ref, dailyTokenBudget = dailyTokens)

    /** Fake client whose behavior is keyed off the API key material it receives. */
    private class KeyedFakeClient(val behavior: (apiKey: String) -> Flow<ChatEvent>) : LlmClient {
        val calls = mutableListOf<String>()
        val models = mutableListOf<String>()
        override fun streamChat(config: ProviderConfig, apiKey: String, request: ChatRequest): Flow<ChatEvent> {
            calls += apiKey
            models += request.model
            return behavior(apiKey)
        }

        override fun listModels(config: ProviderConfig, apiKey: String): List<String> = emptyList()
    }

    private fun creds() = CredentialStore { ref ->
        mapOf("ref-a" to "KEY-A", "ref-b" to "KEY-B")[ref]
    }

    private fun gateway(
        keys: List<KeyInstance>,
        fake: LlmClient,
        router: KeyRouter = KeyRouter(),
        tracker: SpendTracker = SpendTracker(),
        priceTable: Map<String, Pair<Double, Double>> = mapOf("m" to (1.0 to 2.0)),
        notified: MutableList<Pair<String, KeyUsage>>? = null,
        models: List<String> = listOf("m")
    ) = AiGateway(
        providers = listOf(ProviderConfig("p1", "https://p1.test", "ref-unused", models, keys)),
        credentials = creds(),
        client = fake,
        router = Router(),
        tracker = tracker,
        priceTable = priceTable,
        keyRouter = router,
        usageListener = notified?.let { out -> { id: String, usage: KeyUsage -> out += id to usage } }
    )

    private fun okFlow() = flow {
        emit(ChatEvent.Token("hi"))
        emit(ChatEvent.Done(100, 50))
    }

    @Test
    fun `429 on first key fails over to second key of same provider`() = runTest {
        val router = KeyRouter(KeyRouterConfig(baseCooldownMs = 60_000))
        val keys = listOf(key("k1", "A", "ref-a"), key("k2", "B", "ref-b"))
        val fake = KeyedFakeClient { k ->
            if (k == "KEY-A") flow {
                throw GatewayException("slow down", retryable = true, rateLimited = true)
            } else okFlow()
        }
        val tracker = SpendTracker()
        val notified = mutableListOf<Pair<String, KeyUsage>>()
        val g = gateway(keys, fake, router, tracker, notified = notified)

        val events = g.generate("m", listOf(ChatMessage("user", "hi"))).toList()

        assertEquals(listOf(ChatEvent.Token("hi"), ChatEvent.Done(100, 50)), events)
        // KEY-A was tried, 429'd, and KEY-B served — same provider, no waiting.
        assertEquals(listOf("KEY-A", "KEY-B"), fake.calls)
        assertTrue(router.status(keys[0]).startsWith("rate-limited"))
        assertEquals("ready", router.status(keys[1]))
        // Per-key attribution landed on the serving key only.
        val snap = router.usageSnapshot("k2")
        assertEquals(150L, snap.tokensToday)
        val expectedSpend = (100 / 1_000.0) * 1.0 + (50 / 1_000.0) * 2.0
        assertEquals(expectedSpend, snap.spendMonthUsd, 1e-12)
        assertEquals(0L, router.usageSnapshot("k1").tokensToday)
        // Global tracker reconciles with the per-key attribution.
        assertEquals(expectedSpend, tracker.totalUsd(), 1e-12)
        // Listener fired for the 429 (k1) and the success (k2).
        assertEquals(listOf("k1", "k2"), notified.map { it.first })
    }

    @Test
    fun `all keys 429d moves to next provider`() = runTest {
        val router = KeyRouter(KeyRouterConfig(baseCooldownMs = 60_000))
        val keys = listOf(key("k1", "A", "ref-a"), key("k2", "B", "ref-b"))
        val fake = KeyedFakeClient { flow {
            throw GatewayException("slow down", retryable = true, rateLimited = true)
        } }
        val p2fake = object : LlmClient {
            var called = false
            override fun streamChat(c: ProviderConfig, k: String, r: ChatRequest): Flow<ChatEvent> {
                called = true
                return okFlow()
            }

            override fun listModels(c: ProviderConfig, k: String): List<String> = emptyList()
        }
        val g = AiGateway(
            providers = listOf(
                ProviderConfig("p1", "https://p1.test", "ref-unused", listOf("m"), keys),
                ProviderConfig("p2", "https://p2.test", "ref-p2", listOf("m"))
            ),
            credentials = CredentialStore { if (it == "ref-p2") "KEY-P2" else creds().apiKey(it) },
            client = fake,
            router = Router(),
            tracker = SpendTracker(),
            keyRouter = router,
            clientFor = { p -> if (p.id == "p2") p2fake else fake }
        )

        val events = g.generate("m", listOf(ChatMessage("user", "hi"))).toList()

        assertEquals(listOf(ChatEvent.Token("hi"), ChatEvent.Done(100, 50)), events)
        assertEquals(listOf("KEY-A", "KEY-B"), fake.calls)
        assertTrue(p2fake.called)
        assertTrue(router.status(keys[0]).startsWith("rate-limited"))
        assertTrue(router.status(keys[1]).startsWith("rate-limited"))
    }

    @Test
    fun `daily token budget blocks exhausted key inside gateway`() = runTest {
        val router = KeyRouter()
        val keys = listOf(key("k1", "A", "ref-a", dailyTokens = 100), key("k2", "B", "ref-b"))
        val fake = KeyedFakeClient { okFlow() }
        val g = gateway(keys, fake, router)

        g.generate("m", listOf(ChatMessage("user", "one"))).toList()
        g.generate("m", listOf(ChatMessage("user", "two"))).toList()

        // k1 served 150 tokens on call one, blowing its 100-token daily
        // budget; call two went to k2.
        assertEquals(listOf("KEY-A", "KEY-B"), fake.calls)
        assertEquals("daily budget exhausted", router.status(keys[0]))
        assertEquals("ready", router.status(keys[1]))
    }

    @Test
    fun `keyless instance is skipped for a keyed one`() = runTest {
        val router = KeyRouter()
        val keys = listOf(key("k1", "A", "ref-missing"), key("k2", "B", "ref-b"))
        val fake = KeyedFakeClient { okFlow() }
        val g = gateway(keys, fake, router)

        val events = g.generate("m", listOf(ChatMessage("user", "hi"))).toList()

        assertEquals(listOf(ChatEvent.Token("hi"), ChatEvent.Done(100, 50)), events)
        assertEquals(listOf("KEY-B"), fake.calls)
    }

    @Test
    fun `null model resolves to serving provider default`() = runTest {
        val fake = KeyedFakeClient { okFlow() }
        val g = gateway(listOf(key("k1", "A", "ref-a")), fake, models = listOf("deepseek-chat"))

        g.generate(null, listOf(ChatMessage("user", "hi"))).toList()

        assertEquals(listOf("deepseek-chat"), fake.models)
    }

    @Test
    fun `null model with no provider default emits error`() = runTest {
        val fake = KeyedFakeClient { okFlow() }
        val g = gateway(listOf(key("k1", "A", "ref-a")), fake, models = emptyList())

        val events = g.generate(null, listOf(ChatMessage("user", "hi"))).toList()

        assertEquals(1, events.size)
        assertTrue((events[0] as ChatEvent.Error).message.contains("no model configured"))
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `legacy single-key config still routes via apiKeyRef`() = runTest {
        val router = KeyRouter()
        val fake = KeyedFakeClient { okFlow() }
        val g = AiGateway(
            // No keys list: the legacy path synthesizes one instance from apiKeyRef.
            providers = listOf(ProviderConfig("p1", "https://p1.test", "ref-a", listOf("m"))),
            credentials = creds(),
            client = fake,
            router = Router(),
            tracker = SpendTracker(),
            keyRouter = router
        )

        val events = g.generate("m", listOf(ChatMessage("user", "hi"))).toList()

        assertEquals(listOf(ChatEvent.Token("hi"), ChatEvent.Done(100, 50)), events)
        assertEquals(listOf("KEY-A"), fake.calls)
        assertEquals(150L, router.usageSnapshot("p1:default").tokensToday)
    }

    @Test
    fun `non-429 retryable still fails over at provider level`() = runTest {
        val router = KeyRouter()
        val keys = listOf(key("k1", "A", "ref-a"), key("k2", "B", "ref-b"))
        var calls = 0
        val fake = KeyedFakeClient {
            calls++
            if (calls == 1) flow {
                throw GatewayException("boom", retryable = true, rateLimited = false)
            } else okFlow()
        }
        val g = AiGateway(
            providers = listOf(
                ProviderConfig("p1", "https://p1.test", "ref-unused", listOf("m"), keys),
                ProviderConfig("p2", "https://p2.test", "ref-p2", listOf("m"))
            ),
            credentials = CredentialStore { if (it == "ref-p2") "KEY-P2" else creds().apiKey(it) },
            client = fake,
            router = Router(),
            tracker = SpendTracker(),
            keyRouter = router
        )

        val events = g.generate("m", listOf(ChatMessage("user", "hi"))).toList()

        // First attempt 500'd on k1 (no 429 backoff); the gateway moved to p2.
        assertEquals(listOf(ChatEvent.Token("hi"), ChatEvent.Done(100, 50)), events)
        assertEquals("ready", router.status(keys[0]))
    }

    // ---- full chain: client 429 signal -> gateway key failover ---------------

    private lateinit var backend: MockOpenAiBackend

    @Before
    fun setUpBackend() {
        backend = MockOpenAiBackend()
    }

    @Test
    fun `http 429 fails over between keys end to end`() = runBlocking {
        backend.handler = { call ->
            if (call.key == "sk-test-dummy-A") MockHttpResponse(429, MockOpenAiBackend.errorBody("slow down"))
            else MockHttpResponse(200, MockOpenAiBackend.sseBody(60, 60), "text/event-stream")
        }
        val router = KeyRouter(KeyRouterConfig(baseCooldownMs = 60_000))
        val keys = listOf(
            KeyInstance("k1", "p1", "A", "ref-a"),
            KeyInstance("k2", "p1", "B", "ref-b")
        )
        val http = OpenAiCompatClient(backend.newClient())
        val g = AiGateway(
            providers = listOf(
                ProviderConfig("p1", "https://mock.test", "ref-unused", listOf("mock-model"), keys)
            ),
            credentials = CredentialStore { ref ->
                when (ref) {
                    "ref-a" -> "sk-test-dummy-A"
                    "ref-b" -> "sk-test-dummy-B"
                    else -> null
                }
            },
            client = http,
            router = Router(),
            tracker = SpendTracker(),
            priceTable = mapOf("mock-model" to (1.0 to 2.0)),
            keyRouter = router
        )

        val events = g.generate("mock-model", listOf(ChatMessage("user", "hi"))).toList()

        val done = events.filterIsInstance<ChatEvent.Done>().single()
        assertEquals(60L, done.promptTokens)
        assertEquals(60L, done.completionTokens)
        // KEY-A served exactly the call that 429'd; KEY-B served the retry.
        assertEquals(1, backend.countForKey("sk-test-dummy-A"))
        assertEquals(1, backend.countForKey("sk-test-dummy-B"))
        assertTrue(router.status(keys[0]).startsWith("rate-limited"))
        assertEquals("ready", router.status(keys[1]))
        assertEquals(120L, router.usageSnapshot("k2").tokensToday)
    }
}
