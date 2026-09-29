package com.omni.app.gamemaker.nes

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import omni.nes.asm.AssembleError
import omni.nes.asm.Assembler
import omni.nes.gen.ChrData
import omni.nes.gen.FailedAttempt
import omni.nes.gen.GameSpec
import omni.nes.gen.GenerationLoop
import omni.nes.gen.RuleBasedRepair
import omni.nes.rom.RomBuilder
import omni.nes.tools.Headless
import omni.nes.validate.NesValidator
import omni.nes.validate.ValidationReport
import omni.nes.validate.ValidatorConfig
import java.io.File

/**
 * Orchestrator for the on-device "make an NES game" pipeline.
 *
 * Uses the Phase-1 pipeline sources AS-IS (package `omni.nes.*`, shared with
 * the JVM test rigs — nothing here forks them):
 * - [omni.nes.asm.Assembler] builds the draft into PRG bytes.
 * - [RomBuilder] wraps PRG + 8KB CHR into a real iNES ROM.
 * - [NesValidator] gates every ROM: success is reported IFF zero FAILs.
 * - [RuleBasedRepair] applies surgical text patches for known rule failures;
 *   anything it cannot fix goes back to the [LlmDraft] as a re-prompt with
 *   the validator findings attached.
 * - [Headless] runs the final ROM's real CPU against the real 2C02 PPU and
 *   captures rendered frames (PPU-timed path, NMI driven by the vblank edge).
 *
 * Unlike [GenerationLoop.run] (blocking, no progress), this streams a
 * [MakerEvent] per stage so the UI can render a live timeline. The loop
 * semantics mirror GenerationLoop's guarantees: bounded attempts,
 * identical-output early stop, repairer exceptions become honest FAILED
 * runs, never crashes.
 */
class NesGameMaker(
    /** Hard cap on draft -> validate passes. The run always terminates. */
    val maxAttempts: Int = 5,
    /** PPU-rendered frames captured on success. */
    val frameCount: Int = 30,
    val validatorConfig: ValidatorConfig = ValidatorConfig(),
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(frameCount >= 1) { "frameCount must be >= 1" }
    }

    /**
     * Runs the full pipeline for [brief], emitting progress events:
     * DRAFTING -> ASSEMBLING -> VALIDATING -> REPAIRING(n/N) -> ... ->
     * RENDERING -> DONE or FAILED. Collect on any dispatcher; heavy work
     * (assembly, validation, headless run) runs on Dispatchers.Default.
     */
    fun makeGame(brief: String, draft: LlmDraft): Flow<MakerEvent> = flow {
        val spec = brief.toSpec()
        val attempts = mutableListOf<AttemptSummary>()

        var asm: String
        try {
            emit(MakerEvent.Stage(MakerStage.DRAFTING, "attempt 1 of $maxAttempts"))
            asm = draft.draft(draftPrompt(spec, previous = null)).let(::stripFences)
            if (asm.isBlank()) throw IllegalStateException("Draft came back empty.")
        } catch (e: Exception) {
            emit(MakerEvent.Failed("Drafting failed: ${e.message}"))
            return@flow
        }

        var repairAction: String? = "initial draft"
        var note: String? = null
        var finalRom: ByteArray? = null
        var finalReport: ValidationReport? = null
        var success = false

        for (n in 1..maxAttempts) {
            val sha = GenerationLoop.sha256(asm)
            emit(MakerEvent.Stage(MakerStage.ASSEMBLING, "attempt $n of $maxAttempts"))
            val prg: ByteArray
            var buildError: String? = null
            var rom: ByteArray? = null
            var report: ValidationReport? = null
            try {
                prg = withContext(Dispatchers.Default) { Assembler.assemble(asm).bytes }
                rom = withContext(Dispatchers.Default) {
                    RomBuilder.build(prg, ChrData.default())
                }
            } catch (e: AssembleError) {
                buildError = "assemble: ${e.message}"
            } catch (e: IllegalArgumentException) {
                buildError = "rom build: ${e.message}"
            } catch (e: Exception) {
                buildError = "unexpected: ${e.javaClass.simpleName}: ${e.message}"
            }

            if (rom != null) {
                emit(MakerEvent.Stage(MakerStage.VALIDATING, "attempt $n of $maxAttempts"))
                report = withContext(Dispatchers.Default) {
                    NesValidator(validatorConfig).validate(rom)
                }
            }

            val passed = report?.passed == true
            val fired = report?.fails()?.map { it.ruleId }?.sorted() ?: emptyList()
            attempts.add(
                AttemptSummary(
                    attempt = n,
                    asmSha256 = sha,
                    assembled = buildError == null,
                    buildError = buildError,
                    passed = passed,
                    firedRules = fired,
                    repairAction = repairAction,
                ),
            )
            emit(MakerEvent.AttemptReport(attempts.last()))

            if (passed) {
                success = true
                finalRom = rom
                finalReport = report
                break
            }
            if (n == maxAttempts) {
                note = "Attempt budget exhausted ($maxAttempts attempts, all RED)."
                break
            }

            // --- repair path: surgical first, LLM re-prompt second ---
            val failed = FailedAttempt(n, asm, report, buildError)
            val next: String?
            val action: String
            val surgical = runCatching { RuleBasedRepair().repair(failed) }.getOrNull()
            if (surgical?.asm != null) {
                next = surgical.asm
                action = "[RuleBasedRepair] ${surgical.action}"
            } else {
                val reason = surgical?.action ?: "no repairer available"
                emit(
                    MakerEvent.Stage(
                        MakerStage.REPAIRING,
                        "attempt $n/$maxAttempts: surgical repair skipped ($reason) — re-prompting drafter",
                    ),
                )
                try {
                    next = draft.draft(draftPrompt(spec, failed)).let(::stripFences)
                    action = "re-prompted drafter with failure context (${fired.joinToString(",")})"
                } catch (e: Exception) {
                    note = "Drafter threw on re-prompt: ${e.message}; stopping."
                    break
                }
            }

            emit(MakerEvent.Stage(MakerStage.REPAIRING, "attempt $n/$maxAttempts: $action"))
            if (next.isBlank()) {
                note = "Repair produced empty output ($action); stopping."
                break
            }
            if (GenerationLoop.sha256(next) == sha) {
                note = "Repair '${action.take(80)}' produced output identical to attempt $n; stopping."
                break
            }
            asm = next
            repairAction = action
            emit(
                MakerEvent.Stage(
                    MakerStage.DRAFTING,
                    "attempt ${n + 1} of $maxAttempts (after repair)",
                ),
            )
        }

        if (!success || finalRom == null || finalReport == null) {
            emit(
                MakerEvent.Failed(
                    reason = note ?: "Pipeline ended without a GREEN ROM.",
                    attempts = attempts.toList(),
                ),
            )
            return@flow
        }

        // --- render: real CPU + real PPU, offscreen, frames as Bitmaps ---
        emit(MakerEvent.Stage(MakerStage.RENDERING, "running $frameCount frames headless"))
        val romBytes = finalRom
        val run = withContext(Dispatchers.Default) {
            Headless.run(romBytes, frameCount, keepFrames = true)
        }
        val bitmaps = withContext(Dispatchers.Default) {
            run.frames.map { framebufferToBitmap(it) }
        }
        emit(
            MakerEvent.Done(
                MakerResult(
                    rom = romBytes,
                    frames = bitmaps,
                    frameHashes = run.hashes,
                    report = finalReport,
                    attempts = attempts.toList(),
                    cpuCycles = run.cpuCycles,
                    renderNote = run.stoppedEarly,
                ),
            ),
        )
    }

    /**
     * Convenience overload for other tracks: collects the event flow on
     * [scope] and forwards each event to [callbacks]. Returns the Job so the
     * caller can cancel a run in flight.
     */
    fun makeGame(
        brief: String,
        draft: LlmDraft,
        scope: CoroutineScope,
        callbacks: MakerCallbacks,
    ): Job = scope.launch {
        makeGame(brief, draft).collect { callbacks.onEvent(it) }
    }

    /** Deterministic offline fallback draft for [brief] (template-generated). */
    fun templateDraftFor(brief: String): LlmDraft = TemplateDraft(brief.toSpec())

    /**
     * Writes [rom] to app-private storage (`filesDir/nes-roms/<slug>.nes`).
     * No new permissions needed. Note: there is no FileProvider in the
     * manifest, so the file is app-private — another track would add a
     * provider (or MediaStore export) to make it shareable.
     */
    fun exportRom(context: Context, rom: ByteArray, title: String): File {
        val dir = File(context.filesDir, "nes-roms").also { it.mkdirs() }
        val file = File(dir, "${GenerationLoop.slugify(title)}.nes")
        file.writeBytes(rom)
        return file
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private fun String.toSpec(): GameSpec = GameSpec(
        title = replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "UNTITLED" },
    )

    private fun draftPrompt(spec: GameSpec, previous: FailedAttempt?): String = buildString {
        appendLine("Make a small playable NES game: \"${spec.title}\".")
        appendLine("Requirements: full game loop, NMI handler that updates graphics every frame,")
        appendLine("at least one player-controlled sprite on the D-pad, title-ish first frame.")
        val failed = previous
        if (failed != null) {
            appendLine()
            appendLine("Your previous draft FAILED validation. Fix it. Fired rules:")
            val rules = failed.report?.fails()
                ?.joinToString("\n") { "- ${it.ruleId}: ${it.message}" }
                ?: "- build failed before validation: ${failed.buildError}"
            appendLine(rules)
            appendLine()
            appendLine("Previous assembly (repair it, keep what worked):")
            appendLine(failed.asm.take(12000))
            if (failed.asm.length > 12000) appendLine("; ... (truncated)")
        }
    }


    companion object {
        /**
         * 256x240 PPU framebuffer -> ARGB_8888 Bitmap. PPU pixels are
         * 0xRRGGBB (alpha byte zero), so the opaque alpha is OR'd in.
         */
        fun framebufferToBitmap(pixels: IntArray): Bitmap {
            require(pixels.size == 256 * 240) {
                "PPU framebuffer must be 256x240, got ${pixels.size} pixels"
            }
            val argb = IntArray(pixels.size) { i -> pixels[i] or 0xFF000000.toInt() }
            return Bitmap.createBitmap(argb, 256, 240, Bitmap.Config.ARGB_8888)
        }

        /**
         * Standalone validation of ca65-subset 6502 source: assemble, wrap in
         * an iNES ROM, run the real NesValidator. Never throws for bad code —
         * assembly failures come back as passed=false with an ASSEMBLE issue.
         * (Used by the console-plugin layer; the full makeGame loop uses the
         * richer event stream instead.)
         */
        fun validateSource(source: String): NesValidation {
            val rom = try {
                assemble(source)
            } catch (e: Exception) {
                return NesValidation(
                    passed = false,
                    issues = listOf("ASSEMBLE: ${e.javaClass.simpleName}: ${e.message}"),
                )
            }
            val report = NesValidator(ValidatorConfig()).validate(rom)
            return NesValidation(
                passed = report.passed,
                issues = report.findings.map { "${it.ruleId}: ${it.message}" },
            )
        }

        /**
         * Assemble ca65-subset 6502 source into real iNES ROM bytes (mapper 0,
         * default CHR bank). Throws AssembleError / IllegalArgumentException
         * with a clear message on failure.
         */
        fun assemble(source: String): ByteArray =
            RomBuilder.build(Assembler.assemble(source).bytes, ChrData.default())

        /**
         * Run [rom] headless through the real CPU + 2C02 PPU and capture
         * [frameCount] frames as ARGB screenshots.
         */
        fun runHeadless(rom: ByteArray, frameCount: Int): NesTestRun {
            require(frameCount >= 1) { "frameCount must be >= 1" }
            val run = Headless.run(rom, frameCount, keepFrames = true)
            val frames = run.frames.mapIndexed { i, pixels ->
                NesFrame(
                    index = i,
                    width = 256,
                    height = 240,
                    argb = IntArray(pixels.size) { j -> pixels[j] or 0xFF000000.toInt() },
                )
            }
            val log = buildString {
                append("headless run: ${run.hashes.size} frame(s), ${run.cpuCycles} CPU cycles")
                run.stoppedEarly?.let { append("; stopped early: $it") }
            }
            return NesTestRun(frames = frames, log = log)
        }
    }
}

/** Pipeline stages in the order they actually run. */
enum class MakerStage { DRAFTING, ASSEMBLING, VALIDATING, REPAIRING, RENDERING }

/** One validator pass, for the report view. */
data class AttemptSummary(
    val attempt: Int,
    val asmSha256: String,
    val assembled: Boolean,
    val buildError: String?,
    val passed: Boolean,
    val firedRules: List<String>,
    val repairAction: String?,
)

/** Progress events streamed by [NesGameMaker.makeGame]. */
sealed interface MakerEvent {
    /** A stage became active (detail carries attempt counts / repair action). */
    data class Stage(val stage: MakerStage, val detail: String? = null) : MakerEvent
    /** One assemble+validate pass finished, with its full summary. */
    data class AttemptReport(val summary: AttemptSummary) : MakerEvent
    /** GREEN ROM + real PPU frames. */
    data class Done(val result: MakerResult) : MakerEvent
    /** Honest RED: reason + every attempt's summary. */
    data class Failed(val reason: String, val attempts: List<AttemptSummary> = emptyList()) : MakerEvent
}

/** The successful run's deliverables. `success` is implied: this object only
 *  exists when the final ROM passed the validator with zero FAILs. */
data class MakerResult(
    val rom: ByteArray,
    val frames: List<Bitmap>,
    val frameHashes: List<Long>,
    val report: ValidationReport,
    val attempts: List<AttemptSummary>,
    val cpuCycles: Long,
    /** Non-null when the headless run stopped before all frames (informational). */
    val renderNote: String?,
)

/** Callback-style consumption for tracks that don't want a Flow. */
interface MakerCallbacks {
    fun onEvent(event: MakerEvent)
}

/** Standalone source validation result. Issues are "RULE_ID: message". Never throws. */
data class NesValidation(val passed: Boolean, val issues: List<String>)

/** One headless-test screenshot: ARGB pixels, row-major, width*height entries. */
data class NesFrame(val index: Int, val width: Int, val height: Int, val argb: IntArray)

/** Headless test run: representative screenshots + a human-readable log line. */
data class NesTestRun(val frames: List<NesFrame>, val log: String)

/** LLMs love wrapping code in ``` fences; the assembler does not. */
fun stripFences(text: String): String {
    val lines = text.lines().filterNot {
        val t = it.trim()
        t.startsWith("```")
    }
    return lines.joinToString("\n").trim()
}
