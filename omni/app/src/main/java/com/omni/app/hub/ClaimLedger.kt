package com.omni.app.hub

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.omni.app.safety.KillSwitch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/**
 * TRACK H — claim ledger: checks and balances over the hub.
 *
 * Every completed hub task becomes a PROPOSED claim. A *different* enabled
 * CHAT socket verifies or falsifies it. Falsified claims are kept with their
 * evidence — nothing is silently deleted.
 *
 * Claims persist as JSON lines in filesDir/helios_claims.jsonl — this track's
 * own file, never shared with another track's file. The file is rewritten on
 * every mutation (claim counts are small); load() replays it on init.
 */
enum class ClaimStatus { PROPOSED, VALIDATED, FALSIFIED }

data class Claim(
    val id: String,
    val taskId: String,
    val producerSocketId: String,
    val content: String,
    var status: ClaimStatus,
    var evidence: String,
    var verifierSocketId: String?,
    val createdAtMs: Long,
)

class ClaimLedger(appContext: Context, private val hub: HubViewModel) {

    companion object {
        private const val CLAIMS_FILE = "helios_claims.jsonl"
        private const val CONTENT_CHARS = 2000
        private const val EVIDENCE_CHARS = 4000

        /**
         * Verdict-line parser. The verifier is instructed to put VERIFIED or
         * FALSIFIED on its first line; we read the first non-blank line,
         * case-insensitively:
         * - starts with "FALSIFIED" -> FALSIFIED
         * - starts with "VERIFIED"  -> VALIDATED
         * - anything else (empty output, hedging, no verdict line) -> null,
         *   which leaves the claim PROPOSED with a note in evidence.
         */
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
    }

    val claims: SnapshotStateList<Claim> = mutableStateListOf()

    /**
     * Last persist failure, surfaced so the UI can show it instead of
     * swallowing it. Cleared with [clearPersistError].
     */
    var persistError by mutableStateOf<String?>(null)
        private set

    fun clearPersistError() {
        persistError = null
    }

    /** hub verification-task id -> claim id; in memory only. */
    private val pending = mutableMapOf<String, String>()

    /**
     * Raw lines from the claims file that failed to parse. Kept verbatim and
     * rewritten on every persist() so a corrupt line can never silently
     * delete ledger history.
     */
    private val preservedRawLines = mutableListOf<String>()

    private val filesDir: File = appContext.applicationContext.filesDir
    private val json = Json { ignoreUnknownKeys = true }

    init {
        load()
    }

    /**
     * Records a PROPOSED claim from a COMPLETED hub task (content truncated
     * to 2000 chars). Idempotent per task id — sync passes can call it
     * repeatedly without duplicating claims.
     */
    fun recordFromTask(task: HubViewModel.HubTask): Claim? {
        if (task.status != HubViewModel.STATUS_COMPLETED) return null
        if (claims.any { it.taskId == task.id }) return null
        val claim = Claim(
            id = "claim-${task.id}-${System.currentTimeMillis()}",
            taskId = task.id,
            producerSocketId = task.socketId,
            content = task.result.take(CONTENT_CHARS),
            status = ClaimStatus.PROPOSED,
            evidence = "",
            verifierSocketId = null,
            createdAtMs = System.currentTimeMillis(),
        )
        claims.add(claim)
        persist()
        return claim
    }

    /**
     * Starts verification of a PROPOSED claim by a DIFFERENT enabled CHAT
     * socket than the producer. The verifier task runs asynchronously through
     * the hub; [syncVerifications] settles it when the hub task completes.
     *
     * No-ops unless the claim is PROPOSED and no verification is in flight.
     * Kill-switch is checked before any gateway work is fired.
     */
    fun verify(claimId: String) {
        val idx = claims.indexOfFirst { it.id == claimId }
        if (idx < 0) return
        val claim = claims[idx]
        if (claim.status != ClaimStatus.PROPOSED) return
        if (pending.containsValue(claim.id)) return // already in flight

        if (KillSwitch.halted.value) {
            replace(
                idx,
                claim.copy(
                    evidence = appendEvidence(claim.evidence, "Verification refused: kill switch engaged."),
                ),
            )
            persist()
            return
        }

        val verifierId = hub.chatSocketIds(excluding = claim.producerSocketId).firstOrNull()
        if (verifierId == null) {
            replace(
                idx,
                claim.copy(evidence = appendEvidence(claim.evidence, "no independent verifier available")),
            )
            persist()
            return
        }

        val instructions = "Verify or falsify this claim: ${claim.content}\n\n" +
            "Reply on the first line VERIFIED or FALSIFIED, then evidence."
        val verifyTaskId = hub.postTask("Verify claim ${claim.id}", instructions, verifierId)
        pending[verifyTaskId] = claim.id
        hub.runTask(verifyTaskId)

        replace(
            idx,
            claim.copy(
                verifierSocketId = verifierId,
                evidence = appendEvidence(
                    claim.evidence,
                    "Verification task $verifyTaskId running on socket '$verifierId' — awaiting result.",
                ),
            ),
        )
        persist()
    }

    /**
     * Settles verification tasks that finished since the last call. Called by
     * the ledger screen's sync pass. FALSIFIED claims keep full content and
     * evidence forever.
     */
    fun syncVerifications() {
        var changed = false
        for ((verifyTaskId, claimId) in pending.toList()) {
            val hubTask = hub.tasks.firstOrNull { it.id == verifyTaskId } ?: continue
            val idx = claims.indexOfFirst { it.id == claimId }
            if (idx < 0) {
                pending.remove(verifyTaskId)
                continue
            }
            val claim = claims[idx]
            when (hubTask.status) {
                HubViewModel.STATUS_COMPLETED -> {
                    val verdict = parseVerdict(hubTask.result)
                    val withOutput = appendEvidence(
                        claim.evidence,
                        "--- verifier output (socket '${claim.verifierSocketId}') ---\n" +
                            hubTask.result.take(EVIDENCE_CHARS),
                    )
                    val resolved = when (verdict) {
                        ClaimStatus.VALIDATED -> claim.copy(status = ClaimStatus.VALIDATED, evidence = withOutput)
                        ClaimStatus.FALSIFIED -> claim.copy(status = ClaimStatus.FALSIFIED, evidence = withOutput)
                        ClaimStatus.PROPOSED -> claim.copy(
                            evidence = appendEvidence(
                                withOutput,
                                "Verifier returned PROPOSED — claim remains PROPOSED.",
                            ),
                        )
                        null -> claim.copy(
                            evidence = appendEvidence(
                                withOutput,
                                "Verifier did not put VERIFIED or FALSIFIED on its first line — claim left PROPOSED.",
                            ),
                        )
                    }
                    replace(idx, resolved)
                    pending.remove(verifyTaskId)
                    changed = true
                }
                HubViewModel.STATUS_FAILED -> {
                    replace(
                        idx,
                        claim.copy(
                            evidence = appendEvidence(
                                claim.evidence,
                                "Verifier task failed: ${hubTask.result.take(500)}",
                            ),
                        ),
                    )
                    pending.remove(verifyTaskId)
                    changed = true
                }
                else -> { /* still queued/running — leave pending */ }
            }
        }
        if (changed) persist()
    }

    // ---- internals --------------------------------------------------------

    /** Index-replace so Compose observes the mutation. */
    private fun replace(idx: Int, claim: Claim) {
        if (idx in claims.indices) claims[idx] = claim
    }

    private fun appendEvidence(existing: String, addition: String): String {
        val joined = if (existing.isBlank()) addition else "$existing\n$addition"
        return joined.take(EVIDENCE_CHARS * 2)
    }

    private fun claimsFile(): File = File(filesDir, CLAIMS_FILE)

    private fun persist() {
        runCatching {
            val lines = claims.map { claimLine(it) } + preservedRawLines
            claimsFile().writeText(
                if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n"),
                Charsets.UTF_8,
            )
        }.onFailure { e ->
            persistError = "Claim ledger couldn't be saved (${e.javaClass.simpleName}) — " +
                "check device storage. Claims stay in memory but may be lost on restart."
        }
    }

    private fun claimLine(claim: Claim): String = buildJsonObject {
        put("type", "claim")
        put("id", claim.id)
        put("taskId", claim.taskId)
        put("producerSocketId", claim.producerSocketId)
        put("content", claim.content)
        put("status", claim.status.name)
        put("evidence", claim.evidence)
        claim.verifierSocketId?.let { put("verifierSocketId", it) }
        put("createdAtMs", claim.createdAtMs)
    }.toString()

    private fun load() {
        val file = claimsFile()
        if (!file.exists()) return
        val loaded = mutableListOf<Claim>()
        runCatching {
            file.forEachLine(Charsets.UTF_8) { raw ->
                if (raw.isBlank()) return@forEachLine
                val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    ?: return@forEachLine
                if (obj["type"]?.jsonPrimitive?.content != "claim") return@forEachLine
                val claim = runCatching {
                    // An unrecognized status string keeps the claim as PROPOSED
                    // with a note in evidence — never silently dropped, since
                    // the next persist() would delete it from the file.
                    val rawStatus = obj.getValue("status").jsonPrimitive.content
                    val status = runCatching { ClaimStatus.valueOf(rawStatus) }.getOrNull()
                    val savedEvidence = obj["evidence"]?.jsonPrimitive?.content ?: ""
                    val evidence = if (status == null) {
                        appendEvidence(
                            savedEvidence,
                            "Saved status '$rawStatus' is not a known claim status — " +
                                "kept as PROPOSED, history preserved.",
                        )
                    } else {
                        savedEvidence
                    }
                    Claim(
                        id = obj.getValue("id").jsonPrimitive.content,
                        taskId = obj.getValue("taskId").jsonPrimitive.content,
                        producerSocketId = obj.getValue("producerSocketId").jsonPrimitive.content,
                        content = obj.getValue("content").jsonPrimitive.content,
                        status = status ?: ClaimStatus.PROPOSED,
                        evidence = evidence,
                        verifierSocketId = obj["verifierSocketId"]?.jsonPrimitive?.content,
                        createdAtMs = obj.getValue("createdAtMs").jsonPrimitive.long,
                    )
                }.getOrNull()
                if (claim == null) {
                    // Corrupt line: preserve it verbatim so the next persist()
                    // doesn't silently delete history we couldn't parse.
                    preservedRawLines.add(raw)
                    return@forEachLine
                }
                // A verification in flight across an app restart can never
                // resolve (hub tasks don't survive restarts), so reopen it.
                if (claim.status == ClaimStatus.PROPOSED && claim.verifierSocketId != null) {
                    claim.evidence = appendEvidence(
                        claim.evidence,
                        "App restarted before verification resolved — safe to verify again.",
                    )
                    claim.verifierSocketId = null
                }
                loaded.add(claim)
            }
        }
        claims.addAll(loaded)
    }
}
