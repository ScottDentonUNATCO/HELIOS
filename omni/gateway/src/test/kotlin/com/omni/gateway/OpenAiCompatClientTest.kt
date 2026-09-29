package com.omni.gateway

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAiCompatClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiCompatClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenAiCompatClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config() =
        ProviderConfig("p1", server.url("/").toString().trimEnd('/'), "ref", listOf("m"))

    private fun request() = ChatRequest("m", listOf(ChatMessage("user", "hi")))

    @Test
    fun `sse stream yields tokens then done with usage`() = runTest {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\n" +
            "data: [DONE]\n\n"
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse)
        )

        val events = client.streamChat(config(), "key", request()).toList()

        assertEquals(
            listOf(ChatEvent.Token("Hello"), ChatEvent.Token(" world"), ChatEvent.Done(10, 5)),
            events
        )
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer key", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"model\":\"m\""))
    }

    @Test
    fun `http 429 maps to retryable GatewayException`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setBody("{\"error\":{\"message\":\"slow down\"}}")
        )
        try {
            client.streamChat(config(), "key", request()).toList()
            fail("expected GatewayException")
        } catch (e: GatewayException) {
            assertTrue(e.retryable)
            assertTrue(e.message!!.contains("429"))
        }
    }

    @Test
    fun `http 401 maps to non-retryable GatewayException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"bad key\"}}"))
        try {
            client.streamChat(config(), "key", request()).toList()
            fail("expected GatewayException")
        } catch (e: GatewayException) {
            assertFalse(e.retryable)
        }
    }

    @Test
    fun `http 500 maps to retryable GatewayException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            client.streamChat(config(), "key", request()).toList()
            fail("expected GatewayException")
        } catch (e: GatewayException) {
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `listModels parses ids`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("{\"data\":[{\"id\":\"gpt-4\"},{\"id\":\"gpt-3.5\"}],\"object\":\"list\"}")
        )
        assertEquals(listOf("gpt-4", "gpt-3.5"), client.listModels(config(), "key"))
        assertEquals("/v1/models", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }
}
