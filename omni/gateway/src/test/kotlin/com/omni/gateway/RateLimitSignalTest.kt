package com.omni.gateway

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The 429 signal: [OpenAiCompatClient] must surface HTTP 429 distinctly from
 * other failures so [KeyRouter]'s backoff path can trigger. Runs through the
 * sibling's [MockOpenAiBackend] OkHttp interceptor; all keys are DUMMY values.
 */
class RateLimitSignalTest {

    private lateinit var backend: MockOpenAiBackend
    private lateinit var client: OpenAiCompatClient
    private lateinit var config: ProviderConfig

    @Before
    fun setUp() {
        backend = MockOpenAiBackend()
        client = OpenAiCompatClient(backend.newClient())
        config = ProviderConfig("p", "https://mock.test", "ref", listOf("m"))
    }

    private fun streamThrows(): GatewayException = runBlocking {
        try {
            client.streamChat(
                config, "sk-test-dummy",
                ChatRequest("m", listOf(ChatMessage("user", "hi")))
            ).toList()
            error("expected GatewayException")
        } catch (e: GatewayException) {
            e
        }
    }

    @Test
    fun `http 429 surfaces rateLimited`() {
        backend.handler = { MockHttpResponse(429, MockOpenAiBackend.errorBody("slow down")) }
        val e = streamThrows()
        assertTrue("retryable", e.retryable)
        assertTrue("rateLimited", e.rateLimited)
        assertTrue(e.message!!.contains("429"))
    }

    @Test
    fun `http 500 is retryable but not rateLimited`() {
        backend.handler = { MockHttpResponse(500, MockOpenAiBackend.errorBody("boom")) }
        val e = streamThrows()
        assertTrue("retryable", e.retryable)
        assertFalse("rateLimited", e.rateLimited)
    }

    @Test
    fun `http 400 is neither retryable nor rateLimited`() {
        backend.handler = { MockHttpResponse(400, MockOpenAiBackend.errorBody("bad request")) }
        val e = streamThrows()
        assertFalse("retryable", e.retryable)
        assertFalse("rateLimited", e.rateLimited)
    }

}
