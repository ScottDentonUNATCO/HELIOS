package com.omni.memory

/** Thrown when put() receives a record that is not newer than the stored one. */
class StaleVersionException(message: String) : IllegalStateException(message)

/** Ranking function: higher = more important. See [AdaptiveRanker]. */
typealias RecordRanker = (MemoryRecord) -> Double

interface MemoryStore {
    /** Upsert by id. The record must carry a bumped version vs the stored one, else throws. */
    fun put(record: MemoryRecord)
    fun get(id: String): MemoryRecord?
    fun delete(id: String): Boolean
    fun all(): List<MemoryRecord>
    fun listByScope(scope: MemoryScope): List<MemoryRecord>
    /** Ranked substring match over key+value: exact key > key contains > value contains. */
    fun search(query: String): List<MemoryRecord>
    fun topK(limit: Int, ranker: RecordRanker): List<MemoryRecord>
}

/**
 * Local-first store. put() scrubs secrets from values before they touch
 * storage, and rejects stale versions so a slow writer can never clobber
 * newer state. Thread-safe.
 */
class InMemoryStore : MemoryStore {
    private val lock = Any()
    private val records = LinkedHashMap<String, MemoryRecord>()

    override fun put(record: MemoryRecord) {
        val clean = record.copy(value = SecretScrubber.scrub(record.value))
        synchronized(lock) {
            val existing = records[clean.id]
            if (existing != null && clean.version <= existing.version)
                throw StaleVersionException(
                    "stale version ${clean.version} for '${clean.id}' (stored ${existing.version})")
            records[clean.id] = clean
        }
    }

    override fun get(id: String): MemoryRecord? = synchronized(lock) { records[id] }

    override fun delete(id: String): Boolean = synchronized(lock) { records.remove(id) != null }

    override fun all(): List<MemoryRecord> = synchronized(lock) { records.values.toList() }

    override fun listByScope(scope: MemoryScope): List<MemoryRecord> = synchronized(lock) {
        records.values.filter { it.scope == scope }.sortedByDescending { it.updatedAtMs }
    }

    override fun search(query: String): List<MemoryRecord> {
        if (query.isBlank()) return emptyList()
        return synchronized(lock) {
            records.values.mapNotNull { r ->
                val s = matchScore(r, query)
                if (s > 0.0) r to s else null
            }.sortedWith(
                compareByDescending<Pair<MemoryRecord, Double>> { it.second }
                    .thenByDescending { it.first.salience }
                    .thenByDescending { it.first.updatedAtMs }
            ).map { it.first }
        }
    }

    private fun matchScore(r: MemoryRecord, q: String): Double = when {
        r.key.equals(q, ignoreCase = true) -> 3.0
        r.key.contains(q, ignoreCase = true) -> 2.0
        r.value.contains(q, ignoreCase = true) -> 1.0
        else -> 0.0
    }

    override fun topK(limit: Int, ranker: RecordRanker): List<MemoryRecord> =
        synchronized(lock) {
            records.values.sortedByDescending(ranker).take(limit.coerceAtLeast(0))
        }
}
