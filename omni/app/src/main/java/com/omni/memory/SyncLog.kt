package com.omni.memory

enum class SyncOpType { PUT, DELETE }

/**
 * One replicated write. The [record] is carried whole on PUT (null on DELETE)
 * so a merge conflict can always preserve the loser's full content.
 */
data class SyncOp(
    val opId: String,
    val recordId: String,
    val type: SyncOpType,
    val version: Long,
    val lamportTs: Long,
    val deviceId: String,
    val record: MemoryRecord?,
)

data class MergeResult(
    val appliedOps: List<SyncOp>,
    val conflicts: List<SyncOp>,
    val losersPreserved: Int = conflicts.size,
)

/**
 * Lamport-clocked op log for seamless offline-online sync with zero loss.
 * Merge takes the union of ops by opId (idempotent); per recordId the winner
 * is max version, then max lamportTs, then max deviceId. A CONCURRENT conflict
 * — same recordId+version, different lamportTs/deviceId, different content —
 * keeps the higher-lamportTs winner AND preserves the loser whole in
 * [MergeResult.conflicts]. Superseded older versions stay in the log as
 * history but are not conflicts; identical duplicates are not conflicts.
 */
class SyncLog(val deviceId: String) {
    private var lamport = 0L
    private var counter = 0L
    private val opsById = LinkedHashMap<String, SyncOp>()

    @Synchronized
    fun append(type: SyncOpType, recordId: String, version: Long, record: MemoryRecord?): SyncOp {
        if (type == SyncOpType.PUT) requireNotNull(record) { "PUT requires a record" }
        lamport += 1
        counter += 1
        val op = SyncOp("op:$deviceId:$counter", recordId, type, version, lamport, deviceId, record)
        opsById[op.opId] = op
        return op
    }

    @Synchronized
    fun ops(): List<SyncOp> = opsById.values.toList()

    /** Current winning op for [recordId], or null if none. */
    @Synchronized
    fun winningOp(recordId: String): SyncOp? =
        opsById.values.filter { it.recordId == recordId }
            .maxWithOrNull(
                compareBy<SyncOp> { it.version }
                    .thenBy { it.lamportTs }
                    .thenBy { it.deviceId }
            )

    @Synchronized
    fun merge(other: SyncLog): MergeResult {
        val incoming = other.ops().filter { it.opId !in opsById }
        for (op in incoming) {
            opsById[op.opId] = op
            lamport = maxOf(lamport, op.lamportTs) + 1
        }
        val winnerOf = compareBy<SyncOp> { it.version }
            .thenBy { it.lamportTs }
            .thenBy { it.deviceId }
        val conflicts = mutableListOf<SyncOp>()
        for ((_, group) in opsById.values.groupBy { it.recordId }) {
            if (group.size < 2) continue
            val winner = group.maxWith(winnerOf)
            for (loser in group) {
                if (loser.opId == winner.opId) continue
                if (loser.version < winner.version) continue // superseded; history kept, not a conflict
                if (loser.type == winner.type && loser.record == winner.record) continue // duplicate delivery
                conflicts.add(loser) // concurrent conflict: loser preserved whole
            }
        }
        return MergeResult(appliedOps = incoming, conflicts = conflicts)
    }
}
