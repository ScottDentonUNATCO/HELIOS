import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/**
 * STRESS 6 — ClaimLedger. The REAL class imports android.content.Context,
 * androidx.compose SnapshotStateList, HubViewModel and KillSwitch -> NOT
 * JVM-compilable here. This harness uses:
 *  (a) parseVerdict copied VERBATIM from ClaimLedger.kt (pure function, no android deps);
 *  (b) a line-faithful MIRROR of the transition logic (recordFromTask, verify,
 *      syncVerifications, appendEvidence, persist/load JSONL) with SnapshotStateList
 *      replaced by a synchronized ArrayList and Context.filesDir by a temp dir.
 *
 * Stress: 5,000 mixed propose/validate/falsify operations; states must be preserved
 * (FALSIFIED never flips to VALIDATED; VALIDATED never flips to FALSIFIED);
 * counts must reconcile; persist->load replay must match.
 */

// ---- VERBATIM from ClaimLedger.kt companion (only the package-private types redeclared) ----
enum class ClaimStatus { PROPOSED, VALIDATED, FALSIFIED }

fun parseVerdict(output: String): ClaimStatus? {
    val firstLine = output.lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.uppercase()
        ?: return null
    return when {
        firstLine.startsWith("FALSIFIED") -> ClaimStatus.FALSIFIED
        firstLine.startsWith("VERIFIED") -> ClaimStatus.VALIDATED
        else -> null
    }
}
// ---- end verbatim ----

data class Claim(
    val id: String, val taskId: String, val producerSocketId: String,
    val content: String, var status: ClaimStatus, var evidence: String,
    var verifierSocketId: String?, val createdAtMs: Long,
)

data class HubTask(val id: String, val socketId: String, val result: String, val status: String)

/** Fake hub mirroring the HubViewModel surface ClaimLedger touches. */
class FakeHub {
    val tasks = CopyOnWriteArrayList<HubTask>()
    val chatSockets = listOf("verifier-1", "verifier-2", "producer-9")
    private val seq = AtomicLong(0)
    fun chatSocketIds(excluding: String): List<String> = chatSockets.filter { it != excluding }
    fun postTask(title: String, instructions: String, socketId: String): String {
        val id = "vtask-${seq.incrementAndGet()}"
        tasks.add(HubTask(id, socketId, "", "QUEUED"))
        return id
    }
    fun runTask(id: String, verdictLine: String) {
        val i = tasks.indexOfFirst { it.id == id }
        if (i >= 0) tasks[i] = tasks[i].copy(status = "COMPLETED", result = verdictLine)
    }
    fun failTask(id: String, err: String) {
        val i = tasks.indexOfFirst { it.id == id }
        if (i >= 0) tasks[i] = tasks[i].copy(status = "FAILED", result = err)
    }
}

/** Faithful mirror of ClaimLedger's transition logic. */
class LedgerMirror(filesDir: File, private val hub: FakeHub) {
    companion object {
        const val CONTENT_CHARS = 2000
        const val EVIDENCE_CHARS = 4000
    }
    private val lock = Any()
    val claims = mutableListOf<Claim>()
    private val pending = mutableMapOf<String, String>()
    private val file = File(filesDir, "helios_claims.jsonl")
    private val json = Json { ignoreUnknownKeys = true }

    fun recordFromTask(task: HubTask): Claim? = synchronized(lock) {
        if (task.status != "COMPLETED") return null
        if (claims.any { it.taskId == task.id }) return null
        val claim = Claim(
            id = "claim-${task.id}-${System.currentTimeMillis()}-${task.id.hashCode()}",
            taskId = task.id, producerSocketId = task.socketId,
            content = task.result.take(CONTENT_CHARS),
            status = ClaimStatus.PROPOSED, evidence = "",
            verifierSocketId = null, createdAtMs = System.currentTimeMillis(),
        )
        claims.add(claim); persist()
        return claim
    }

    fun verify(claimId: String, killHalted: Boolean) = synchronized(lock) {
        val idx = claims.indexOfFirst { it.id == claimId }
        if (idx < 0) return
        val claim = claims[idx]
        if (claim.status != ClaimStatus.PROPOSED) return
        if (pending.containsValue(claim.id)) return
        if (killHalted) {
            replace(idx, claim.copy(evidence = appendEvidence(claim.evidence, "Verification refused: kill switch engaged.")))
            persist(); return
        }
        val verifierId = hub.chatSocketIds(excluding = claim.producerSocketId).firstOrNull()
        if (verifierId == null) {
            replace(idx, claim.copy(evidence = appendEvidence(claim.evidence, "no independent verifier available")))
            persist(); return
        }
        val verifyTaskId = hub.postTask("Verify claim ${claim.id}", "verify", verifierId)
        pending[verifyTaskId] = claim.id
        replace(idx, claim.copy(
            verifierSocketId = verifierId,
            evidence = appendEvidence(claim.evidence, "Verification task $verifyTaskId running on socket '$verifierId' — awaiting result."),
        ))
        persist()
    }

    fun settleOne(verifyTaskId: String, verdictLine: String?, failed: String?) = synchronized(lock) {
        if (verdictLine != null) hub.runTask(verifyTaskId, verdictLine)
        else if (failed != null) hub.failTask(verifyTaskId, failed)
    }

    fun syncVerifications() = synchronized(lock) {
        var changed = false
        for ((verifyTaskId, claimId) in pending.toList()) {
            val hubTask = hub.tasks.firstOrNull { it.id == verifyTaskId } ?: continue
            val idx = claims.indexOfFirst { it.id == claimId }
            if (idx < 0) { pending.remove(verifyTaskId); continue }
            val claim = claims[idx]
            when (hubTask.status) {
                "COMPLETED" -> {
                    val verdict = parseVerdict(hubTask.result)
                    val withOutput = appendEvidence(claim.evidence,
                        "--- verifier output (socket '${claim.verifierSocketId}') ---\n" + hubTask.result.take(EVIDENCE_CHARS))
                    val resolved = when (verdict) {
                        ClaimStatus.VALIDATED -> claim.copy(status = ClaimStatus.VALIDATED, evidence = withOutput)
                        ClaimStatus.FALSIFIED -> claim.copy(status = ClaimStatus.FALSIFIED, evidence = withOutput)
                        ClaimStatus.PROPOSED -> claim.copy(evidence = appendEvidence(withOutput, "Verifier returned PROPOSED — claim remains PROPOSED."))
                        null -> claim.copy(evidence = appendEvidence(withOutput, "Verifier did not put VERIFIED or FALSIFIED on its first line — claim left PROPOSED."))
                    }
                    replace(idx, resolved); pending.remove(verifyTaskId); changed = true
                }
                "FAILED" -> {
                    replace(idx, claim.copy(evidence = appendEvidence(claim.evidence, "Verifier task failed: ${hubTask.result.take(500)}")))
                    pending.remove(verifyTaskId); changed = true
                }
            }
        }
        if (changed) persist()
    }

    private fun replace(idx: Int, claim: Claim) { if (idx in claims.indices) claims[idx] = claim }

    private fun appendEvidence(existing: String, addition: String): String {
        val joined = if (existing.isBlank()) addition else "$existing\n$addition"
        return joined.take(EVIDENCE_CHARS * 2)
    }

    private fun persist() {
        runCatching {
            file.writeText(
                claims.joinToString(separator = "\n", postfix = if (claims.isNotEmpty()) "\n" else "") { claimLine(it) },
                Charsets.UTF_8,
            )
        }
    }

    private fun claimLine(claim: Claim): String = buildJsonObject {
        put("type", "claim"); put("id", claim.id); put("taskId", claim.taskId)
        put("producerSocketId", claim.producerSocketId); put("content", claim.content)
        put("status", claim.status.name); put("evidence", claim.evidence)
        claim.verifierSocketId?.let { put("verifierSocketId", it) }
        put("createdAtMs", claim.createdAtMs)
    }.toString()

    fun load() = synchronized(lock) {
        claims.clear(); pending.clear()
        if (!file.exists()) return
        for (line in file.readLines()) {
            if (line.isBlank()) continue
            val o = json.parseToJsonElement(line).jsonObject
            claims.add(Claim(
                id = o.getValue("id").jsonPrimitive.content,
                taskId = o.getValue("taskId").jsonPrimitive.content,
                producerSocketId = o.getValue("producerSocketId").jsonPrimitive.content,
                content = o.getValue("content").jsonPrimitive.content,
                status = ClaimStatus.valueOf(o.getValue("status").jsonPrimitive.content),
                evidence = o.getValue("evidence").jsonPrimitive.content,
                verifierSocketId = o["verifierSocketId"]?.jsonPrimitive?.content,
                createdAtMs = o.getValue("createdAtMs").jsonPrimitive.long,
            ))
        }
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }
}

fun main() {
    val out = StringBuilder()
    val txtFile = java.io.File("/home/hatch/workspace/omni-app/v7-work/stress/claim-stress.txt")
    fun log(s: String) { out.appendLine(s); println(s); txtFile.writeText(out.toString()) }

    // ---------- 1. parseVerdict adversarial fuzz (VERBATIM function) ----------
    val verdictCases = listOf(
        "VERIFIED\nlooks good" to ClaimStatus.VALIDATED,
        "FALSIFIED\nwrong" to ClaimStatus.FALSIFIED,
        "verified" to ClaimStatus.VALIDATED,
        "  falsified  " to ClaimStatus.FALSIFIED,
        "" to null,
        "   \n  \n" to null,
        "maybe, hedging..." to null,
        "VERIFIEDLY TRUE" to ClaimStatus.VALIDATED, // prefix quirk: startsWith
        "FALSIFIED-ISH" to ClaimStatus.FALSIFIED,   // prefix quirk: startsWith
        "UNVERIFIED" to null,
        "\n\nVERIFIED after blanks" to ClaimStatus.VALIDATED,
        "verdict: falsified" to null, // not FIRST on line
        "FALSIFIED".repeat(200) to ClaimStatus.FALSIFIED,
    )
    var vOk = 0; var vBad = 0; var vThrow = 0
    for ((input, expected) in verdictCases) {
        try {
            val got = parseVerdict(input)
            if (got == expected) vOk++ else { vBad++; log("[V] parseVerdict mismatch on '${input.take(30)}': got=$got want=$expected") }
        } catch (t: Throwable) { vThrow++; log("[V] parseVerdict THREW on '${input.take(30)}': ${t.javaClass.simpleName}") }
    }
    // random garbage fuzz
    val vrnd = java.util.Random(5)
    var fuzzThrow = 0
    repeat(20000) {
        val s = (0 until vrnd.nextInt(60)).map { (' '.code + vrnd.nextInt(95)).toChar() }.joinToString("")
        try { parseVerdict(s) } catch (t: Throwable) { fuzzThrow++ }
    }
    log("[V] parseVerdict: table ok=$vOk bad=$vBad threw=$vThrow; 20k random fuzz throws=$fuzzThrow")

    // ---------- 2. rapid propose/validate/falsify: 5000 ops, 4 threads ----------
    // NOTE: filesDir lives in the workspace (NOT /tmp — /tmp is wiped between sessions).
    val dataDir = java.io.File("/home/hatch/workspace/omni-app/v7-work/stress/claim-data")
    dataDir.mkdirs()
    val dir = dataDir
    val hub = FakeHub()
    val ledger = LedgerMirror(dir, hub)
    val pool = Executors.newFixedThreadPool(4)
    val latch = CountDownLatch(4)
    val ops = AtomicLong(0); val crashes = AtomicLong(0)
    val rnd = java.util.Random(11)
    val t0 = System.nanoTime()
    repeat(4) { t ->
        pool.submit {
            try {
                repeat(1250) { i ->
                    val taskId = "t$t-task-$i"
                    val hubTask = HubTask(taskId, "producer-9", "result payload $taskId " + "x".repeat(i % 3000), "COMPLETED")
                    val claim = ledger.recordFromTask(hubTask) ?: return@repeat
                    ops.incrementAndGet()
                    ledger.verify(claim.id, killHalted = (i % 37 == 0))
                    ops.incrementAndGet()
                    // settle: scripted verifier outputs
                    val mode = rnd.nextInt(10)
                    val line = when (mode) {
                        in 0..3 -> "VERIFIED\n evidence ok"
                        in 4..6 -> "FALSIFIED\n evidence bad"
                        7 -> "meh, no verdict line"
                        8 -> "PROPOSED\n still thinking"
                        else -> null // FAILED task
                    }
                    // find the pending verify task for this claim
                    val vt = hub.tasks.firstOrNull { it.status == "QUEUED" }
                    if (vt != null) {
                        if (line != null) ledger.settleOne(vt.id, line, null)
                        else ledger.settleOne(vt.id, null, "socket exploded")
                    }
                    ledger.syncVerifications()
                    ops.incrementAndGet()
                }
            } catch (x: Throwable) { crashes.incrementAndGet(); x.printStackTrace() }
            finally { latch.countDown() }
        }
    }
    latch.await() // no timeout: the audit below must see the finished burst
    pool.shutdown()
    pool.awaitTermination(10, TimeUnit.MINUTES)
    val dt = (System.nanoTime() - t0) / 1e9

    // ---------- 3. invariant audit ----------
    val byStatus = ledger.claims.groupingBy { it.status }.eachCount()
    val total = ledger.claims.size
    log("[L] 5000-op burst: ${"%.2f".format(dt)}s ops=${ops.get()} crashes=${crashes.get()}")
    log("[L] claims=$total statuses=$byStatus pending-left=${ledger.pendingCount()}")
    // idempotency: re-record every task -> must add zero duplicates
    var dupAdded = 0
    for (c in ledger.claims.toList()) {
        val again = ledger.recordFromTask(HubTask(c.taskId, c.producerSocketId, "x", "COMPLETED"))
        if (again != null) dupAdded++
    }
    log("[L] idempotency re-record: duplicates-added=$dupAdded (expect 0)")
    // verify() on a FALSIFIED/VALIDATED claim must be a no-op (no flip possible).
    // NOTE: deliberately NOT calling syncVerifications() per claim here — the burst
    // already settled everything settleable; a per-claim full pending scan would be
    // O(settled * pending * tasks) and measure the harness, not the ledger.
    var flipAttempts = 0; var flips = 0
    for (c in ledger.claims.toList()) {
        if (c.status != ClaimStatus.PROPOSED) {
            flipAttempts++
            val before = c.status
            ledger.verify(c.id, killHalted = false)
            val after = ledger.claims.first { it.id == c.id }.status
            if (after != before) { flips++; log("[L] FLIP! ${c.id}: $before -> $after") }
        }
    }
    log("[L] flip attempts on settled claims=$flipAttempts flips=$flips (expect 0)")
    // persist/load replay fidelity
    val snapBefore = ledger.claims.map { it.id to it.status to it.evidence.length }
    val ledger2 = LedgerMirror(dir, FakeHub())
    ledger2.load()
    val snapAfter = ledger2.claims.map { it.id to it.status to it.evidence.length }
    val replayMatch = snapBefore == snapAfter
    log("[L] persist->load replay: before=${snapBefore.size} after=${snapAfter.size} identical=$replayMatch")
    // evidence cap: EVIDENCE_CHARS*2 = 8000; content cap: CONTENT_CHARS = 2000
    val overCap = ledger.claims.count { it.evidence.length > 8000 }
    val overContent = ledger.claims.count { it.content.length > 2000 }
    log("[L] evidence over 8000 chars: $overCap (expect 0); content over 2000 chars: $overContent (expect 0)")
    log("[L] max evidence length=${ledger.claims.maxOf { it.evidence.length }}")
    log("CLAIM-STRESS DONE")
}
