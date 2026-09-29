package omni.nes.gen

import omni.nes.asm.Assembler
import omni.nes.rom.RomBuilder
import omni.nes.validate.NesValidator
import omni.nes.validate.Severity
import omni.nes.validate.ValidatorConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for the validator-gated generation loop: the mock generator's games
 * go GREEN, slop is rejected then repaired (surgically or via re-prompt),
 * budgets die honestly, and every new toggle genuinely toggles.
 */
class GenerationLoopTest {

    private fun tmpDir(): File = Files.createTempDirectory("gen-loop-test").toFile()

    private fun testLoop(
        dir: File,
        generator: Generator,
        repairer: Repairer = RuleBasedRepair(),
        maxAttempts: Int = 5,
        enableRepair: Boolean = true,
        writeLog: Boolean = true,
        validatorConfig: ValidatorConfig = ValidatorConfig(),
    ) = GenerationLoop(
        generator,
        repairer,
        LoopConfig(
            maxAttempts = maxAttempts,
            enableRepair = enableRepair,
            writeAttemptLog = writeLog,
            logDir = dir,
            validatorConfig = validatorConfig,
        ),
    )

    private fun dpadSpec(title: String) = GameSpec(title, "arcade", "dpad", listOf("move", "dpad"))

    /** Independently re-validates a ROM: the loop's GREEN claim must hold. */
    private fun assertGreen(rom: ByteArray) {
        val report = NesValidator().validate(rom)
        assertTrue(
            "loop claimed GREEN but re-validation failed: " +
                report.findings.joinToString("; ") { "${it.severity} ${it.ruleId}" },
            report.passed,
        )
    }

    // ------------------------------------------------------------------
    // 1. the mock generator's real games go GREEN
    // ------------------------------------------------------------------

    @Test fun mockDpadGameGoesGreen() {
        val dir = tmpDir()
        val spec = dpadSpec("dpad-move")
        val result = testLoop(dir, MockGenerator()).run(spec, "dpad")
        assertTrue("expected SUCCESS, note=${result.note}", result.success)
        assertEquals(1, result.attempts.size)
        assertNotNull(result.finalRom)
        assertNotNull(result.finalAsm)
        assertGreen(result.finalRom!!)
        // PRG is a real NROM-128 image
        val prg = Assembler.assemble(result.finalAsm!!).bytes
        assertEquals(16384, prg.size)
        // attempt log exists and records the green run
        val log = File(dir, "dpad-attempts.log")
        assertTrue(log.exists())
        val text = log.readText()
        assertTrue(text.contains("result: SUCCESS"))
        assertTrue(text.contains("fired_rules: none"))
    }

    @Test fun mockScoreGameGoesGreen() {
        val dir = tmpDir()
        val spec = GameSpec("score-counter", "arcade", "dpad+a", listOf("score", "move"))
        val result = testLoop(dir, MockGenerator()).run(spec, "score")
        assertTrue("expected SUCCESS, note=${result.note}", result.success)
        assertEquals(1, result.attempts.size)
        assertGreen(result.finalRom!!)
        // the score game really has digit tiles referenced (16 + digit)
        assertTrue(result.finalAsm!!.contains("#\$10"))
    }

    @Test fun mockBounceGameGoesGreen() {
        val dir = tmpDir()
        val spec = GameSpec("bounce-ball", "arcade", "a", listOf("bounce", "ball"))
        val result = testLoop(dir, MockGenerator()).run(spec, "bounce")
        assertTrue("expected SUCCESS, note=${result.note}", result.success)
        assertEquals(1, result.attempts.size)
        assertGreen(result.finalRom!!)
    }

    @Test fun unknownSpecFallsBackToAGreenGame() {
        val dir = tmpDir()
        val spec = GameSpec("mystery", mechanics = listOf("quantum chess", "???"))
        val result = testLoop(dir, MockGenerator()).run(spec, "mystery")
        assertTrue(result.success)
        assertGreen(result.finalRom!!)
    }

    // ------------------------------------------------------------------
    // 2. slop is rejected, then surgically repaired
    // ------------------------------------------------------------------

    private fun slopRepairedSurgically(variant: SlopVariant, expectRules: Set<String>, slug: String) {
        val dir = tmpDir()
        val slop = SlopGenerator(variant)
        val spec = GameSpec("slop-$slug", mechanics = listOf("move"))
        // sanity: the raw slop really is RED with the expected rule IDs
        val rawPrg = Assembler.assemble(slop.generate(spec)).bytes
        val rawReport = NesValidator().validate(RomBuilder.build(rawPrg, ChrData.default()))
        assertFalse("slop variant $variant should be RED", rawReport.passed)
        val rawFails = rawReport.fails().map { it.ruleId }.toSet()
        assertTrue(
            "expected $expectRules, got $rawFails",
            rawFails.containsAll(expectRules),
        )

        val result = testLoop(dir, slop, RuleBasedRepair()).run(spec, "slop-$slug")
        assertTrue("expected repair to reach GREEN, note=${result.note}", result.success)
        assertEquals(2, result.attempts.size)
        assertEquals(expectRules.intersect(rawFails), result.attempts[0].firedRules.toSet().intersect(expectRules))
        assertTrue(result.attempts[1].passed)
        assertGreen(result.finalRom!!)
        val log = File(dir, "slop-$slug-attempts.log").readText()
        assertTrue(log.contains("result: SUCCESS"))
        for (rule in expectRules) assertTrue("log must show $rule", log.contains(rule))
    }

    @Test fun slopZeroVectorsRepaired() =
        slopRepairedSurgically(SlopVariant.ZERO_VECTORS, setOf("VEC_NMI_IN_PRG", "VEC_RESET_IN_PRG"), "zero-vec")

    @Test fun slopBadVectorsRepaired() =
        slopRepairedSurgically(SlopVariant.BAD_VECTORS, setOf("VEC_NMI_IN_PRG", "VEC_RESET_IN_PRG"), "bad-vec")

    @Test fun slopNmiNeverEnabledRepaired() =
        slopRepairedSurgically(SlopVariant.NMI_NEVER_ENABLED, setOf("NMI_ENABLED"), "nmi-off")

    @Test fun slopMissingCliRepaired() =
        slopRepairedSurgically(SlopVariant.MISSING_CLI, setOf("NMI_ENABLED"), "no-cli")

    // ------------------------------------------------------------------
    // 3. slop with no surgical fix: re-prompt repairs it; surgery alone fails honestly
    // ------------------------------------------------------------------

    private fun slopReprompted(variant: SlopVariant, expectRules: Set<String>, slug: String) {
        val dir = tmpDir()
        val slop = SlopGenerator(variant)
        val spec = GameSpec("slop-$slug", mechanics = listOf("move"))

        // surgery alone: honest failure, no false green
        val surgicalOnly = testLoop(tmpDir(), slop, RuleBasedRepair(), maxAttempts = 3).run(spec, "slop-$slug-solo")
        assertFalse(surgicalOnly.success)
        assertTrue(surgicalOnly.attempts.all { !it.passed })

        // with the re-prompt hook: RED then GREEN
        val dir2 = tmpDir()
        val composite = CompositeRepair(RuleBasedRepair(), RepromptRepair(spec, slop))
        val result = testLoop(dir2, slop, composite).run(spec, "slop-$slug")
        assertTrue("expected re-prompt to reach GREEN, note=${result.note}", result.success)
        assertEquals(2, result.attempts.size)
        assertTrue(
            result.attempts[0].firedRules.toSet().containsAll(expectRules),
        )
        // the repair action is recorded on the attempt it PRODUCED (attempt 2)
        assertTrue(result.attempts[1].repairAction!!.contains("RepromptRepair"))
        assertTrue(result.attempts[1].passed)
        assertGreen(result.finalRom!!)
    }

    @Test fun slopIllegalOpcodeReprompted() =
        slopReprompted(SlopVariant.ILLEGAL_OPCODES, setOf("ILLEGAL_OPCODE"), "illegal")

    @Test fun slopSpriteOverflowReprompted() =
        slopReprompted(SlopVariant.SPRITE_OVERFLOW, setOf("SPRITE_OVERFLOW"), "overflow")

    @Test fun slopPaletteViolationReprompted() =
        slopReprompted(SlopVariant.PALETTE_VIOLATION, setOf("PALETTE_TIMING"), "palette")

    // ------------------------------------------------------------------
    // 4. repair genuinely changes the verdict (direct, no loop)
    // ------------------------------------------------------------------

    @Test fun ruleBasedRepairChangesVerdict() {
        val repairer = RuleBasedRepair()
        val spec = GameSpec("direct", mechanics = listOf("move"))
        for (variant in listOf(
            SlopVariant.ZERO_VECTORS, SlopVariant.BAD_VECTORS,
            SlopVariant.NMI_NEVER_ENABLED, SlopVariant.MISSING_CLI,
        )) {
            val slop = SlopGenerator(variant)
            val asm = slop.generate(spec)
            val before = NesValidator().validate(RomBuilder.build(Assembler.assemble(asm).bytes, ChrData.default()))
            assertFalse("$variant should start RED", before.passed)
            val failed = FailedAttempt(1, asm, before, null)
            val outcome = repairer.repair(failed)
            assertNotNull("$variant: repair gave up", outcome.asm)
            val after = NesValidator().validate(
                RomBuilder.build(Assembler.assemble(outcome.asm!!).bytes, ChrData.default()),
            )
            assertTrue(
                "$variant: still RED after repair: " +
                    after.findings.joinToString("; ") { "${it.severity} ${it.ruleId}" },
                after.passed,
            )
        }
    }

    // ------------------------------------------------------------------
    // 5. budget exhaustion is honest
    // ------------------------------------------------------------------

    /** A generator that never listens: new slop every reprompt, forever RED. */
    class StubbornSlop(private val variant: SlopVariant) : RepromptableGenerator, ChrProvider {
        private val slop = SlopGenerator(variant)
        private var n = 0
        override fun generate(spec: GameSpec): String = slop.generate(spec)
        override fun reprompt(spec: GameSpec, failure: FailedAttempt): String {
            n++
            // different bytes each time (new comment) so the identical-output
            // early stop does not trigger; still the same RED program.
            return slop.generate(spec) + "\n; stubborn reprompt #$n\n"
        }
        override fun chr(): ByteArray = ChrData.default()
    }

    @Test fun budgetExhaustionIsHonest() {
        val dir = tmpDir()
        val stubborn = StubbornSlop(SlopVariant.ILLEGAL_OPCODES)
        val spec = GameSpec("stubborn", mechanics = listOf("move"))
        val composite = CompositeRepair(RuleBasedRepair(), RepromptRepair(spec, stubborn))
        val result = testLoop(dir, stubborn, composite, maxAttempts = 4).run(spec, "stubborn")
        assertFalse("must not claim success", result.success)
        assertEquals(4, result.attempts.size)
        assertTrue(result.attempts.all { !it.passed })
        assertTrue(result.attempts.all { "ILLEGAL_OPCODE" in it.firedRules })
        assertNull(result.finalRom)
        assertTrue(result.note!!.contains("budget exhausted"))
        val log = File(dir, "stubborn-attempts.log").readText()
        assertTrue(log.contains("result: FAILURE"))
        assertTrue(log.contains("budget exhausted"))
    }

    @Test fun identicalOutputStopsEarlyInsteadOfBurningBudget() {
        val dir = tmpDir()
        // reprompt returns byte-identical slop: loop must stop, not spin.
        val identical = object : RepromptableGenerator, ChrProvider {
            val slop = SlopGenerator(SlopVariant.ILLEGAL_OPCODES)
            override fun generate(spec: GameSpec) = slop.generate(spec)
            override fun reprompt(spec: GameSpec, f: FailedAttempt) = slop.generate(spec)
            override fun chr() = ChrData.default()
        }
        val spec = GameSpec("identical", mechanics = listOf("move"))
        val composite = CompositeRepair(RuleBasedRepair(), RepromptRepair(spec, identical))
        val result = testLoop(dir, identical, composite, maxAttempts = 5).run(spec, "identical")
        assertFalse(result.success)
        // the identical re-prompt is detected BEFORE a second validation,
        // so only the one real attempt is recorded; the note names the
        // repair action that produced the duplicate output
        assertEquals(1, result.attempts.size)
        assertTrue(result.note!!.contains("identical"))
        assertTrue(result.note!!.contains("RepromptRepair"))
    }

    // ------------------------------------------------------------------
    // 6. toggles: off genuinely means off
    // ------------------------------------------------------------------

    @Test fun repairDisabledMeansDisabled() {
        val dir = tmpDir()
        val slop = SlopGenerator(SlopVariant.NMI_NEVER_ENABLED)
        val spec = GameSpec("no-repair", mechanics = listOf("move"))
        // deterministic generator + no repair: every attempt re-runs the raw slop.
        val gen = object : Generator, ChrProvider {
            override fun generate(spec: GameSpec) = slop.generate(spec)
            override fun chr() = ChrData.default()
        }
        val result = testLoop(dir, gen, RuleBasedRepair(), maxAttempts = 3, enableRepair = false)
            .run(spec, "no-repair")
        assertFalse(result.success)
        // no repair actions anywhere; the raw slop is all the loop ever judged
        assertTrue(result.attempts.all { it.repairAction == "initial generation" })
        assertTrue(result.attempts.none { (it.repairAction ?: "").contains("RuleBased") })
        assertNull(result.finalRom)
        // the re-generated attempt is byte-identical slop -> the loop stops
        // before wasting a validation on it, instead of burning the budget
        assertEquals(1, result.attempts.size)
        assertTrue(result.note!!.contains("identical"))
    }

    @Test fun logDisabledMeansNoFile() {
        val dir = tmpDir()
        val spec = dpadSpec("no-log")
        val before = dir.listFiles()!!.size
        val result = testLoop(dir, MockGenerator(), writeLog = false).run(spec, "no-log")
        assertTrue(result.success)
        assertEquals(before, dir.listFiles()!!.size)
    }

    @Test fun validatorConfigSeamIsHonored() {        // requireNmi=false: the NMI-less slop is GREEN even with repair off,
        // proving LoopConfig.validatorConfig reaches the real validator.
        val dir = tmpDir()
        val slop = SlopGenerator(SlopVariant.NMI_NEVER_ENABLED)
        val spec = GameSpec("lax-nmi", mechanics = listOf("move"))
        val lax = ValidatorConfig(requireNmi = false)
        val result = testLoop(
            dir, slop, RuleBasedRepair(),
            enableRepair = false, validatorConfig = lax,
        ).run(spec, "lax-nmi")
        assertTrue("lax config should pass NMI-less ROM, note=${result.note}", result.success)
        // and the strict default still rejects the same bytes
        val strict = testLoop(tmpDir(), slop, RuleBasedRepair(), enableRepair = false, maxAttempts = 1)
            .run(spec, "strict-nmi")
        assertFalse(strict.success)
        assertTrue(strict.attempts[0].firedRules.contains("NMI_ENABLED"))
    }

    @Test fun degenerateBudgetFailsFastAndHonestly() {
        try {
            LoopConfig(maxAttempts = 0)
            fail("maxAttempts=0 must be rejected, not silently validate nothing")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxAttempts"))
        }
        try {
            LoopConfig(maxAttempts = -3)
            fail("negative maxAttempts must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ------------------------------------------------------------------
    // 7. attempt log content
    // ------------------------------------------------------------------

    @Test fun attemptLogRecordsEveryField() {
        val dir = tmpDir()
        val slop = SlopGenerator(SlopVariant.MISSING_CLI)
        val spec = GameSpec("fields", mechanics = listOf("move"))
        val result = testLoop(dir, slop, RuleBasedRepair()).run(spec, "fields")
        assertTrue(result.success)
        val text = File(dir, "fields-attempts.log").readText()
        for (field in listOf(
            "spec_title:", "generator:", "repairer:", "config:",
            "attempt: 1", "attempt: 2",
            "asm_sha256:", "assembled: true", "build_error: none",
            "passed: false", "passed: true",
            "fired_rules: NMI_ENABLED", "repair_action:",
            "result: SUCCESS",
        )) {
            assertTrue("log missing: $field", text.contains(field))
        }
        // the two hashes differ (repair changed the bytes)
        val hashes = Regex("asm_sha256: ([0-9a-f]+)").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(2, hashes.toSet().size)
        assertTrue(hashes.all { it.length == 64 })
    }

    @Test fun failingRunLogShowsRedAttemptsAndHonestNote() {
        val dir = tmpDir()
        val stubborn = StubbornSlop(SlopVariant.SPRITE_OVERFLOW)
        val spec = GameSpec("red-log", mechanics = listOf("move"))
        val composite = CompositeRepair(RuleBasedRepair(), RepromptRepair(spec, stubborn))
        testLoop(dir, stubborn, composite, maxAttempts = 2).run(spec, "red-log")
        val text = File(dir, "red-log-attempts.log").readText()
        assertTrue(text.contains("result: FAILURE"))
        assertTrue(text.contains("fired_rules: SPRITE_OVERFLOW"))
        assertTrue(text.contains("note: attempt budget exhausted"))
    }
}
