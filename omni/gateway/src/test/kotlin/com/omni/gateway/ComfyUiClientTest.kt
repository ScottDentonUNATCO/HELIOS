package com.omni.gateway

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ComfyUiClient tests. HTTP is faked with an in-process OkHttp [Interceptor]
 * (the repo's established pattern — this sandbox blocks loopback TCP, so a
 * real local HTTP server is unreachable; the interceptor sees the exact
 * request the client builds and returns scripted responses).
 */
class ComfyUiClientTest {

    private data class Scripted(val status: Int, val body: String)

    private class Backend : Interceptor {
        val calls = mutableListOf<String>()
        @Volatile var script: (path: String, method: String, body: String) -> Scripted =
            { _, _, _ -> Scripted(500, "{}") }
        private val counter = AtomicInteger(0)

        fun newClient(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val buf = Buffer()
            req.body?.writeTo(buf)
            val path = req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: "")
            synchronized(calls) { calls += "${req.method} $path" }
            counter.incrementAndGet()
            val s = script(req.url.encodedPath, req.method, buf.readUtf8())
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(s.status)
                .message("mock")
                .body(s.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private val base = "http://comfy.local:8188"
    private val workflow = """{"1":{"inputs":{"text":"a cat"},"class_type":"CLIPTextEncode"}}"""

    private fun successHistory(promptId: String) = """
        {"$promptId":{
          "status":{"status_str":"success","completed":true,"messages":[]},
          "outputs":{"9":{"images":[
            {"filename":"helios_00001_.png","subfolder":"","type":"output"},
            {"filename":"helios_00002_.png","subfolder":"sub","type":"output"}
          ]}}
        }}""".trimIndent()

    @Test fun `submitPrompt posts workflow and returns prompt_id`() {
        val backend = Backend()
        backend.script = { path, method, body ->
            assertEquals("/prompt", path)
            assertEquals("POST", method)
            assertTrue("workflow embedded", body.contains("\"class_type\":\"CLIPTextEncode\""))
            assertTrue("client_id present", body.contains("\"client_id\""))
            Scripted(200, """{"prompt_id":"abc-123","number":1,"node_errors":{}}""")
        }
        val client = ComfyUiClient(backend.newClient())
        assertEquals("abc-123", client.submitPrompt(base, workflow))
        assertEquals(listOf("POST /prompt"), backend.calls)
    }

    @Test fun `submitPrompt rejects invalid workflow JSON before any HTTP call`() {
        val backend = Backend()
        val client = ComfyUiClient(backend.newClient())
        try {
            client.submitPrompt(base, "{not json")
            fail("expected ComfyUiException")
        } catch (e: ComfyUiException) {
            assertTrue(e.message!!.contains("not valid JSON"))
        }
        assertTrue("no HTTP call made", backend.calls.isEmpty())
    }

    @Test fun `submitPrompt surfaces server rejection with node errors`() {
        val backend = Backend()
        backend.script = { _, _, _ ->
            Scripted(400, """{"error":{"message":"bad node"},"node_errors":{"4":{"errors":["missing ckpt"]}}}""")
        }
        val client = ComfyUiClient(backend.newClient())
        try {
            client.submitPrompt(base, workflow)
            fail("expected ComfyUiException")
        } catch (e: ComfyUiException) {
            assertTrue(e.message!!.contains("rejected") || e.message!!.contains("node"))
        }
    }

    @Test fun `submitPrompt rejects non-http base URL`() {
        val client = ComfyUiClient(Backend().newClient())
        try {
            client.submitPrompt("ftp://nope", workflow)
            fail("expected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("http"))
        }
    }

    @Test fun `pollOnce returns null while server answers empty object`() {
        val backend = Backend()
        backend.script = { path, method, _ ->
            assertEquals("/history/abc-123", path)
            assertEquals("GET", method)
            Scripted(200, "{}")
        }
        assertNull(ComfyUiClient(backend.newClient()).pollOnce(base, "abc-123"))
    }

    @Test fun `pollOnce returns null while run not completed`() {
        val backend = Backend()
        backend.script = { _, _, _ ->
            Scripted(200, """{"abc-123":{"status":{"status_str":"running","completed":false,"messages":[]},"outputs":{}}}""")
        }
        assertNull(ComfyUiClient(backend.newClient()).pollOnce(base, "abc-123"))
    }

    @Test fun `pollOnce parses successful outputs`() {
        val backend = Backend()
        backend.script = { _, _, _ -> Scripted(200, successHistory("abc-123")) }
        val result = ComfyUiClient(backend.newClient()).pollOnce(base, "abc-123")
        assertNotNull(result)
        assertEquals("abc-123", result!!.promptId)
        assertEquals(2, result.images.size)
        assertEquals(ComfyImage("helios_00001_.png", "", "output"), result.images[0])
        assertEquals(ComfyImage("helios_00002_.png", "sub", "output"), result.images[1])
    }

    @Test fun `pollOnce throws on server-side run error`() {
        val backend = Backend()
        backend.script = { _, _, _ ->
            Scripted(200, """{"abc-123":{"status":{"status_str":"error","completed":true,"messages":[["prompt","out of memory"]]},"outputs":{}}}""")
        }
        try {
            ComfyUiClient(backend.newClient()).pollOnce(base, "abc-123")
            fail("expected ComfyUiException")
        } catch (e: ComfyUiException) {
            assertTrue(e.message!!.contains("failed"))
            assertTrue(e.message!!.contains("out of memory"))
        }
    }

    @Test fun `pollUntilDone waits through running polls then returns`() {
        val backend = Backend()
        var n = 0
        backend.script = { _, _, _ ->
            n++
            if (n < 3) Scripted(200, "{}") else Scripted(200, successHistory("abc-123"))
        }
        val result = ComfyUiClient(backend.newClient())
            .pollUntilDone(base, "abc-123", timeoutMs = 5_000, intervalMs = 5)
        assertEquals(2, result.images.size)
        assertEquals(3, n)
    }

    @Test fun `pollUntilDone throws on timeout`() {
        val backend = Backend()
        backend.script = { _, _, _ -> Scripted(200, "{}") }
        try {
            ComfyUiClient(backend.newClient()).pollUntilDone(base, "abc-123", timeoutMs = 60, intervalMs = 5)
            fail("expected timeout")
        } catch (e: ComfyUiException) {
            assertTrue(e.message!!.contains("timed out"))
        }
    }

    @Test fun `downloadView returns bytes and encodes query params`() {
        val backend = Backend()
        var seenPath = ""
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val raw = object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val req = chain.request()
                seenPath = req.url.encodedPath
                assertEquals("helios_00001_.png", req.url.queryParameter("filename"))
                assertEquals("output", req.url.queryParameter("type"))
                return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200)
                    .message("mock")
                    .body(bytes.toResponseBody("image/png".toMediaType()))
                    .build()
            }
        }
        val client = ComfyUiClient(OkHttpClient.Builder().addInterceptor(raw).build())
        val out = client.downloadView(base, ComfyImage("helios_00001_.png", "", "output"))
        assertEquals("/view", seenPath)
        assertEquals(bytes.toList(), out.toList())
    }

    @Test fun `downloadView throws on 404`() {
        val backend = Backend()
        backend.script = { _, _, _ -> Scripted(404, "not found") }
        try {
            ComfyUiClient(backend.newClient()).downloadView(base, ComfyImage("nope.png", "", "output"))
            fail("expected ComfyUiException")
        } catch (e: ComfyUiException) {
            assertTrue(e.message!!.contains("404"))
        }
    }
}
