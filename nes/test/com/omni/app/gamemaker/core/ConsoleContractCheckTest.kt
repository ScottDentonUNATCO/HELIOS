package com.omni.app.gamemaker.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plug-in contract check: pass/fail behavior against the honest stubs and
 * adversarial test doubles. (Runs on the build machine; the real NES plugin
 * is exercised on-device by the one-tap flow itself.)
 */
class ConsoleContractCheckTest {

    /** A well-behaved READY plugin: must pass every check. */
    private open class GoodPlugin : ConsolePlugin {
        override val id = "good"
        override val displayName = "Good Plugin"
        override val tagline = "test double"
        override val status = PluginStatus.READY
        override val statusNote = ""
        override suspend fun generate(brief: GameBrief): GameCode {
            require(brief.title.isNotBlank()) { "title must not be blank" }
            return GameCode("test", mapOf("game.asm" to "; ok"))
        }
        override fun validate(code: GameCode): ValidationReport =
            if (code.mainSource().isBlank())
                ValidationReport(false, listOf(ValidationIssue("EMPTY", "no source")))
            else ValidationReport(true)
        override fun build(code: GameCode): ByteArray {
            require(code.mainSource().isNotBlank()) { "cannot build: no source code" }
            return byteArrayOf(1, 2, 3)
        }
        override fun test(rom: ByteArray): TestReport {
            require(rom.isNotEmpty()) { "cannot test: empty ROM" }
            return TestReport(true, 0)
        }
        override fun export(rom: ByteArray): ExportResult {
            require(rom.isNotEmpty()) { "cannot export: empty ROM" }
            return ExportResult("good.bin", "application/octet-stream", rom)
        }
    }

    /** validate() throws — the one unforgivable contract violation. */
    private class ThrowingValidatePlugin : GoodPlugin() {
        override fun validate(code: GameCode): ValidationReport =
            throw RuntimeException("boom")
    }

    /** build() silently accepts garbage — must be caught. */
    private class SilentAcceptPlugin : GoodPlugin() {
        override fun build(code: GameCode): ByteArray = byteArrayOf(9)
    }

    @Test fun `ready well-behaved plugin passes every check`() {
        val result = checkConsoleContract(GoodPlugin())
        assertTrue(
            "expected all pass, failures: ${result.failureSummary()}",
            result.passed,
        )
        assertTrue(result.items.size >= 6)
    }

    @Test fun `coming-soon stub fails with its honest label quoted`() {
        val result = checkConsoleContract(GenesisPlugin())
        assertFalse(result.passed)
        val statusItem = result.items.first { it.name == "status is READY" }
        assertFalse(statusItem.passed)
        assertTrue(
            "honest label quoted: ${statusItem.detail}",
            statusItem.detail.contains("not yet — NES first, this console is coming"),
        )
    }

    @Test fun `validate that throws fails the contract`() {
        val result = checkConsoleContract(ThrowingValidatePlugin())
        assertFalse(result.passed)
        val item = result.items.first { it.name == "validate() never throws on garbage" }
        assertFalse(item.passed)
        assertTrue(item.detail.contains("threw"))
    }

    @Test fun `build that silently accepts garbage fails the contract`() {
        val result = checkConsoleContract(SilentAcceptPlugin())
        assertFalse(result.passed)
        val item = result.items.first { it.name == "build() refuses empty source with a clear message" }
        assertFalse(item.passed)
        assertTrue(item.detail.contains("silently"))
    }
}
