package com.omni.gateway

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process mock of an OpenAI-compatible HTTP API, implemented as an OkHttp
 * [Interceptor] — the same seam the repo's [OpenAiCompatClientTest] uses.
 *
 * Rationale: this sandbox blocks direct TCP connections (even loopback), so a
 * `com.sun.net.httpserver` mock cannot be reached by the client under test.
 * The interceptor sees the exact request the real client builds (method,
 * path, Authorization header, JSON body) and returns scripted HTTP responses
 * (status code, SSE / JSON bodies), so every client code path — request
 * construction, SSE parsing, HTTP-error mapping — is exercised for real, with
 * zero sockets and zero new dependencies (OkHttp is already on the test
 * classpath).
 *
 * Scriptable per test via [handler]:
 * ```kotlin
 * backend.handler = { call ->
 *     if (call.key == BAD_KEY) MockHttpResponse(429, MockOpenAiBackend.errorBody("slow down"))
 *     else MockHttpResponse(200, MockOpenAiBackend.sseBody(60, 60), "text/event-stream")
 * }
 * ```
 *
 * Every intercepted call is appended to a thread-safe log ([LoggedHttpCall])
 * recording the bearer key and arrival timestamp, so tests can assert exactly
 * which key instance served each call.
 */
data class MockHttpResponse(
    val status: Int,
    val body: String,
    val contentType: String = "application/json"
)

data class LoggedHttpCall(
    /** Bearer key from the Authorization header ("" when absent). */
    val key: String,
    val host: String,
    val path: String,
    val requestBody: String,
    val timestampMs: Long,
    val callIndex: Int
)

class MockOpenAiBackend : Interceptor {

    /** Artificial per-request latency, applied before the handler runs. */
    @Volatile var latencyMs: Long = 0

    /** Model ids returned by GET /v1/models. */
    @Volatile var modelIds: List<String> = listOf("mock-model")

    /**
     * Decides the response for each intercepted call. Default: a valid SSE
     * chat-completion with 10 prompt / 5 completion tokens. GET /v1/models is
     * answered automatically and never reaches the handler.
     */
    @Volatile var handler: (call: LoggedHttpCall) -> MockHttpResponse =
        { MockHttpResponse(200, sseBody(10, 5), "text/event-stream") }

    private val callCounter = AtomicInteger(0)
    private val logLock = Any()
    private val log = mutableListOf<LoggedHttpCall>()

    /** An OkHttpClient whose traffic is served by this mock. */
    fun newClient(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val bodyBuf = Buffer()
        req.body?.writeTo(bodyBuf)
        val auth = req.header("Authorization") ?: ""
        val key = if (auth.startsWith("Bearer ")) auth.removePrefix("Bearer ") else ""
        val idx = callCounter.getAndIncrement()
        val call = LoggedHttpCall(
            key = key,
            host = req.url.host,
            path = req.url.encodedPath,
            requestBody = bodyBuf.readUtf8(),
            timestampMs = System.currentTimeMillis(),
            callIndex = idx
        )
        synchronized(logLock) { log += call }
        if (latencyMs > 0) Thread.sleep(latencyMs)

        val mock = if (req.method == "GET" && call.path == "/v1/models") {
            MockHttpResponse(200, modelsBody(*modelIds.toTypedArray()))
        } else {
            handler(call)
        }
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(mock.status)
            .message("mock")
            .body(mock.body.toResponseBody(mock.contentType.toMediaType()))
            .build()
    }

    /** Snapshot of the thread-safe call log (arrival order). */
    fun calls(): List<LoggedHttpCall> = synchronized(logLock) { log.toList() }

    fun countForKey(key: String): Int = synchronized(logLock) { log.count { it.key == key } }

    fun totalCalls(): Int = synchronized(logLock) { log.size }

    fun resetLog() {
        synchronized(logLock) { log.clear() }
        callCounter.set(0)
    }

    companion object {
        /** Valid SSE chat-completion body the real client parses into tokens + usage. */
        fun sseBody(promptTokens: Long, completionTokens: Long, vararg tokens: String): String {
            val sb = StringBuilder()
            val words = if (tokens.isEmpty()) arrayOf("mock", " reply") else tokens
            for (w in words) {
                sb.append("data: {\"choices\":[{\"delta\":{\"content\":\"$w\"}}]}\n\n")
            }
            sb.append("data: {\"choices\":[],\"usage\":")
            sb.append("{\"prompt_tokens\":$promptTokens,\"completion_tokens\":$completionTokens}}")
            sb.append("\n\ndata: [DONE]\n\n")
            return sb.toString()
        }

        fun errorBody(message: String): String =
            "{\"error\":{\"message\":\"$message\",\"type\":\"mock_error\"}}"

        fun modelsBody(vararg ids: String): String {
            val data = ids.joinToString(",") { "{\"id\":\"$it\",\"object\":\"model\"}" }
            return "{\"object\":\"list\",\"data\":[$data]}"
        }

        /** Response that is not valid JSON at all (tests client tolerance). */
        fun malformedBody(): String = "this is not json {{{"
    }
}
