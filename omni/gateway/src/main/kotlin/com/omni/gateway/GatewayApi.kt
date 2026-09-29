package com.omni.gateway

import kotlinx.coroutines.flow.Flow

/**
 * Resolves an API key for a [ProviderConfig.apiKeyRef]. Implemented by the
 * Android Keystore-backed vault in :app; the gateway never stores keys itself.
 */
fun interface CredentialStore {
    fun apiKey(ref: String): String?
}

sealed interface ChatEvent {
    data class Token(val text: String) : ChatEvent
    data class Done(val promptTokens: Long, val completionTokens: Long) : ChatEvent
    data class Error(val message: String) : ChatEvent
}

class GatewayException(message: String, val retryable: Boolean = true) : Exception(message)

interface LlmClient {
    fun streamChat(config: ProviderConfig, apiKey: String, request: ChatRequest): Flow<ChatEvent>
    fun listModels(config: ProviderConfig, apiKey: String): List<String>
}

/**
 * Turns a raw gateway failure into a user-visible message: WHAT happened and
 * WHAT TO DO next. Never returns a bare "Error"/"Failed" or a raw exception
 * string. App layers (chat, hub, socket board) route [ChatEvent.Error]
 * messages through here.
 */
fun actionableGatewayError(raw: String?): String {
    val r = raw?.trim().orEmpty()
    return when {
        r.contains("budget exceeded", ignoreCase = true) ->
            "Spend budget exceeded — raise the cap or reset spend on the Safety tab, then retry."
        r.startsWith("missing api key for provider", ignoreCase = true) ->
            "$r — save the key on the Sockets tab, then retry."
        r.startsWith("no providers available", ignoreCase = true) ->
            "No providers available — enable a socket and save its key on the Sockets tab."
        r.contains("returned HTTP", ignoreCase = true) ->
            "$r — check the socket's key on the Sockets tab and the provider's status page, then retry."
        r.startsWith("network error", ignoreCase = true) ->
            "$r — check the device connection, then retry."
        r.isEmpty() ->
            "The request failed with no details — check the Sockets tab (key saved, socket on) and try again."
        else ->
            "$r — check the Sockets tab (key saved, socket on) and try again."
    }
}
