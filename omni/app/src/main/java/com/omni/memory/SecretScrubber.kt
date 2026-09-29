package com.omni.memory

/**
 * Privacy by default. Masks secret-looking patterns before they reach storage:
 * - `vault:<ref>` (our own credential references)
 * - `sk-...` (OpenAI-style secret keys)
 * - `xoxb-/xoxa-/xoxp-...` (Slack-style tokens)
 * Applied automatically by [InMemoryStore.put]; call directly for defense in depth.
 */
object SecretScrubber {
    private val VAULT = Regex("vault:[^\\s]+")
    private val SK = Regex("sk-[A-Za-z0-9_-]+")
    private val XOX = Regex("xox[bap]-[A-Za-z0-9-]+")

    fun scrub(text: String): String =
        text.replace(VAULT, "vault:[REDACTED]")
            .replace(SK, "sk-[REDACTED]")
            .replace(XOX, "[REDACTED]")
}
