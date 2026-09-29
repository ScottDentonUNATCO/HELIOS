package com.omni.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Routes chat requests across providers with failover, latency-aware picks,
 * cooldowns for sick providers, and per-app spend budgeting.
 *
 * @param fallbacks model name -> provider ids to try only after the router's
 *   own picks for that model (see [Router.pickRoute]).
 * @param budgetCapUsd when set, [generate] refuses to start once
 *   [SpendTracker.totalUsd] exceeds the cap.
 * @param priceTable model name -> (input $/1M tokens, output $/1M tokens).
 */
class AiGateway(
    private val providers: List<ProviderConfig>,
    private val credentials: CredentialStore,
    private val client: LlmClient,
    private val router: Router,
    private val tracker: SpendTracker,
    private val fallbacks: Map<String, List<String>> = emptyMap(),
    private val budgetCapUsd: Double? = null,
    private val priceTable: Map<String, Pair<Double, Double>> = emptyMap()
) {
    fun generate(
        model: String,
        messages: List<ChatMessage>,
        preferredProvider: String? = null,
        temperature: Double = 0.7,
        maxTokens: Int = 1024
    ): Flow<ChatEvent> = flow {
        val cap = budgetCapUsd
        if (cap != null && !tracker.checkBudget(cap)) {
            emit(ChatEvent.Error("budget exceeded"))
            return@flow
        }

        val request = ChatRequest(model, messages, temperature, maxTokens)
        val modelFallbacks = fallbacks[model]?.let { mapOf(model to it) } ?: emptyMap()
        var lastError = "no providers available"

        val remaining = providers.toMutableList()
        var preferred = preferredProvider
        while (true) {
            val provider = router.pickRoute(remaining, modelFallbacks, preferred) ?: break
            preferred = null
            remaining.remove(provider)

            val apiKey = credentials.apiKey(provider.apiKeyRef)
            if (apiKey == null) {
                router.recordFailure(provider.id)
                lastError = "missing api key for provider '${provider.id}'"
                continue
            }

            val startMs = System.currentTimeMillis()
            var usage: Pair<Long, Long>? = null
            var streamFailed = false
            try {
                client.streamChat(provider, apiKey, request).collect { event ->
                    if (streamFailed) return@collect
                    when (event) {
                        is ChatEvent.Token -> emit(event)
                        is ChatEvent.Done -> {
                            usage = event.promptTokens to event.completionTokens
                            emit(event)
                        }
                        is ChatEvent.Error -> {
                            // The client itself gave up; fail over to the next provider.
                            streamFailed = true
                            lastError = event.message
                        }
                    }
                }
            } catch (e: GatewayException) {
                if (e.retryable) {
                    router.recordFailure(provider.id)
                    lastError = e.message ?: "provider '${provider.id}' failed"
                    continue
                } else {
                    emit(ChatEvent.Error(e.message ?: "request failed"))
                    return@flow
                }
            }
            if (streamFailed) {
                router.recordFailure(provider.id)
                continue
            }

            router.recordSuccess(provider.id, System.currentTimeMillis() - startMs)
            usage?.let { (prompt, completion) ->
                tracker.record(prompt, completion, priceTable[model])
            }
            return@flow
        }

        emit(ChatEvent.Error(lastError))
    }

    /** Provider id -> configured model list. Config-only; no network. */
    fun availableModels(): Map<String, List<String>> =
        providers.associate { it.id to it.models }
}
