package com.omni.app.gamemaker.hubwire

import com.omni.app.hub.ClaimLedger
import com.omni.app.hub.ClaimStatus
import com.omni.app.hub.HubViewModel
import java.util.concurrent.atomic.AtomicLong

/**
 * TRACK E — [ClaimSink] over the sibling coordinator's ledger
 * (`com.omni.app.hub`, Track H) using ONLY its public API. No sibling file
 * is modified; everything below compiles against public classes, public
 * constructors, and public mutable fields.
 *
 * How each operation maps onto the sibling's public surface:
 * - postClaim: synthesizes a COMPLETED [HubViewModel.HubTask] (public data
 *   class, public constructor) carrying the claim title/content, then calls
 *   the public [ClaimLedger.recordFromTask], which files a PROPOSED claim
 *   and persists it. Idempotent per task id (counter suffix defeats
 *   same-millisecond collisions).
 * - setStatus: the sibling exposes no status setter, so this replaces the
 *   entry in the public `claims` SnapshotStateList via `copy()` — the same
 *   index-replace the ledger itself uses, so Compose observes it. Only
 *   PROPOSED -> VALIDATED is honored (see interface contract).
 * - appendEvidence: same index-replace with appended text (capped).
 *
 * GAPS (precise; each needs a sibling-side change to close):
 *
 * GAP-1 — external status/evidence mutations are NOT persisted.
 * `ClaimLedger.persist()` is private, and `recordFromTask` always writes
 * `evidence = ""`. Mutations made here are visible in the ledger UI for the
 * rest of the session but are lost on app restart: on reload the claim
 * comes back PROPOSED with empty evidence (the on-disk JSONL only ever saw
 * the post). Needed: a public `ClaimLedger.setClaimStatus(id, status,
 * reason)` / `attachEvidence(id, text)` that mutates AND persists, or a
 * public `persist()` hook.
 *
 * GAP-2 — no binary evidence attachments. Sibling `Claim.evidence` is a
 * String; PPU frame PNGs cannot ride on the claim itself. This track works
 * around it by saving PNGs under the app's filesDir
 * (`gamemaker_evidence/<claimId>/`, its own directory — never the ledger's
 * `helios_claims.jsonl`) and citing relative paths + CRC32 hashes in the
 * evidence text. Needed for first-class support: an attachments field on
 * `Claim` (or an evidence-URI convention the ledger screen renders).
 *
 * GAP-3 — no accessor for the live ledger instance. The only `ClaimLedger`
 * lives as a private field inside `ClaimLedgerViewModel` (constructed in
 * MainActivity). A future wiring point must either add a public accessor
 * (e.g. `val ledger: ClaimLedger`) or construct a second
 * `ClaimLedger(appContext, hub)` — public constructor, same backing file —
 * with the caveat that two instances racing `persist()` can clobber each
 * other. This class takes the instance by constructor so the choice stays
 * with the wiring, not the adapter.
 *
 * GAP-4 — the sibling's claim lifecycle assumes an LLM/chat verifier
 * (`verify()` picks a different CHAT socket and parses VERIFIED/FALSIFIED).
 * Game-maker claims carry machine proof (validator report + rendered
 * frames) instead; this adapter treats the validator as the verifier and
 * never routes these claims through `verify()`. If the sibling later wants
 * these claims in its verifier flow, the producer id "gamemaker-nes" is
 * already excluded from verifier selection by `chatSocketIds(excluding=…)`.
 */
class HubLedgerClaimSink(
    private val ledger: ClaimLedger,
) : ClaimSink {

    private val seq = AtomicLong(0)

    override fun postClaim(title: String, content: String, producerSocketId: String): String {
        val now = System.currentTimeMillis()
        val task = HubViewModel.HubTask(
            id = "gm-claim-$now-${seq.incrementAndGet()}",
            title = title,
            instructions = "game-maker provenance record (not a chat task; never run through a socket)",
            socketId = producerSocketId,
            status = HubViewModel.STATUS_COMPLETED,
            result = content,
            createdAt = now,
        )
        return ledger.recordFromTask(task)?.id ?: ""
    }

    override fun setStatus(claimId: String, status: ClaimStatus, reason: String) {
        if (status != ClaimStatus.VALIDATED) return // honest transitions only
        val idx = ledger.claims.indexOfFirst { it.id == claimId }
        if (idx < 0) return
        val claim = ledger.claims[idx]
        if (claim.status != ClaimStatus.PROPOSED) return
        ledger.claims[idx] = claim.copy(
            status = ClaimStatus.VALIDATED,
            evidence = appendCapped(claim.evidence, "VALIDATED: $reason"),
        )
        // NOTE (GAP-1): in-memory only; the sibling persists on its own
        // mutations, not on this one.
    }

    override fun appendEvidence(claimId: String, evidence: String) {
        val idx = ledger.claims.indexOfFirst { it.id == claimId }
        if (idx < 0) return
        val claim = ledger.claims[idx]
        ledger.claims[idx] = claim.copy(evidence = appendCapped(claim.evidence, evidence))
        // NOTE (GAP-1): in-memory only; see class KDoc.
    }

    private fun appendCapped(existing: String, addition: String): String {
        val joined = if (existing.isBlank()) addition else "$existing\n$addition"
        return joined.take(EVIDENCE_CAP)
    }

    companion object {
        /** Stays well under the file sizes the ledger itself writes. */
        private const val EVIDENCE_CAP = 6000
    }
}
