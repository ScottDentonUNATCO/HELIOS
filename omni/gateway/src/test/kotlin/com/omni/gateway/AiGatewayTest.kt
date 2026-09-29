package com.omni.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiGatewayTest {

    private fun cfg(id: String) = ProviderConfig(id, "https://$id.test", "ref-$id", listOf("m"))

    private class FakeClient(val behavior: (ProviderConfig) -> Flow<ChatEvent>) : LlmClient {
        val calls = mutableListOf<String>()
        override fun streamChat(config: ProviderConfig, apiKey: String, request: ChatRequest): Flow<ChatEvent> {
            calls += config.id
            return behavior(config)
        }

        override fun listModels(config: ProviderConfig, apiKey: String): List<String> = emptyList()
    }

    private fun creds(vararg missing: String) = CredentialStore { ref ->
        if (missing.any { ref == "ref-$it" }) null else "key"
    }

    @Test
    fun `falls back to second provider when first throws retryable`() = runTest {
        val fake = FakeClient { c ->
            if (c.id == "p1") flow { throw GatewayException("boom", retryable = true) }
            else flow {
                emit(ChatEvent.Token("hi"))
                emit(ChatEvent.Done(1, 2))
            }
        }
        val gateway = AiGateway(listOf(cfg("p1"), cfg("p2")), creds(), fake, Router(), SpendTracker())

        val events = gateway.generate("m", listOf(ChatMessage("user", "hi"))).toList()

        assertEquals(listOf(ChatEvent.Token("hi"), ChatEvent.Done(1, 2)), events)
        assertEquals(listOf("p1", "p2"), fake.calls)
    }

    @Test
    fun `emits Error when budget exceeded and never calls a provider`() = runTest {
        val tracker = SpendTracker()
        tracker.record(1_000_000, 0, 10.0 to 10.0) // $10 spent
        val fake = FakeClient { flow { emit(ChatEvent.Token("nope")) } }
        val gateway = AiGateway(
            listOf(cfg("p1")), creds(), fake, Router(), tracker, budgetCapUsd = 5.0
        )

        val events = gateway.generate("m", emptyList()).toList()

        assertEquals(1, events.size)
        val error = events[0] as ChatEvent.Error
        assertTrue(error.message.contains("budget", ignoreCase = true))
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `emits single Error when all providers fail`() = runTest {
        val fake = FakeClient { flow { throw GatewayException("down", retryable = true) } }
        val gateway = AiGateway(listOf(cfg("p1"), cfg("p2")), creds(), fake, Router(), SpendTracker())

        val events = gateway.generate("m", emptyList()).toList()

        assertEquals(1, events.size)
        assertTrue((events[0] as ChatEvent.Error).message.isNotEmpty())
        assertEquals(listOf("p1", "p2"), fake.calls)
    }

    @Test
    fun `missing api key skips to next provider`() = runTest {
        val fake = FakeClient { c ->
            flow {
                emit(ChatEvent.Token("from-${c.id}"))
                emit(ChatEvent.Done(0, 0))
            }
        }
        val gateway = AiGateway(listOf(cfg("p1"), cfg("p2")), creds("p1"), fake, Router(), SpendTracker())

        val events = gateway.generate("m", emptyList()).toList()

        assertEquals(listOf(ChatEvent.Token("from-p2"), ChatEvent.Done(0, 0)), events)
        assertEquals(listOf("p2"), fake.calls)
    }

    @Test
    fun `non-retryable error emits Error immediately without failover`() = runTest {
        val fake = FakeClient { flow { throw GatewayException("bad key", retryable = false) } }
        val gateway = AiGateway(listOf(cfg("p1"), cfg("p2")), creds(), fake, Router(), SpendTracker())

        val events = gateway.generate("m", emptyList()).toList()

        assertEquals(1, events.size)
        assertEquals("bad key", (events[0] as ChatEvent.Error).message)
        assertEquals(listOf("p1"), fake.calls)
    }

    @Test
    fun `spend recorded on success via price table`() = runTest {
        val tracker = SpendTracker()
        val fake = FakeClient { flow { emit(ChatEvent.Done(1_000_000, 1_000_000)) } }
        val gateway = AiGateway(
            listOf(cfg("p1")), creds(), fake, Router(), tracker,
            priceTable = mapOf("m" to (1.0 to 3.0))
        )

        gateway.generate("m", emptyList()).toList()

        assertEquals(4.0, tracker.totalUsd(), 1e-9)
    }

    @Test
    fun `preferred provider is tried first`() = runTest {
        val fake = FakeClient { c ->
            flow {
                emit(ChatEvent.Token(c.id))
                emit(ChatEvent.Done(0, 0))
            }
        }
        val gateway = AiGateway(listOf(cfg("p1"), cfg("p2")), creds(), fake, Router(), SpendTracker())

        val events = gateway.generate("m", emptyList(), preferredProvider = "p2").toList()

        assertEquals(ChatEvent.Token("p2"), events[0])
        assertEquals(listOf("p2"), fake.calls)
    }

    @Test
    fun `availableModels returns config model lists`() {
        val gateway = AiGateway(
            listOf(cfg("p1").copy(models = listOf("a", "b")), cfg("p2")),
            creds(), FakeClient { flow { } }, Router(), SpendTracker()
        )
        assertEquals(mapOf("p1" to listOf("a", "b"), "p2" to listOf("m")), gateway.availableModels())
    }
}
