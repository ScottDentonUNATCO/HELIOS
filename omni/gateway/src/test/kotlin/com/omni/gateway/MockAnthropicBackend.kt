package com.omni.gateway

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Response
import okio.Buffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process mock of Anthropic's Messages API, implemented as an OkHttp
 * [Interceptor] (same pattern as the sibling's [MockOpenAiBackend]) — zero
 * new dependencies, no real sockets.
 *
 * - POST /v1/messages → [handler] decides the response (SSE by default)
 * - GET  /v1/models   → JSON list of [modelIds] (never reaches the handler)
 *
 * Every request is appended to a thread-safe log ([LoggedAnthropicCall])
 * recording the `x-api-key` / `anthropic-version` headers and the raw body,
 * so tests can assert the exact wire shape the adapter sends.
 *
 * All keys are DUMMY values ("sk-ant-test-dummy-00N"). No real credentials
 * exist anywhere in this file.
 */
data class MockAnthropicResponse(
    val status: Int,
    val body: String,
    val contentType: String = "application/json"
)

data class LoggedAnthropicCall(
    /** Value of the x-api-key header ("" when absent). */
    val key: String,
    val anthropicVersion: String,
    val path: String,
    val body: String,
    val callIndex: Int
)

class MockAnthropicBackend : Interceptor {

    /** Model ids returned by GET /v1/models. */
    @Volatile var modelIds: List<String> = listOf("claude-test-model")

    /**
     * Decides the response for each POST /v1/messages call.
     * Default: a valid SSE stream with 60 input / 45 output tokens.
     */
    @Volatile var handler: (call: LoggedAnthropicCall) -> MockAnthropicResponse =
        { MockAnthropicResponse(200, sseBody(60, 45), "text/event-stream") }

    private val callCounter = AtomicInteger(0)
    private val logLock = Any()
    private val log = mutableListOf<LoggedAnthropicCall>()

    /** An OkHttpClient whose traffic is served by this mock. */
    fun newClient(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val bodyBuf = Buffer()
        req.body?.writeTo(bodyBuf)
        val key = req.header("x-api-key") ?: ""
        val version = req.header("anthropic-version") ?: ""
        val idx = callCounter.getAndIncrement()
        val call = LoggedAnthropicCall(
            key = key,
            anthropicVersion = version,
            path = req.url.encodedPath,
            body = bodyBuf.readUtf8(),
            callIndex = idx
        )
        synchronized(logLock) { log += call }

        val mock = if (req.method == "GET" && call.path == "/v1/models") {
            MockAnthropicResponse(200, modelsBody(*modelIds.toTypedArray()))
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
    fun calls(): List<LoggedAnthropicCall> = synchronized(logLock) { log.toList() }

    companion object {
        /** Valid Anthropic SSE stream: message_start (input usage), text deltas, message_delta (output usage). */
        fun sseBody(promptTokens: Long, completionTokens: Long, vararg tokens: String): String {
            val sb = StringBuilder()
            sb.append("event: message_start\n")
            sb.append("data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_test\",\"type\":\"message\",")
            sb.append("\"role\":\"assistant\",\"content\":[],\"usage\":")
            sb.append("{\"input_tokens\":$promptTokens,\"output_tokens\":0}}}\n\n")
            val words = if (tokens.isEmpty()) arrayOf("mock", " reply") else tokens
            for ((i, w) in words.withIndex()) {
                sb.append("event: content_block_start\n")
                sb.append("data: {\"type\":\"content_block_start\",\"index\":$i,")
                sb.append("\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n")
                sb.append("event: content_block_delta\n")
                sb.append("data: {\"type\":\"content_block_delta\",\"index\":$i,")
                sb.append("\"delta\":{\"type\":\"text_delta\",\"text\":\"$w\"}}\n\n")
            }
            sb.append("event: message_delta\n")
            sb.append("data: {\"type\":\"message_delta\",\"delta\":{},")
            sb.append("\"usage\":{\"output_tokens\":$completionTokens}}\n\n")
            sb.append("event: message_stop\n")
            sb.append("data: {\"type\":\"message_stop\"}\n\n")
            return sb.toString()
        }

        fun errorBody(message: String): String =
            "{\"type\":\"error\",\"error\":{\"type\":\"mock_error\",\"message\":\"$message\"}}"

        fun modelsBody(vararg ids: String): String {
            val data = ids.joinToString(",") { "{\"id\":\"$it\",\"display_name\":\"$it\"}" }
            return "{\"data\":[$data]}"
        }
    }
}
