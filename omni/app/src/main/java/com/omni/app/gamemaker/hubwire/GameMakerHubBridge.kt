package com.omni.app.gamemaker.hubwire

import com.omni.app.hub.ClaimStatus
import java.io.File
import java.util.zip.CRC32

/**
 * TRACK E — adapter: every generated game becomes a claim in the sibling
 * coordinator's ledger.
 *
 * Mapping:
 * - claim title   = the game brief (spec title).
 * - claim content = ROM metadata + validator verdict summary (fits the
 *   ledger's 2000-char content cap).
 * - claim evidence = full NesValidator report + PPU-rendered frame hashes;
 *   frame PNGs are saved under the app's filesDir and referenced by path.
 * - producer      = "gamemaker-nes" (honest provenance; not a chat socket,
 *   so the sibling's verifier flow can still pick an independent verifier).
 *
 * THE HONEST RULE (enforced here, in code):
 * - validator GREEN (zero FAIL findings)  -> VALIDATED, with the report and
 *   frames attached as evidence. The validator IS the verifier for these
 *   claims; no LLM socket is asked to re-judge machine proof.
 * - anything else                         -> stays PROPOSED, with the
 *   failure reasons (fired rule IDs, build errors, loop note) recorded in
 *   the claim. There is no FAILED claim status in the sibling's
 *   [ClaimStatus]; PROPOSED-with-reasons is the honest label, and the
 *   bridge never sets FALSIFIED (that verdict belongs to the sibling's
 *   verifier flow).
 *
 * Defense in depth: VALIDATED requires `validation.passed` AND zero
 * FAIL-severity findings AND non-null [RomMeta]. An inconsistent payload
 * (passed=true but FAILs present) is treated as PROPOSED with an
 * "inconsistent validation payload" note — never green.
 */
class GameMakerHubBridge(
    private val sink: ClaimSink,
    /** App filesDir; frame PNGs land in <filesDir>/gamemaker_evidence/<claimId>/. */
    private val filesDir: File,
) {
    companion object {
        const val PRODUCER_ID = "gamemaker-nes"
        private const val EVIDENCE_DIR = "gamemaker_evidence"
        private const val MAX_FRAMES_SAVED = 12
    }

    /**
     * Files one claim for a finished generation loop. Returns the claim id
     * ("" if the ledger refused the post).
     */
    fun onGameGenerated(game: MadeGame): String {
        val claimId = sink.postClaim(
            title = "NES: ${game.brief.title}",
            content = buildContent(game),
            producerSocketId = PRODUCER_ID,
        )
        if (claimId.isBlank()) return ""

        val frameRefs = saveFrames(claimId, game.frames)
        sink.appendEvidence(claimId, buildValidationEvidence(game, frameRefs))

        val green = game.validation.passed &&
            game.validation.findings.none { it.severity == "FAIL" } &&
            game.romMeta != null

        if (green && game.success) {
            sink.setStatus(
                claimId,
                ClaimStatus.VALIDATED,
                "NesValidator GREEN: 0 FAIL findings; " +
                    (game.validation.bootInfo.ifBlank { "boot evidence n/a" }) +
                    "; ${game.frames.size} PPU frame(s) rendered and hashed.",
            )
        } else {
            val reasons = buildFailureReasons(game)
            sink.appendEvidence(claimId, "--- outcome: PROPOSED (not validated) ---\n$reasons")
        }
        return claimId
    }

    // ---- claim body ------------------------------------------------------

    private fun buildContent(game: MadeGame): String {
        val sb = StringBuilder()
        sb.appendLine("Game brief: ${game.brief.title}" + (if (game.brief.genre.isNotBlank()) " [${game.brief.genre}]" else ""))
        if (game.brief.mechanics.isNotEmpty()) sb.appendLine("Mechanics: ${game.brief.mechanics.joinToString(", ")}")
        val meta = game.romMeta
        if (meta != null) {
            sb.appendLine(
                "ROM: PRG ${meta.prgBytes / 1024}KB, CHR ${meta.chrBytes / 1024}KB, " +
                    "mapper ${meta.mapper}, mirror ${meta.mirroring}, sha256=${meta.sha256Hex.take(16)}…",
            )
        } else {
            sb.appendLine("ROM: none (generation failed before a ROM existed)")
        }
        val fails = game.validation.findings.filter { it.severity == "FAIL" }
        val warns = game.validation.findings.count { it.severity == "WARN" }
        sb.appendLine(
            "Validator: ${if (game.validation.passed) "GREEN" else "RED"} — " +
                "${fails.size} FAIL(s), $warns warning(s)" +
                (if (fails.isNotEmpty()) "; fired: ${fails.joinToString(",") { it.ruleId }}" else ""),
        )
        if (game.attemptSummaries.isNotEmpty()) {
            sb.appendLine("Attempts: ${game.attemptSummaries.size} (${game.attemptSummaries.last().take(120)})")
        }
        game.note?.let { sb.appendLine("Note: ${it.take(200)}") }
        if (game.frames.isNotEmpty()) sb.appendLine("PPU frames rendered: ${game.frames.size}")
        return sb.toString().trim()
    }

    // ---- evidence --------------------------------------------------------

    private fun buildValidationEvidence(game: MadeGame, frameRefs: List<String>): String {
        val sb = StringBuilder()
        sb.appendLine("--- NesValidator report (static proof) ---")
        val fails = game.validation.findings.filter { it.severity == "FAIL" }
        val warns = game.validation.findings.filter { it.severity == "WARN" }
        sb.appendLine("verdict: ${if (game.validation.passed) "GREEN" else "RED"} " +
            "(${fails.size} FAIL, ${warns.size} WARN, ${game.validation.findings.size} total findings)")
        if (game.validation.bootInfo.isNotBlank()) sb.appendLine("boot: ${game.validation.bootInfo}")
        if (fails.isEmpty()) {
            sb.appendLine("FAIL findings: none")
        } else {
            sb.appendLine("FAIL findings:")
            fails.forEach { sb.appendLine("  [${it.ruleId}] ${it.message.take(300)}") }
        }
        warns.forEach { sb.appendLine("WARN [${it.ruleId}] ${it.message.take(300)}") }
        if (game.attemptSummaries.isNotEmpty()) {
            sb.appendLine("--- generation attempts ---")
            game.attemptSummaries.forEach { sb.appendLine("  ${it.take(220)}") }
        }
        if (frameRefs.isNotEmpty()) {
            sb.appendLine("--- PPU-rendered frames (256x240, Headless) ---")
            frameRefs.forEach { sb.appendLine("  $it") }
        } else if (game.success) {
            sb.appendLine("--- PPU frames: none supplied by orchestrator ---")
        }
        return sb.toString().trim()
    }

    private fun buildFailureReasons(game: MadeGame): String {
        val sb = StringBuilder()
        val fails = game.validation.findings.filter { it.severity == "FAIL" }
        if (fails.isNotEmpty()) {
            sb.appendLine("fired rules: ${fails.joinToString(", ") { it.ruleId }}")
            fails.forEach { sb.appendLine("  [${it.ruleId}] ${it.message.take(300)}") }
        } else {
            sb.appendLine("no FAIL findings, but the loop did not report success")
        }
        if (game.attemptSummaries.isNotEmpty()) {
            sb.appendLine("attempts: ${game.attemptSummaries.size}")
            game.attemptSummaries.forEach { sb.appendLine("  ${it.take(220)}") }
        }
        game.note?.let { sb.appendLine("loop note: ${it.take(300)}") }
        if (game.romMeta == null) sb.appendLine("no ROM was produced")
        val inconsistent = game.validation.passed && fails.isNotEmpty()
        if (inconsistent) {
            sb.appendLine("WARNING: payload claimed passed=true with FAIL findings present — treated as PROPOSED, never green.")
        }
        return sb.toString().trim()
    }

    // ---- frame PNGs ------------------------------------------------------

    /**
     * Saves frame PNGs under the track's own filesDir directory (never the
     * ledger's JSONL file) and returns "crc=… -> <relative path>" lines for
     * the evidence text. Caps the count so a long run can't fill storage.
     */
    private fun saveFrames(claimId: String, frames: List<PpuFrameEvidence>): List<String> {
        if (frames.isEmpty()) return emptyList()
        val dir = File(filesDir, "$EVIDENCE_DIR/$claimId")
        return frames.take(MAX_FRAMES_SAVED).mapNotNull { f ->
            runCatching {
                dir.mkdirs()
                val file = File(dir, "frame-%02d.png".format(f.index))
                file.writeBytes(f.pngBytes)
                val crc = f.crc32Hex.ifBlank { crc32Hex(f.pngBytes) }
                "frame ${f.index}: crc=$crc -> $EVIDENCE_DIR/$claimId/${file.name}"
            }.getOrNull()
        }
    }

    private fun crc32Hex(bytes: ByteArray): String {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value.toString(16).padStart(8, '0')
    }
}
