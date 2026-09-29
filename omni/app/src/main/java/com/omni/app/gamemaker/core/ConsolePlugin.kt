package com.omni.app.gamemaker.core

/**
 * TRACK C — console plugin contract for the Helios game-maker module.
 *
 * One [ConsolePlugin] per target console. The NES plugin is the full,
 * working implementation; every other console is an honest stub whose UI
 * label says "not yet — NES first, this console is coming" and whose
 * methods throw [UnsupportedOperationException] instead of faking support.
 *
 * Types are deliberately simple and Android-friendly:
 * - ROMs are [ByteArray] (iNES bytes, SMD bytes, whatever the console uses).
 * - Test frames are ARGB [IntArray]s — exactly what Bitmap.createBitmap takes,
 *   so the caller can turn screenshots into Bitmaps without a conversion layer.
 * - No new permissions: plugins never touch disk, network, or sensors
 *   themselves. Export returns bytes; the caller decides how to share/save.
 *
 * Heavy steps ([build], [test]) are synchronous by contract — call them off
 * the main thread (Dispatchers.Default). Only [generate] is suspend because
 * it may call out to an LLM socket through the gateway.
 */

/** What the user wants made. Shared across every console plugin. */
data class GameBrief(
    val title: String,
    val description: String,
    val genreHint: String = "",
)

/** Generated source code for one game, before assembly. */
data class GameCode(
    /** Human label, e.g. "6502 assembly (ca65 subset)" or "C". */
    val language: String,
    /** File name -> source text. Never empty for a real result. */
    val sources: Map<String, String>,
    val notes: String = "",
) {
    /** Primary source text, or "" when [sources] is empty. */
    fun mainSource(): String =
        sources["game.asm"] ?: sources.values.firstOrNull() ?: ""
}

enum class IssueSeverity { ERROR, WARNING, INFO }

data class ValidationIssue(
    val ruleId: String,
    val message: String,
    val severity: IssueSeverity = IssueSeverity.ERROR,
)

data class ValidationReport(
    val passed: Boolean,
    val issues: List<ValidationIssue> = emptyList(),
    /** Honest provenance note, e.g. "structural check only — deep validator pending". */
    val provenance: String = "",
)

/** One captured frame from a headless test run. */
data class TestFrame(
    val index: Int,
    val width: Int,
    val height: Int,
    /** ARGB pixels, row-major, width*height entries. */
    val argb: IntArray,
)

data class TestReport(
    val passed: Boolean,
    val framesRendered: Int,
    /** Representative screenshots (first / middle / last), not every frame. */
    val frames: List<TestFrame> = emptyList(),
    val log: String = "",
)

data class ExportResult(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/** Readiness badge shown next to each console in the hub. */
enum class PluginStatus {
    /** Fully working pipeline behind this plugin. */
    READY,

    /** Honest stub: UI must say "not yet — NES first, this console is coming". */
    COMING_SOON,

    /** Partially working or planned with a narrower scope than a full console. */
    EXPERIMENTAL,
}

/**
 * One target console for the game maker.
 *
 * Pipeline: [generate] (brief -> source) -> [validate] -> [build] (source ->
 * ROM bytes) -> [test] (ROM -> frames/screenshots) -> [export] (ROM ->
 * shareable file).
 */
interface ConsolePlugin {
    /** Stable id, e.g. "nes", "genesis". */
    val id: String

    /** Display name, e.g. "Nintendo Entertainment System". */
    val displayName: String

    /** One-line pitch shown under the name. */
    val tagline: String

    /** Readiness badge. Anything but READY must carry an honest [statusNote]. */
    val status: PluginStatus

    /**
     * Honest label for non-READY plugins, shown verbatim in the UI.
     * Stubs use "not yet — NES first, this console is coming".
     */
    val statusNote: String

    /**
     * Generate source code for [brief]. May call an LLM socket through the
     * gateway; runs on whatever dispatcher the caller chooses.
     */
    suspend fun generate(brief: GameBrief): GameCode

    /** Validate generated source without building. Never throws for bad code. */
    fun validate(code: GameCode): ValidationReport

    /** Assemble [code] into ROM bytes. Throws with a clear message on failure. */
    fun build(code: GameCode): ByteArray

    /**
     * Run [rom] headless and capture screenshots.
     * Returns representative frames, not a full video dump.
     */
    fun test(rom: ByteArray): TestReport

    /** Wrap [rom] as a shareable file (bytes out; caller handles storage). */
    fun export(rom: ByteArray): ExportResult
}
