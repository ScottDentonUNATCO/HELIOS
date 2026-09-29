package com.omni.memory

/**
 * One durable memory. [salience] in 0.0..1.0 drives compression and ranking;
 * [pinned] records are never decayed, never summarized away, never evicted.
 * [provenance] names the writer, e.g. "socket:openai/run:12" or "validator:nes".
 */
data class MemoryRecord(
    val id: String,
    val scope: MemoryScope,
    val key: String,
    val value: String,
    val salience: Double,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val version: Long,
    val provenance: String,
    val pinned: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "MemoryRecord.id must not be blank" }
        require(key.isNotBlank()) { "MemoryRecord.key must not be blank" }
        require(!salience.isNaN() && salience in 0.0..1.0) {
            "salience must be in 0.0..1.0, was $salience"
        }
    }

    /** Returns a copy with version+1 and updatedAtMs=now. Creation time is preserved. */
    fun bump(): MemoryRecord =
        copy(version = version + 1, updatedAtMs = System.currentTimeMillis())
}
