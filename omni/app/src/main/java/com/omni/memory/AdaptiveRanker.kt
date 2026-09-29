package com.omni.memory

import kotlin.math.ln
import kotlin.math.pow

/**
 * Adaptive ranking for long builds: what mattered recently, what gets
 * re-read, and what the user pinned. score =
 * 0.5*salience + 0.3*recency + 0.2*freq + pinBoost + scopeBoost, where
 * recency = 0.5^(ageDays/30) (halves every 30 days), freq is log-normalized
 * access frequency, pinned records get +0.5 AND are immune to recency decay,
 * and PROJECT_STATE/PROCEDURE scopes get +0.1 so long-build state and
 * validated procedures surface first.
 */
object AdaptiveRanker {
    private const val MS_PER_DAY = 86_400_000.0
    private const val HALF_LIFE_DAYS = 30.0

    fun score(r: MemoryRecord, nowMs: Long, accessCounts: Map<String, Int>): Double {
        val ageDays = ((nowMs - r.updatedAtMs).coerceAtLeast(0)).toDouble() / MS_PER_DAY
        val recency = if (r.pinned) 1.0 else 0.5.pow(ageDays / HALF_LIFE_DAYS)
        val maxAccesses = accessCounts.values.maxOrNull() ?: 0
        val freq = if (maxAccesses <= 0) 0.0
        else ln(1.0 + (accessCounts[r.id] ?: 0)) / ln(1.0 + maxAccesses)
        val pinBoost = if (r.pinned) 0.5 else 0.0
        val scopeBoost =
            if (r.scope == MemoryScope.PROJECT_STATE || r.scope == MemoryScope.PROCEDURE) 0.1
            else 0.0
        return 0.5 * r.salience + 0.3 * recency + 0.2 * freq + pinBoost + scopeBoost
    }

    fun rank(
        records: List<MemoryRecord>,
        nowMs: Long,
        accessCounts: Map<String, Int>,
    ): List<MemoryRecord> = records.sortedByDescending { score(it, nowMs, accessCounts) }
}
