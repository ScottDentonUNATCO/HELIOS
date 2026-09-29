import java.io.File

/**
 * Follow-up audit on the 5000-claim JSONL produced by the ClaimStress burst.
 * - Loads the persisted ledger (faithful load()).
 * - Flip audit: verify() on every settled (VALIDATED/FALSIFIED) claim must be a
 *   no-op — statuses must never change.
 * - Replay fidelity: load into a second mirror, compare (id, status, evidence length).
 * - Evidence cap: no claim may exceed EVIDENCE_CHARS*2 = 8000 chars.
 */
fun main(args: Array<String>) {
    val out = StringBuilder()
    val txtFile = File("/home/hatch/workspace/omni-app/v7-work/stress/claim-stress.txt")
    // append to the existing log
    out.append(txtFile.readText())
    fun log(s: String) { out.appendLine(s); println(s); txtFile.writeText(out.toString()) }

    val dir = File(args[0])
    val hub = FakeHub()
    val ledger = LedgerMirror(dir, hub)
    val t0 = System.nanoTime()
    ledger.load()
    log("[A] load: ${ledger.claims.size} claims in ${"%.1f".format((System.nanoTime() - t0) / 1e6)}ms")

    // flip audit: verify() must no-op on settled claims
    var settled = 0; var flips = 0
    val t1 = System.nanoTime()
    for (c in ledger.claims.toList()) {
        if (c.status != ClaimStatus.PROPOSED) {
            settled++
            val before = c.status
            ledger.verify(c.id, killHalted = false)
            val after = ledger.claims.first { it.id == c.id }.status
            if (after != before) { flips++; log("[A] FLIP! ${c.id}: $before -> $after") }
        }
    }
    log("[A] flip audit: settled-checked=$settled flips=$flips in ${"%.1f".format((System.nanoTime() - t1) / 1e6)}ms (expect 0 flips)")

    // replay fidelity
    val snapBefore = ledger.claims.map { Triple(it.id, it.status, it.evidence.length) }
    val ledger2 = LedgerMirror(dir, FakeHub())
    ledger2.load()
    val snapAfter = ledger2.claims.map { Triple(it.id, it.status, it.evidence.length) }
    log("[A] persist->load replay: before=${snapBefore.size} after=${snapAfter.size} identical=${snapBefore == snapAfter}")

    val overCap = ledger.claims.count { it.evidence.length > 8000 }
    val overContent = ledger.claims.count { it.content.length > 2000 }
    log("[A] evidence over 8000 chars: $overCap (expect 0); content over 2000 chars: $overContent (expect 0)")
    val maxEv = ledger.claims.maxOf { it.evidence.length }
    log("[A] max evidence length=$maxEv")
    log("CLAIM-STRESS DONE")
}
