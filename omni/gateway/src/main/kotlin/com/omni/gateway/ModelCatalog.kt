package com.omni.gateway

/**
 * Per-socket default models and curated price estimates.
 *
 * The default-model map consolidates what used to live in
 * `SocketBoardViewModel.defaultTestModel` (kept as a forwarder): the model
 * each socket's requests actually go out with. A wrong guess surfaces as an
 * exact provider error (e.g. model-not-found) — never silently swallowed.
 */
object ModelCatalog {

    /** Catalog id of the Anthropic socket (native Messages API, not OpenAI-compat). */
    const val ANTHROPIC_SOCKET_ID = "anthropic"

    /**
     * APPROXIMATE per-1K-token prices in USD, as (input, output).
     *
     * ESTIMATES ONLY — reviewed 2026-10-01 against public provider pricing;
     * verify against the provider's pricing page before trusting them for
     * real budgeting. Models missing from this table record tokens with
     * unknown spend (see [SpendTracker.hasUnknownPricedUsage]) — never $0.
     */
    val PRICE_TABLE_USD_PER_1K: Map<String, Pair<Double, Double>> = mapOf(
        "gpt-4o-mini" to (0.00015 to 0.0006),
        "gpt-4o" to (0.0025 to 0.01),
        "deepseek-chat" to (0.00027 to 0.0011),
        "grok-2-latest" to (0.002 to 0.01),
        "mistral-small-latest" to (0.0001 to 0.0003),
        "gemini-2.0-flash" to (0.0001 to 0.0004),
        "command-r" to (0.00015 to 0.0006),
        "sonar" to (0.001 to 0.001),
        "claude-sonnet-4-6" to (0.003 to 0.015),
        "claude-haiku-4-5" to (0.0008 to 0.004),
        "llama-3.3-70b-versatile" to (0.00059 to 0.00079),
        "meta-llama/Llama-3.3-70B-Instruct-Turbo" to (0.00088 to 0.00088),
        "accounts/fireworks/models/llama-v3p3-70b-instruct" to (0.0009 to 0.0009),
    )

    /**
     * Default chat model for a socket id. Sockets without an entry are
     * assumed OpenAI-compatible and fall back to gpt-4o-mini.
     */
    fun defaultModelFor(socketId: String): String = when (socketId) {
        "deepseek" -> "deepseek-chat"
        "xai" -> "grok-2-latest"
        "mistral" -> "mistral-small-latest"
        "gemini-key" -> "gemini-2.0-flash"
        "cohere" -> "command-r"
        "perplexity" -> "sonar"
        "anthropic" -> "claude-sonnet-4-6"
        "groq" -> "llama-3.3-70b-versatile"
        "together" -> "meta-llama/Llama-3.3-70B-Instruct-Turbo"
        "fireworks" -> "accounts/fireworks/models/llama-v3p3-70b-instruct"
        "openrouter" -> "openrouter/auto"
        else -> "gpt-4o-mini"
    }
}
