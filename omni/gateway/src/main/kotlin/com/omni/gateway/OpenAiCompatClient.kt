package com.omni.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
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
 * [LlmClient] speaking the OpenAI `/v1/chat/completions` wire protocol
 * (covers OpenAI, Anthropic-via-adapter, Gemini, DeepSeek, Groq, Ollama,
 * llama.cpp server, ...).
 */
class OpenAiCompatClient(private val http: OkHttpClient) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    override fun streamChat(config: ProviderConfig, apiKey: String, request: ChatRequest): Flow<ChatEvent> =
        flow {
            val body = buildRequestBody(request)
            val httpRequest = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = try {
                http.newCall(httpRequest).execute()
            } catch (e: IOException) {
                throw GatewayException("network error calling '${config.id}': ${e.message}", retryable = true)
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw httpError(config.id, resp.code, resp.body?.string())
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
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val chunk = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                        ?: continue // skip malformed chunk lines
                    chunk["choices"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("delta")
                        ?.jsonObject?.get("content")
                        ?.jsonPrimitive?.takeIf { it.isString }?.content
                        ?.let { emit(ChatEvent.Token(it)) }
                    chunk["usage"]?.jsonObject?.let { usage ->
                        promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: promptTokens
                        completionTokens = usage["completion_tokens"]?.jsonPrimitive?.longOrNull ?: completionTokens
                    }
                }
                emit(ChatEvent.Done(promptTokens, completionTokens))
            }
        }

    override fun listModels(config: ProviderConfig, apiKey: String): List<String> {
        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/v1/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        try {
            http.newCall(httpRequest).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw httpError(config.id, resp.code, resp.body?.string())
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

    private fun buildRequestBody(request: ChatRequest): String {
        // Manual JSON string escaping: every control char is escaped, so a
        // message containing e.g. \u000b can never produce invalid JSON.
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
        val messages = request.messages.joinToString(",") {
            """{"role":"${esc(it.role)}","content":"${esc(it.content)}"}"""
        }
        return """{"model":"${esc(request.model)}","messages":[$messages],"temperature":${request.temperature},"max_tokens":${request.maxTokens},"stream":true}"""
    }

    private fun httpError(providerId: String, code: Int, body: String?): GatewayException {
        val detail = body?.let {
            runCatching {
                json.parseToJsonElement(it).jsonObject["error"]
                    ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }
        val message = "provider '$providerId' returned HTTP $code${detail?.let { ": $it" } ?: ""}"
        return GatewayException(message, retryable = code == 429 || code >= 500)
    }
}
