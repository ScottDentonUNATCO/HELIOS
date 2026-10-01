package com.omni.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Picks the [LlmClient] for a provider: the Anthropic socket speaks the
 * native Messages API (not OpenAI-compatible), everything else goes through
 * [OpenAiCompatClient].
 */
fun clientForProvider(openAiCompat: LlmClient, anthropic: LlmClient): (ProviderConfig) -> LlmClient =
    { provider ->
        if (provider.id == ModelCatalog.ANTHROPIC_SOCKET_ID) anthropic else openAiCompat
    }

/**
 * [LlmClient] speaking Anthropic's native Messages API
 * (`POST /v1/messages`, `x-api-key` + `anthropic-version` headers).
 *
 * The catalog's "anthropic" socket is NOT OpenAI-compatible, so it can never
 * go through [OpenAiCompatClient] — this adapter translates to/from the
 * gateway's chat message model instead:
 * - "system" messages become the top-level `system` parameter
 * - consecutive same-role turns are merged (the API requires strict
 *   user/assistant alternation)
 * - streaming SSE: `content_block_delta` text deltas -> tokens; usage from
 *   `message_start` (input) and `message_delta` (output)
 */
class AnthropicClient(private val http: OkHttpClient) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    override fun streamChat(config: ProviderConfig, apiKey: String, request: ChatRequest): Flow<ChatEvent> =
        flow {
            val httpRequest = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .post(buildRequestBody(request).toRequestBody("application/json".toMediaType()))
                .build()

            val response = try {
                http.newCall(httpRequest).execute()
            } catch (e: IOException) {
                throw GatewayException("network error calling '${config.id}': ${e.message}", retryable = true)
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw httpGatewayError(config.id, resp.code, resp.body?.string())
                }
                var promptTokens = 0L
                var completionTokens = 0L
                val source = resp.body!!.source()
                while (true) {
                    val line = try {
                        source.readUtf8Line()
                    } catch (e: IOException) {
                        throw GatewayException("stream read error from '${config.id}': ${e.message}", retryable = true)
                    } ?: break
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val evt = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                        ?: continue // skip malformed event lines
                    evt["delta"]?.jsonObject?.get("text")
                        ?.jsonPrimitive?.takeIf { it.isString }?.content
                        ?.let { emit(ChatEvent.Token(it)) }
                    // Usage rides on message_start (input) and message_delta
                    // (output); the nested message.usage shape is also handled.
                    evt["usage"]?.jsonObject?.let { readUsage(it)?.let { (p, c) ->
                        if (p >= 0) promptTokens = p
                        if (c >= 0) completionTokens = c
                    } }
                    evt["message"]?.jsonObject?.get("usage")?.jsonObject?.let { readUsage(it)?.let { (p, c) ->
                        if (p >= 0) promptTokens = p
                        if (c >= 0) completionTokens = c
                    } }
                }
                emit(ChatEvent.Done(promptTokens, completionTokens))
            }
        }

    override fun listModels(config: ProviderConfig, apiKey: String): List<String> {
        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/v1/models")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .get()
            .build()
        try {
            http.newCall(httpRequest).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw httpGatewayError(config.id, resp.code, resp.body?.string())
                }
                val root = json.parseToJsonElement(resp.body!!.string()).jsonObject
                return root["data"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                    ?: emptyList()
            }
        } catch (e: GatewayException) {
            throw e
        } catch (e: IOException) {
            throw GatewayException("network error calling '${config.id}': ${e.message}", retryable = true)
        }
    }

    /** Returns (inputTokens, outputTokens); -1 for whichever side is absent. */
    private fun readUsage(usage: JsonObject): Pair<Long, Long>? {
        val p = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: -1L
        val c = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: -1L
        return if (p < 0 && c < 0) null else (p to c)
    }

    private fun buildRequestBody(request: ChatRequest): String {
        fun esc(s: String): String = buildString {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }
        val system = request.messages
            .filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
        // The API requires strict user/assistant alternation — merge
        // consecutive same-role turns instead of 400ing.
        val turns = mutableListOf<Pair<String, String>>()
        for (m in request.messages) {
            if (m.role == "system") continue
            val role = if (m.role == "assistant") "assistant" else "user"
            val last = turns.lastOrNull()
            if (last != null && last.first == role) {
                turns[turns.lastIndex] = role to (last.second + "\n\n" + m.content)
            } else {
                turns += role to m.content
            }
        }
        val messagesJson = turns.joinToString(",") { (role, content) ->
            """{"role":"$role","content":"${esc(content)}"}"""
        }
        return buildString {
            append("""{"model":"${esc(request.model)}","max_tokens":${request.maxTokens},"stream":true,"messages":[$messagesJson]""")
            if (system.isNotEmpty()) append(""","system":"${esc(system)}"""")
            append("}")
        }
    }

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
