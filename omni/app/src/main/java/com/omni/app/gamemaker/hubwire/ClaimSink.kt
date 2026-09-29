package com.omni.app.gamemaker.hubwire

import com.omni.app.hub.ClaimStatus

/**
 * TRACK E — minimal seam between the NES game-maker and the hub's claim
 * ledger (Track H, package `com.omni.app.hub`, owned by the sibling
 * coordinator — read-only for this track).
 *
 * Why an interface instead of calling the sibling directly: the sibling's
 * public API cannot express what the game-maker needs (see GAP-1..GAP-3 in
 * [HubLedgerClaimSink]). The interface states the need; the bundled
 * [HubLedgerClaimSink] implements it with only public sibling API today, and
 * can be swapped for a first-class sibling implementation later without
 * touching [GameMakerHubBridge].
 */
interface ClaimSink {
    /**
     * Posts a new PROPOSED claim. Returns the claim id, or "" when the
     * ledger refused it (e.g. duplicate task id).
     */
    fun postClaim(title: String, content: String, producerSocketId: String): String

    /**
     * Honest transitions only. Implementations must refuse anything but
     * PROPOSED -> VALIDATED: FALSIFIED belongs to the sibling's verifier
     * flow, and no caller may invent it.
     */
    fun setStatus(claimId: String, status: ClaimStatus, reason: String)

    /** Appends human/machine-readable evidence text to a claim. */
    fun appendEvidence(claimId: String, evidence: String)
}

/** Which game was requested. Mirrors `omni.nes.gen.GameSpec` (not on the app classpath). */
data class GameBrief(
    val title: String,
    val genre: String = "",
    val controls: String = "",
    val mechanics: List<String> = emptyList(),
)

/** ROM metadata. Mirrors what `omni.nes.rom.INes.parse` yields for a built ROM. */
data class RomMeta(
    /** 16384 or 32768. */
    val prgBytes: Int,
    /** 8192 for the game-maker's CHR bank. */
    val chrBytes: Int,
    val mapper: Int,
    val mirroring: String,
    /** Hex SHA-256 of the full iNES file. */
    val sha256Hex: String,
)

/** One validator finding. Mirrors `omni.nes.validate.Finding`. */
data class ValidatorFinding(
    /** "FAIL", "WARN", or "INFO" — mirrors `omni.nes.validate.Severity`. */
    val severity: String,
    /** Stable machine-readable id, e.g. "ILLEGAL_OPCODE", "NMI_ENABLED". */
    val ruleId: String,
    val message: String,
)

/**
 * The validator's verdict. Mirrors `omni.nes.validate.ValidationReport`.
 * `passed` is true IFF zero FAIL-severity findings — the bridge re-derives
 * this from [findings] rather than trusting the boolean alone.
 */
data class GameValidation(
    val passed: Boolean,
    val findings: List<ValidatorFinding>,
    /** The validator's BOOT info line (cycles, NMI frames, ppuctrl), if any. */
    val bootInfo: String = "",
)

/**
 * One PPU-rendered frame as static proof the ROM draws something.
 * Produced by the orchestrator via `omni.nes.tools.Headless.run(rom, n,
 * keepFrames = true)` + `omni.nes.img.Png.encode(256, 240, framebuffer)`.
 */
data class PpuFrameEvidence(
    val index: Int,
    val pngBytes: ByteArray,
    /** CRC32 hex of the 256x240 framebuffer (`Headless.hash`). */
    val crc32Hex: String,
)

/**
 * Everything the game-maker hands the bridge after a generation loop
 * finishes. Mirrors `omni.nes.gen.GenerationResult`.
 *
 * ASSUMPTION (documented per task): no in-app game-maker orchestrator has
 * landed yet — there is no `com.omni.app.gamemaker` package and no
 * `makeGame` symbol anywhere in the repo. Track B's orchestrator (or its
 * future `makeGame` flow) constructs this from:
 *
 * | MadeGame field   | Source in omni.nes                              |
 * |------------------|-------------------------------------------------|
 * | brief            | GameSpec (title/genre/controls/mechanics)       |
 * | romMeta          | INes.parse(finalRom): prg/chr sizes, mapper,    |
 * |                  | mirroring; SHA-256 of the iNES bytes            |
 * | validation       | NesValidator(...).validate(finalRom)            |
 * | frames           | Headless.run(finalRom, n, keepFrames=true)      |
 * | attemptSummaries | GenerationResult.attempts (one line each)       |
 * | note             | GenerationResult.note                           |
 * | success          | GenerationResult.success (== validator GREEN)   |
 *
 * `finalRom` is null on failure; [romMeta]/[frames] are then null too and
 * the claim records the failure reasons instead.
 */
data class MadeGame(
    val brief: GameBrief,
    /** True IFF the final ROM passed the validator with zero FAILs. */
    val success: Boolean,
    val romMeta: RomMeta?,
    val validation: GameValidation,
    val frames: List<PpuFrameEvidence> = emptyList(),
    val attemptSummaries: List<String> = emptyList(),
    val note: String? = null,
)
