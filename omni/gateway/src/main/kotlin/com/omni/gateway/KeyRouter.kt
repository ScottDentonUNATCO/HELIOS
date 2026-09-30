package com.omni.gateway

/**
 * One labeled API key belonging to a provider.
 *
 * A provider (e.g. "openai") can have N KeyInstances (e.g. "Personal",
 * "Work", "Backup"). The [KeyRouter] round-robins across them, fails over
 * on 429s, and enforces per-key budgets. The actual key material lives in
 * the vault; this only carries the [vaultRef] pointer.
 *
 * Budgets are optional; null means unlimited.
 * - [dailyTokenBudget]: max prompt+completion tokens per calendar day.
 * - [monthlySpendBudgetUsd]: max USD spend per calendar month.
 */
data class KeyInstance(
    val id: String,
    val providerId: String,
    val label: String,
    val vaultRef: String,
    val dailyTokenBudget: Long? = null,
    val monthlySpendBudgetUsd: Double? = null,
    val enabled: Boolean = true
)

data class KeyRouterConfig(
    /** Consecutive 429s before a key cools down. */
    val allowed429s: Int = 2,
    /** Base cooldown after rate-limiting; doubles with consecutive 429s. */
    val baseCooldownMs: Long = 30_000,
    /** Max cooldown cap. */
    val maxCooldownMs: Long = 600_000
)

/**
 * Routes at the KEY level within a provider.
 *
 * - Round-robin across eligible keys (per-provider cursor).
 * - A 429 immediately marks the key rate-limited with exponential backoff
 *   and the caller should try the next key WITHOUT waiting.
 * - Keys over their daily token or monthly spend budget are skipped.
 * - Disabled keys are skipped.
 *
 * Thread-safe for concurrent callers.
 */
class KeyRouter(
    private val config: KeyRouterConfig = KeyRouterConfig(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class State(
        var tokensToday: Long = 0,
        var spendMonthUsd: Double = 0.0,
        var consecutive429s: Int = 0,
        var cooldownUntilMs: Long = 0,
        var dayOfYear: Int = -1,
        var monthKey: Int = -1 // year*12 + month
    )

    private val states = mutableMapOf<String, State>()
    private val cursors = mutableMapOf<String, Int>()
    private val lock = Any()

    private fun stateFor(id: String): State = states.getOrPut(id) { State() }

    /** Resets daily/monthly counters if the calendar rolled over. */
    private fun maybeResetCalendars(s: State, now: Long) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val day = cal.get(java.util.Calendar.DAY_OF_YEAR)
        val month = cal.get(java.util.Calendar.YEAR) * 12 + cal.get(java.util.Calendar.MONTH)
        if (s.dayOfYear != day) {
            s.dayOfYear = day
            s.tokensToday = 0
        }
        if (s.monthKey != month) {
            s.monthKey = month
            s.spendMonthUsd = 0.0
        }
    }

    fun isEligible(key: KeyInstance): Boolean = synchronized(lock) {
        if (!key.enabled) return false
        val s = stateFor(key.id)
        val now = clock()
        maybeResetCalendars(s, now)
        if (now < s.cooldownUntilMs) return false
        key.dailyTokenBudget?.let { if (s.tokensToday >= it) return false }
        key.monthlySpendBudgetUsd?.let { if (s.spendMonthUsd >= it) return false }
        return true
    }

    /**
     * Picks the next eligible key for [providerId] via round-robin.
     * Returns null if no key is currently eligible.
     */
    fun pickKey(providerId: String, keys: List<KeyInstance>): KeyInstance? = synchronized(lock) {
        val pool = keys.filter { it.providerId == providerId && isEligible(it) }
        if (pool.isEmpty()) return null
        val cursor = (cursors[providerId] ?: 0) % pool.size
        cursors[providerId] = cursor + 1
        return pool[cursor]
    }

    /** Records a 429 rate-limit on [keyId]. Returns the cooldown in ms. */
    fun record429(keyId: String): Long = synchronized(lock) {
        val s = stateFor(keyId)
        s.consecutive429s++
        val backoff = config.baseCooldownMs * (1L shl minOf(s.consecutive429s - 1, 4))
        val cooldown = minOf(backoff, config.maxCooldownMs)
        s.cooldownUntilMs = clock() + cooldown
        return cooldown
    }

    /** Records a successful call: clears 429 state, accumulates usage. */
    fun recordSuccess(keyId: String, promptTokens: Long, completionTokens: Long, spendUsd: Double) =
        synchronized(lock) {
            val s = stateFor(keyId)
            maybeResetCalendars(s, clock())
            s.consecutive429s = 0
            // A success clears a 429 cooldown early — the key proved itself healthy.
            s.cooldownUntilMs = 0
            s.tokensToday += promptTokens + completionTokens
            s.spendMonthUsd += spendUsd
        }

    /** Records a non-429 failure (does not trigger rate-limit backoff). */
    fun recordFailure(keyId: String) = synchronized(lock) {
        // Non-429 failures don't affect key rotation; provider-level
        // Router handles those. We just don't reset 429 state here.
    }

    /** Human-readable status for UI / diagnostics. */
    fun status(key: KeyInstance): String = synchronized(lock) {
        val s = stateFor(key.id)
        maybeResetCalendars(s, clock())
        return when {
            !key.enabled -> "disabled"
            clock() < s.cooldownUntilMs -> "rate-limited (${(s.cooldownUntilMs - clock()) / 1000}s)"
            key.dailyTokenBudget != null && s.tokensToday >= key.dailyTokenBudget -> "daily budget exhausted"
            key.monthlySpendBudgetUsd != null && s.spendMonthUsd >= key.monthlySpendBudgetUsd -> "monthly budget exhausted"
            else -> "ready"
        }
    }

    fun reset(keyId: String) {
        synchronized(lock) {
            states.remove(keyId)
        }
    }
}
