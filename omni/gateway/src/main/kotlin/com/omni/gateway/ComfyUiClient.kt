package com.omni.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/**
 * Failure talking to a ComfyUI server. [retryable] is true only for
 * transport-level problems (network down, HTTP 5xx); a rejected workflow or
 * a failed render is NOT retryable — the user must fix the workflow.
 */
class ComfyUiException(message: String, val retryable: Boolean = false) : Exception(message)

/** One image file produced by a ComfyUI prompt (addressable via GET /view). */
data class ComfyImage(
    val filename: String,
    val subfolder: String,
    val type: String,
)

/** Final state of a prompt after polling /history. */
data class ComfyResult(
    val promptId: String,
    val images: List<ComfyImage>,
    val statusMessages: List<String>,
)

/**
 * Pure-Kotlin HTTP client for a self-hosted ComfyUI server.
 *
 * ComfyUI is GPL-3.0, so per the socket licensing policy it is NEVER compiled
 * into the APK — this client only drives a user-supplied server over HTTP:
 * POST /prompt with a workflow JSON, poll GET /history/{prompt_id} until the
 * run completes, then download results via GET /view.
 *
 * No API key: ComfyUI's API is unauthenticated by default. The server URL is
 * user-configured (a CUSTOM-kind socket); nothing here invents a URL.
 */
class ComfyUiClient(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Queues [workflowJson] (a ComfyUI API-format workflow) on the server.
     * Returns the prompt_id. Throws [ComfyUiException] when the workflow is
     * not valid JSON or the server rejects it (the server's node_errors are
     * included in the message).
     */
    fun submitPrompt(
        baseUrl: String,
        workflowJson: String,
        clientId: String = UUID.randomUUID().toString(),
    ): String {
        runCatching { json.parseToJsonElement(workflowJson) }.getOrElse {
            throw ComfyUiException("workflow is not valid JSON: ${it.message}")
        }
        val body = """{"prompt":$workflowJson,"client_id":"$clientId"}"""
        val request = Request.Builder()
            .url(trim(baseUrl) + "/prompt")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request, "submit prompt").use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw comfyHttpError(resp.code, text, "submit prompt")
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
                throw ComfyUiException("ComfyUI returned non-JSON on POST /prompt: ${text.take(200)}")
            }
            root["error"]?.let { err ->
                throw ComfyUiException("ComfyUI rejected the prompt: ${err.toString().take(600)}")
            }
            root["node_errors"]?.let { nodeErrors ->
                if (nodeErrors.toString() != "{}") {
                    throw ComfyUiException("ComfyUI node errors: ${nodeErrors.toString().take(600)}")
                }
            }
            return root["prompt_id"]?.jsonPrimitive?.contentOrNull
                ?: throw ComfyUiException(
                    "ComfyUI POST /prompt response had no prompt_id: ${text.take(200)}",
                )
        }
    }

    /**
     * One poll of GET /history/{promptId}.
     * Returns null while the prompt is still queued/running (ComfyUI answers
     * `{}` until the run finishes), or the finished [ComfyResult].
     * Throws [ComfyUiException] when the run failed server-side.
     */
    fun pollOnce(baseUrl: String, promptId: String): ComfyResult? {
        val request = Request.Builder()
            .url(trim(baseUrl) + "/history/$promptId")
            .get()
            .build()
        execute(request, "poll history").use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw comfyHttpError(resp.code, text, "poll history")
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
                throw ComfyUiException("ComfyUI returned non-JSON on GET /history: ${text.take(200)}")
            }
            val entry = root[promptId]?.jsonObject ?: return null // still queued/running
            val status = entry["status"]?.jsonObject
            val statusStr = status?.get("status_str")?.jsonPrimitive?.contentOrNull
            val completed = status?.get("completed")?.jsonPrimitive?.contentOrNull == "true" ||
                status?.get("completed").toString() == "true"
            val messages = status?.get("messages")?.jsonArray
                ?.flatMap { entry ->
                    entry.jsonArray.mapNotNull { el ->
                        (el as? JsonPrimitive)?.contentOrNull
                    }
                }
                ?: emptyList()
            if (statusStr == "error") {
                throw ComfyUiException(
                    "ComfyUI run failed: ${messages.joinToString("; ").take(600)}",
                )
            }
            if (!completed) return null
            val images = entry["outputs"]?.jsonObject?.values
                ?.mapNotNull { it.jsonObject["images"]?.jsonArray }
                ?.flatten()
                ?.mapNotNull { el ->
                    val o = el.jsonObject
                    val filename = o["filename"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    ComfyImage(
                        filename = filename,
                        subfolder = o["subfolder"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        type = o["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                } ?: emptyList()
            return ComfyResult(promptId = promptId, images = images, statusMessages = messages)
        }
    }

    /**
     * Polls until the prompt completes, fails, or [timeoutMs] elapses.
     * Sleeps [intervalMs] between polls. Throws on server-side failure or timeout.
     */
    fun pollUntilDone(
        baseUrl: String,
        promptId: String,
        timeoutMs: Long = 600_000,
        intervalMs: Long = 2_000,
    ): ComfyResult {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        require(intervalMs > 0) { "intervalMs must be positive" }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val done = pollOnce(baseUrl, promptId)
            if (done != null) return done
            if (System.currentTimeMillis() >= deadline) {
                throw ComfyUiException(
                    "timed out waiting for ComfyUI prompt $promptId after ${timeoutMs}ms",
                    retryable = true,
                )
            }
            try {
                Thread.sleep(intervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ComfyUiException("interrupted while polling ComfyUI")
            }
        }
    }

    /** Downloads one rendered file via GET /view. Returns the raw bytes. */
    fun downloadView(baseUrl: String, image: ComfyImage): ByteArray {
        val url = trim(baseUrl).toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment("view")
            ?.addQueryParameter("filename", image.filename)
            ?.addQueryParameter("subfolder", image.subfolder)
            ?.addQueryParameter("type", image.type)
            ?.build()
            ?: throw ComfyUiException("bad ComfyUI base URL: '$baseUrl'")
        val request = Request.Builder().url(url).get().build()
        execute(request, "download result").use { resp ->
            if (!resp.isSuccessful) {
                throw comfyHttpError(resp.code, resp.body?.string().orEmpty(), "download result")
            }
            return resp.body?.bytes()
                ?: throw ComfyUiException("ComfyUI /view returned an empty body")
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private fun trim(baseUrl: String): String {
        val t = baseUrl.trim().trimEnd('/')
        require(t.startsWith("http://") || t.startsWith("https://")) {
            "ComfyUI base URL must start with http:// or https://, got '$baseUrl'"
        }
        return t
    }

    private fun execute(request: Request, what: String): okhttp3.Response {
        return try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ComfyUiException("network error trying to $what: ${e.message}", retryable = true)
        }
    }

    private fun comfyHttpError(code: Int, body: String, what: String): ComfyUiException {
        val detail = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.toString() ?: root["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val hint = when (code) {
            404 -> " — is this really a ComfyUI server URL? Check the address."
            400 -> " — the workflow was rejected; check node names and model files on the server."
            else -> ""
        }
        return ComfyUiException(
            "ComfyUI $what failed with HTTP $code${detail?.let { ": ${it.take(300)}" } ?: ""}$hint",
            retryable = code >= 500,
        )
    }
}
