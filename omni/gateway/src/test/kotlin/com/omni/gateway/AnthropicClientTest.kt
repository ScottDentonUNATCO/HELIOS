package com.omni.gateway

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [AnthropicClient] request/response mapping against [MockAnthropicBackend]
 * (OkHttp interceptor mock; all keys are DUMMY values).
 */
class AnthropicClientTest {

    private lateinit var backend: MockAnthropicBackend
    private lateinit var client: AnthropicClient
    private lateinit var config: ProviderConfig
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        backend = MockAnthropicBackend()
        client = AnthropicClient(backend.newClient())
        config = ProviderConfig("anthropic", "https://api.anthropic.com", "ref", listOf("claude-test-model"))
    }

    private fun stream(request: ChatRequest) = runBlocking {
        client.streamChat(config, "sk-ant-test-dummy-001", request).toList()
    }

    @Test
    fun `posts to v1-messages with anthropic headers`() = runBlocking {
        stream(ChatRequest("claude-test-model", listOf(ChatMessage("user", "hi"))))

        val req = backend.calls().single()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant-test-dummy-001", req.key)
        assertEquals(AnthropicClient.ANTHROPIC_VERSION, req.anthropicVersion)
    }

    @Test
    fun `system messages become top-level system param and roles merge`() = runBlocking {
        stream(
            ChatRequest(
                "claude-test-model",
                listOf(
                    ChatMessage("system", "be terse"),
                    ChatMessage("user", "one"),
                    ChatMessage("user", "two"),
                    ChatMessage("assistant", "ack"),
                    ChatMessage("user", "three")
                )
            )
        )

        val body = json.parseToJsonElement(backend.calls().single().body).jsonObject
        assertEquals("claude-test-model", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("be terse", body["system"]?.jsonPrimitive?.contentOrNull)
        assertTrue(body["max_tokens"]?.jsonPrimitive?.contentOrNull?.toInt() ?: 0 > 0)
        assertEquals(true, body["stream"]?.jsonPrimitive?.contentOrNull?.toBoolean())
        val messages = body["messages"]?.jsonArray!!.map {
            it.jsonObject["role"]?.jsonPrimitive?.contentOrNull to
                it.jsonObject["content"]?.jsonPrimitive?.contentOrNull
        }
        // consecutive user turns merged; strict alternation preserved
        assertEquals(
            listOf(
                "user" to "one\n\ntwo",
                "assistant" to "ack",
                "user" to "three"
            ),
            messages
        )
    }

    @Test
    fun `streams text deltas and usage into tokens and done`() {
        backend.handler = {
            MockAnthropicResponse(
                200,
                MockAnthropicBackend.sseBody(60, 45, "hel", "lo"),
                "text/event-stream"
            )
        }

        val events = stream(ChatRequest("claude-test-model", listOf(ChatMessage("user", "hi"))))

        assertEquals(
            listOf(ChatEvent.Token("hel"), ChatEvent.Token("lo"), ChatEvent.Done(60, 45)),
            events
        )
    }

    @Test
    fun `http 429 surfaces rateLimited`() {
        backend.handler = { MockAnthropicResponse(429, MockAnthropicBackend.errorBody("slow down")) }

        try {
            stream(ChatRequest("claude-test-model", listOf(ChatMessage("user", "hi"))))
            error("expected GatewayException")
        } catch (e: GatewayException) {
            assertTrue(e.retryable)
            assertTrue(e.rateLimited)
            assertTrue(e.message!!.contains("429"))
            assertTrue(e.message!!.contains("slow down"))
        }
    }

    @Test
    fun `http 401 is non-retryable with provider detail`() {
        backend.handler = { MockAnthropicResponse(401, MockAnthropicBackend.errorBody("invalid x-api-key")) }

        try {
            stream(ChatRequest("claude-test-model", listOf(ChatMessage("user", "hi"))))
            error("expected GatewayException")
        } catch (e: GatewayException) {
            assertFalse(e.retryable)
            assertFalse(e.rateLimited)
            assertTrue(e.message!!.contains("invalid x-api-key"))
        }
    }

    @Test
    fun `listModels maps anthropic model ids`() = runBlocking {
        backend.modelIds = listOf("claude-sonnet-4-6", "claude-haiku-4-5")

        val ids = client.listModels(config, "sk-ant-test-dummy-001")

        assertEquals(listOf("claude-sonnet-4-6", "claude-haiku-4-5"), ids)
    }

    @Test
    fun `clientForProvider routes anthropic to the anthropic adapter`() {
        val openAi = OpenAiCompatClient(OkHttpClient())
        val pick = clientForProvider(openAi, client)

        assertTrue(pick(ProviderConfig("anthropic", "https://x", "r")) === client)
        assertTrue(pick(ProviderConfig("openai", "https://x", "r")) === openAi)
        assertTrue(pick(ProviderConfig("deepseek", "https://x", "r")) === openAi)
    }
}
