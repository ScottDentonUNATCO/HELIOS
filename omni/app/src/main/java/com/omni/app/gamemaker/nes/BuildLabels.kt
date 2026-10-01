package com.omni.app.gamemaker.nes

import android.content.Context
import com.omni.app.gamemaker.core.ContractCheckResult
import java.io.File

/**
 * Honest build labels stamped beside every one-tap ROM.
 *
 * The `.labels.json` file next to each `nes-roms/<slug>.nes` records:
 * - how the draft was actually produced (socket vs offline template),
 * - whether the validator passed and which rules fired,
 * - how many repair attempts it took,
 * - the SHA-256 of the exported ROM.
 *
 * Nothing here is a claim about quality beyond what the validator proved.
 */
data class BuildLabels(
    val romSha256: String,
    val draftSource: String,
    val validatorPassed: Boolean,
    val firedRules: List<String>,
    val attempts: Int,
    val repairActions: List<String>,
)

/**
 * Writes `nes-roms/<slug>.labels.json` for the exported ROM. Pure file IO —
 * no Android beyond Context, so the stamping is unit-testable logic around
 * [labelsJson].
 */
fun stampBuildLabels(
    context: Context,
    romFile: File,
    labels: BuildLabels,
): File {
    val dir = File(context.filesDir, "nes-roms").also { it.mkdirs() }
    val labelsFile = File(dir, "${romFile.nameWithoutExtension}.labels.json")
    labelsFile.writeText(labelsJson(labels))
    return labelsFile
}

/**
 * Serializes labels to the on-disk JSON schema (versioned). Hand-rolled
 * (pure Kotlin, no org.json) so the serializer is unit-testable on the
 * build machine as well as on-device.
 */
fun labelsJson(labels: BuildLabels): String {
    fun str(v: String): String = buildString {
        append('"')
        for (c in v) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
    fun arr(vs: List<String>): String = vs.joinToString(",", "[", "]", transform = ::str)
    return "{\n" +
        "  \"labels_version\": 1,\n" +
        "  \"rom_sha256\": ${str(labels.romSha256)},\n" +
        "  \"draft_source\": ${str(labels.draftSource)},\n" +
        "  \"validator_passed\": ${labels.validatorPassed},\n" +
        "  \"fired_rules\": ${arr(labels.firedRules)},\n" +
        "  \"attempts\": ${labels.attempts},\n" +
        "  \"repair_actions\": ${arr(labels.repairActions)}\n" +
        "}"
}

/**
 * Hex SHA-256 of bytes. Local copy (MadeGameAdapter's is private) so label
 * stamping stays dependency-free and unit-testable.
 */
internal fun sha256Hex(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
