package com.omni.app.gamemaker.core

import com.omni.app.gamemaker.nes.LlmDraft
import com.omni.app.gamemaker.nes.NesGameMaker
import com.omni.app.gamemaker.nes.stripFences

/**
 * TRACK C — NES console plugin: the one full implementation.
 *
 * Every pipeline step DELEGATES to Track B's orchestrator,
 * [com.omni.app.gamemaker.nes.NesGameMaker] (companion helpers) and the
 * [LlmDraft] seam. This file does no NES work itself — it only adapts
 * Track B's results to the [ConsolePlugin] types.
 *
 * Integration reconciled 2026-09-24 (final pass): Track B landed a real
 * class-based API — `NesGameMaker` companion `validateSource` / `assemble` /
 * `runHeadless`, the [LlmDraft] fun interface, and top-level [stripFences].
 * The earlier ASSUMPTION block (object-style API) is superseded and removed.
 *
 * Honest layering: [generate] drafts source only (LLM socket when one is
 * injected, otherwise the offline deterministic template generator — always
 * labeled as such). [validate] / [build] / [test] run the real assembler,
 * validator, and headless PPU independently, so each step is checkable on
 * its own. The full draft→repair→render loop lives in Track B's
 * NesMakerScreen.
 */
class NesConsolePlugin(
    /**
     * Drafter used by [generate]. Null = offline deterministic template
     * generator (never labeled as AI). A socket-backed [LlmDraft] (e.g.
     * Track B's SocketLlmDraft) makes this a real AI pipeline step.
     */
    private val draft: LlmDraft? = null,
) : ConsolePlugin {

    override val id: String = "nes"
    override val displayName: String = "Nintendo Entertainment System"
    override val tagline: String = "Make real, validatable NES games — generate, assemble, and screenshot-test on-device."
    override val status: PluginStatus = PluginStatus.READY
    override val statusNote: String = ""

    override suspend fun generate(brief: GameBrief): GameCode {
        require(brief.title.isNotBlank()) { "title must not be blank" }
        val drafter = draft ?: NesGameMaker().templateDraftFor(brief.title)
        val asm = stripFences(drafter.draft(promptFor(brief)))
        require(asm.isNotBlank()) { "drafter returned empty output" }
        return GameCode(
            language = "6502 assembly (ca65 subset)",
            sources = mapOf("game.asm" to asm),
            notes = if (draft == null)
                "Drafted by the offline deterministic template generator (not AI). " +
                    "Not yet validated — call validate()."
            else
                "Drafted by the configured LLM socket. Not yet validated — call validate().",
        )
    }

    override fun validate(code: GameCode): ValidationReport {
        val source = code.mainSource()
        if (source.isBlank()) {
            return ValidationReport(
                passed = false,
                issues = listOf(
                    ValidationIssue(
                        ruleId = "EMPTY_SOURCE",
                        message = "No source code to validate.",
                    )
                ),
                provenance = "structural check in Track C (no pipeline call made)",
            )
        }
        val v = NesGameMaker.validateSource(source)
        return ValidationReport(
            passed = v.passed,
            issues = v.issues.map { raw ->
                val ruleId = raw.substringBefore(":").trim().ifBlank { "NES" }
                val message = raw.substringAfter(":", raw).trim()
                ValidationIssue(
                    ruleId = ruleId,
                    message = message,
                    severity = if (v.passed) IssueSeverity.INFO else IssueSeverity.ERROR,
                )
            },
            provenance = "omni.nes.validate.NesValidator via NesGameMaker.validateSource",
        )
    }

    override fun build(code: GameCode): ByteArray {
        val source = code.mainSource()
        require(source.isNotBlank()) { "cannot build: no source code" }
        return NesGameMaker.assemble(source)
    }

    override fun test(rom: ByteArray): TestReport {
        require(rom.isNotEmpty()) { "cannot test: empty ROM" }
        val run = NesGameMaker.runHeadless(rom, TEST_FRAME_COUNT)
        return TestReport(
            passed = run.frames.isNotEmpty(),
            framesRendered = run.frames.size,
            frames = run.frames.map { f ->
                TestFrame(index = f.index, width = f.width, height = f.height, argb = f.argb)
            },
            log = run.log,
        )
    }

    override fun export(rom: ByteArray): ExportResult {
        require(rom.isNotEmpty()) { "cannot export: empty ROM" }
        return ExportResult(
            fileName = "helios-nes-game.nes",
            mimeType = "application/x-nes-rom",
            bytes = rom,
        )
    }

    private fun promptFor(brief: GameBrief): String = buildString {
        appendLine("Make a small playable NES game: \"${brief.title}\".")
        if (brief.description.isNotBlank()) appendLine("Description: ${brief.description}")
        if (brief.genreHint.isNotBlank()) appendLine("Genre: ${brief.genreHint}")
        appendLine("Requirements: full game loop, NMI handler that updates graphics every frame,")
        appendLine("at least one player-controlled sprite on the D-pad, title-ish first frame.")
    }

    companion object {
        /** Frames captured per headless test run. */
        const val TEST_FRAME_COUNT: Int = 60
    }
}
