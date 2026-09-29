package com.omni.gateway

data class RouterConfig(val allowedFails: Int = 3, val cooldownMs: Long = 60_000)

/**
 * Picks providers: preferred (if eligible) -> remaining candidates sorted by
 * average latency (unknown latency sorts last) -> fallback ids. Providers that
 * hit [RouterConfig.allowedFails] consecutive failures cool down for
 * [RouterConfig.cooldownMs] and are skipped while cooling down.
 *
 * `fallbacks` maps a model name to an ordered list of provider ids that should
 * only be used after the router's own picks; [AiGateway] passes just the entry
 * for the requested model. Fallback ids resolve against [candidates]; unknown
 * ids are ignored.
 */
class Router(
    private val config: RouterConfig = RouterConfig(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val failCounts = mutableMapOf<String, Int>()
    private val cooldownUntil = mutableMapOf<String, Long>()
    private val latencySamples = mutableMapOf<String, ArrayDeque<Long>>()

    fun pickRoute(
        candidates: List<ProviderConfig>,
        fallbacks: Map<String, List<String>> = emptyMap(),
        preferredId: String? = null
    ): ProviderConfig? {
        val eligible = candidates.filter { !coolingDown(it.id) }
        if (eligible.isEmpty()) return null
        val byId = eligible.associateBy { it.id }
        preferredId?.let { byId[it] }?.let { return it }
        // Fallback ids are demoted: they sort after the router's own latency picks.
        val fallbackIds = fallbacks.values.flatten().distinct()
        val byLatency = eligible
            .filter { it.id !in fallbackIds }
            .sortedWith(
                compareBy({ avgLatency(it.id) == null }, { avgLatency(it.id) ?: Double.MAX_VALUE })
            )
        val fallbackProviders = fallbackIds.mapNotNull { byId[it] }
        return (byLatency + fallbackProviders).firstOrNull()
    }

    fun recordSuccess(providerId: String, latencyMs: Long) {
        failCounts.remove(providerId)
        cooldownUntil.remove(providerId)
        val samples = latencySamples.getOrPut(providerId) { ArrayDeque() }
        samples.addLast(latencyMs)
        while (samples.size > 10) samples.removeFirst()
    }

    fun recordFailure(providerId: String) {
        val fails = (failCounts[providerId] ?: 0) + 1
        if (fails >= config.allowedFails) {
            failCounts.remove(providerId)
            cooldownUntil[providerId] = clock() + config.cooldownMs
        } else {
            failCounts[providerId] = fails
        }
    }

    fun coolingDown(providerId: String): Boolean {
        val until = cooldownUntil[providerId] ?: return false
        if (clock() >= until) {
            cooldownUntil.remove(providerId)
            return false
        }
        return true
    }

    fun avgLatency(providerId: String): Double? {
        val samples = latencySamples[providerId]
        return if (samples.isNullOrEmpty()) null else samples.average()
    }

    fun reset(providerId: String) {
        failCounts.remove(providerId)
        cooldownUntil.remove(providerId)
        latencySamples.remove(providerId)
    }
}
