package com.omni.gateway

/**
 * Tracks cumulative spend in USD. Prices come from the caller; a null
 * [pricePerMillion] (unknown model) records NOTHING — we never invent a price.
 */
class SpendTracker {
    private var total = 0.0

    @Synchronized
    fun record(promptTokens: Long, completionTokens: Long, pricePerMillion: Pair<Double, Double>?) {
        if (pricePerMillion == null) return
        val (inputPerMillion, outputPerMillion) = pricePerMillion
        total += (promptTokens / 1_000_000.0) * inputPerMillion +
            (completionTokens / 1_000_000.0) * outputPerMillion
    }

    @Synchronized
    fun totalUsd(): Double = total

    fun checkBudget(capUsd: Double): Boolean = totalUsd() <= capUsd

    @Synchronized
    fun reset() {
        total = 0.0
    }
}
