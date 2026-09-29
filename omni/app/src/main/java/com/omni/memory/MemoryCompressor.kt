package com.omni.memory

/**
 * Result of a compression pass. Zero-loss contract: [dropped] is ALWAYS 0 —
 * nothing is ever deleted, only summarized. The store still holds every
 * original record; summaries are lossy navigation aids for a token budget.
 */
data class CompressionResult(
    val kept: List<MemoryRecord>,
    val summaries: List<MemoryRecord>,
    val dropped: Int = 0,
) {
    init { require(dropped == 0) { "compressor must never drop records" } }
}

/**
 * Fits memory into a token budget without losing anything.
 *
 * - Pinned records are ALWAYS kept verbatim (they count against the budget first).
 * - Remaining records are kept verbatim in [ranker] order while they fit.
 * - Everything else is folded into ONE extractive summary record per scope:
 *   scope name + count + every folded id + first 120 chars of each value.
 *
 * Full-fidelity restore path: compression never mutates or deletes store
 * records. To restore, re-fetch originals by id via [MemoryStore.get] /
 * [MemoryStore.listByScope] using the ids listed in each summary's value.
 * A summary is a pointer for a budget-constrained context window, not a
 * replacement for the records it names.
 */
class MemoryCompressor {
    /** Token estimate: ~4 chars per token. */
    fun tokensOf(r: MemoryRecord): Int = (r.key.length + r.value.length) / 4

    fun compress(
        records: List<MemoryRecord>,
        tokenBudget: Int,
        ranker: RecordRanker,
    ): CompressionResult {
        require(tokenBudget >= 0) { "tokenBudget must be >= 0" }
        val kept = mutableListOf<MemoryRecord>()
        var remaining = tokenBudget
        // Pinned first: always verbatim, even if they blow the budget alone.
        for (p in records.filter { it.pinned }) {
            kept.add(p)
            remaining -= tokensOf(p)
        }
        for (r in records.filterNot { it.pinned }.sortedByDescending(ranker)) {
            val t = tokensOf(r)
            if (t <= remaining) {
                kept.add(r)
                remaining -= t
            }
        }
        val keptIds = kept.map { it.id }.toSet()
        val folded = records.filter { it.id !in keptIds }
        val summaries = folded.groupBy { it.scope }.map { (scope, rs) -> summarize(scope, rs) }
        return CompressionResult(kept = kept, summaries = summaries, dropped = 0)
    }

    private fun summarize(scope: MemoryScope, rs: List<MemoryRecord>): MemoryRecord {
        val now = System.currentTimeMillis()
        val lines = rs.joinToString("\n") { r ->
            "${r.id}: ${r.value.take(120).replace('\n', ' ')}"
        }
        val value = "scope=${scope.name} count=${rs.size}\n" +
            "ids: ${rs.joinToString(", ") { it.id }}\n$lines"
        return MemoryRecord(
            id = "summary:${scope.name}:$now",
            scope = scope,
            key = "summary",
            value = value,
            salience = rs.maxOf { it.salience },
            createdAtMs = now,
            updatedAtMs = now,
            version = 1L,
            provenance = "compressor",
            pinned = false,
        )
    }
}
