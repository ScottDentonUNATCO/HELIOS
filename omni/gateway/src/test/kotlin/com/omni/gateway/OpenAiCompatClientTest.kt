package com.omni.gateway

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Hermetic tests for [OpenAiCompatClient].
 *
 * The HTTP transport is faked with an in-process OkHttp [Interceptor] rather
 * than MockWebServer, so no TCP sockets are opened and the suite runs anywhere
 * (CI, sandboxes, offline). Every assertion covers the real client code path:
 * request construction, SSE parsing, and HTTP-error mapping.
 */
class OpenAiCompatClientTest {

    private data class Recorded(val path: String, val auth: String?, val body: String)

    /** In-process fake transport: records the request, returns a canned response. */
    private class FakeTransport(
        val code: Int,
        val body: String,
        val contentType: String = "application/json",
    ) : Interceptor {
        val recorded = mutableListOf<Recorded>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val buf = Buffer()
            req.body?.writeTo(buf)
            recorded += Recorded(
                path = req.url.encodedPath,
                auth = req.header("Authorization"),
                body = buf.readUtf8(),
            )
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .body(body.toResponseBody(contentType.toMediaType()))
                .build()
        }
    }

    private fun clientWith(fake: FakeTransport) =
        OpenAiCompatClient(OkHttpClient.Builder().addInterceptor(fake).build())

    private fun config() =
        ProviderConfig("p1", "https://fake.invalid", "ref", listOf("m"))

    private fun request() = ChatRequest("m", listOf(ChatMessage("user", "hi")))

    @Test
    fun `sse stream yields tokens then done with usage`() = runTest {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\n" +
            "data: [DONE]\n\n"
        val fake = FakeTransport(200, sse, "text/event-stream")

        val events = clientWith(fake).streamChat(config(), "key", request()).toList()

        assertEquals(
            listOf(ChatEvent.Token("Hello"), ChatEvent.Token(" world"), ChatEvent.Done(10, 5)),
            events
        )
        val rec = fake.recorded.single()
        assertEquals("/v1/chat/completions", rec.path)
        assertEquals("Bearer key", rec.auth)
        assertTrue(rec.body.contains("\"stream\":true"))
        assertTrue(rec.body.contains("\"model\":\"m\""))
    }

    @Test
    fun `sse stream tolerates malformed chunks`() = runTest {
        val sse = "data: not json at all\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
            ": a comment line\n\n" +
            "data: [DONE]\n\n"
        val fake = FakeTransport(200, sse, "text/event-stream")

        val events = clientWith(fake).streamChat(config(), "key", request()).toList()

        assertEquals(listOf(ChatEvent.Token("ok"), ChatEvent.Done(0, 0)), events)
    }

    @Test
    fun `http 429 maps to retryable GatewayException`() = runTest {
        val fake = FakeTransport(429, "{\"error\":{\"message\":\"slow down\"}}")
        try {
            clientWith(fake).streamChat(config(), "key", request()).toList()
            fail("expected GatewayException")
        } catch (e: GatewayException) {
            assertTrue(e.retryable)
            assertTrue(e.message!!.contains("429"))
        }
    }

    @Test
    fun `http 401 maps to non-retryable GatewayException`() = runTest {
        val fake = FakeTransport(401, "{\"error\":{\"message\":\"bad key\"}}")
        try {
            clientWith(fake).streamChat(config(), "key", request()).toList()
            fail("expected GatewayException")
        } catch (e: GatewayException) {
            assertFalse(e.retryable)
        }
    }

    @Test
    fun `http 500 maps to retryable GatewayException`() = runTest {
        val fake = FakeTransport(500, "")
        try {
            clientWith(fake).streamChat(config(), "key", request()).toList()
            fail("expected GatewayException")
        } catch (e: GatewayException) {
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `listModels parses ids`() {
        val fake = FakeTransport(
            200,
            "{\"data\":[{\"id\":\"gpt-4\"},{\"id\":\"gpt-3.5\"}],\"object\":\"list\"}"
        )
        assertEquals(listOf("gpt-4", "gpt-3.5"), clientWith(fake).listModels(config(), "key"))
        val rec = fake.recorded.single()
        assertEquals("/v1/models", rec.path)
        assertEquals("Bearer key", rec.auth)
    }

    @Test
    fun `listModels error maps to GatewayException`() {
        val fake = FakeTransport(403, "{\"error\":{\"message\":\"nope\"}}")
        try {
            clientWith(fake).listModels(config(), "key")
            fail("expected GatewayException")
        } catch (e: GatewayException) {
            assertFalse(e.retryable)
        }
    }
}
