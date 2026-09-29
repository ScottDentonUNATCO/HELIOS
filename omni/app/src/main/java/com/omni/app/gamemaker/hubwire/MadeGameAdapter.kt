package com.omni.app.gamemaker.hubwire

import android.graphics.Bitmap
import com.omni.app.gamemaker.nes.AttemptSummary
import com.omni.app.gamemaker.nes.MakerResult
import omni.nes.rom.INes
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * FINAL-PASS adapter (2026-09-24): builds Track E's neutral [MadeGame] from
 * Track B's [MakerResult].
 *
 * This is the missing link Track E documented in its ASSUMPTION block —
 * "Track B's orchestrator constructs MadeGame per the KDoc mapping table".
 * Rather than touching Track B's orchestrator, the mapping lives here as a
 * pure function: whoever runs the maker (NesMakerViewModel, the hub, a
 * future agent) calls `result.toMadeGame(briefTitle)` and hands the result
 * to [GameMakerHubBridge.onGameGenerated].
 *
 * Mapping (mirrors the table in ClaimSink.kt):
 * - brief            = GameBrief(title) — Track B's brief is a plain string.
 * - success          = true — MakerResult only exists on a GREEN run.
 * - romMeta          = INes.parse(rom): prg/chr sizes, mapper, mirroring,
 *                      SHA-256 of the iNES bytes. Null if parsing fails
 *                      (the bridge treats that as PROPOSED, never green).
 * - validation       = the real NesValidator report, findings verbatim with
 *                      severity names ("FAIL"/"WARN"/"INFO").
 * - frames           = each Bitmap PNG-encoded; crc32Hex comes from
 *                      Headless.hash (the framebuffer hash, hex) so the
 *                      evidence text matches the pipeline's own hashing.
 * - attemptSummaries = one honest line per attempt.
 * - note             = the headless run's render note, if any.
 */
fun MakerResult.toMadeGame(briefTitle: String): MadeGame {
    val parsed = runCatching { INes.parse(rom) }.getOrNull()
    val romMeta = parsed?.let {
        RomMeta(
            prgBytes = it.prg.size,
            chrBytes = it.chr.size,
            mapper = it.mapper,
            mirroring = it.mirroring.name,
            sha256Hex = sha256Hex(rom),
        )
    }
    return MadeGame(
        brief = GameBrief(title = briefTitle),
        success = true,
        romMeta = romMeta,
        validation = GameValidation(
            passed = report.passed,
            findings = report.findings.map { f ->
                ValidatorFinding(
                    severity = f.severity.name,
                    ruleId = f.ruleId,
                    message = f.message,
                )
            },
        ),
        frames = frames.mapIndexed { i, bmp ->
            PpuFrameEvidence(
                index = i,
                pngBytes = bitmapToPng(bmp),
                crc32Hex = frameHashes.getOrNull(i)?.toString(16)?.padStart(8, '0') ?: "",
            )
        },
        attemptSummaries = attempts.map { it.toLine() },
        note = renderNote,
    )
}

private fun AttemptSummary.toLine(): String = buildString {
    append("attempt $attempt: assembled=$assembled passed=$passed")
    if (firedRules.isNotEmpty()) append(" fired=[${firedRules.joinToString(",")}]")
    if (!repairAction.isNullOrBlank()) append(" repair=$repairAction")
    if (!buildError.isNullOrBlank()) append(" buildError=$buildError")
}.take(220)

private fun bitmapToPng(bmp: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
