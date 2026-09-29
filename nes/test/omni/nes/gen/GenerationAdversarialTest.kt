package omni.nes.gen

import omni.nes.asm.Assembler
import omni.nes.rom.RomBuilder
import omni.nes.validate.NesValidator
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Hostile tests for the generation loop: generators that fight repair, code
 * that assembles but fails boot, spec injection, silent NMI-enable drops,
 * throwing generators, and empty output. The loop must never crash, never
 * claim a false GREEN, and always terminate with an honest log.
 */
class GenerationAdversarialTest {

    private fun tmpDir(): File = Files.createTempDirectory("gen-adv-test").toFile()

    private fun testLoop(
        dir: File,
        generator: Generator,
        repairer: Repairer,
        maxAttempts: Int = 5,
    ) = GenerationLoop(
        generator, repairer,
        LoopConfig(maxAttempts = maxAttempts, logDir = dir),
    )

    // ------------------------------------------------------------------
    // 1. a generator that fights repair: a NEW unfixable breakage every
    //    reprompt, until the budget dies. The correct outcome is an honest
    //    FAILURE with all five breakages in the log - never a hang, never
    //    a false green.
    // ------------------------------------------------------------------

    class EvilGenerator : RepromptableGenerator, ChrProvider {
        private val mock = MockGenerator()
        private val cleanSpec = GameSpec("evil", mechanics = listOf("move"))
        private var n = 0

        private fun clean(): String = mock.generate(cleanSpec)

        /** 0=illegal 1=rom-write 2=sprite-overflow 3=palette 4=stack-fault */
        fun breakage(i: Int): String = when (i % 5) {
            0 -> clean().replace(
                "RESET:\n",
                "RESET:\n    .byte \$0B               ; evil: illegal opcode\n",
            )
            1 -> clean().replace(
                "RESET:\n",
                "RESET:\n    STA \$C000           ; evil: write to ROM\n",
            )
            2 -> SlopGenerator(SlopVariant.SPRITE_OVERFLOW).generate(cleanSpec)
            3 -> SlopGenerator(SlopVariant.PALETTE_VIOLATION).generate(cleanSpec)
            else -> clean().replace(
                "RESET:\n",
                "RESET:\n    PLA                 ; evil: pop the empty stack\n",
            )
        }

        override fun generate(spec: GameSpec): String = breakage(0)
        override fun reprompt(spec: GameSpec, failure: FailedAttempt): String {
            n++
            return breakage(n)
        }
        override fun chr(): ByteArray = ChrData.default()
    }

    @Test fun generatorThatFightsRepairDiesOnBudgetHonestly() {
        val dir = tmpDir()
        val evil = EvilGenerator()
        val spec = GameSpec("evil", mechanics = listOf("move"))
        val composite = CompositeRepair(RuleBasedRepair(), RepromptRepair(spec, evil))
        val result = testLoop(dir, evil, composite, maxAttempts = 5).run(spec, "evil")

        assertFalse("must never go green on fighting breakages", result.success)
        assertEquals(5, result.attempts.size) // all 5 budget attempts consumed
        assertTrue(result.attempts.all { !it.passed })
        assertNull(result.finalRom)
        assertTrue(result.note!!.contains("budget exhausted"))

        // five DISTINCT breakages, one per attempt, in order
        val fired = result.attempts.map { it.firedRules.toSet() }
        assertTrue(fired[0].contains("ILLEGAL_OPCODE"))
        assertTrue(fired[1].contains("BOOT_BUS_FAULT"))
        assertTrue(fired[2].contains("SPRITE_OVERFLOW"))
        assertTrue(fired[3].contains("PALETTE_TIMING"))
        assertTrue(fired[4].contains("BOOT_STACK_FAULT"))
        assertEquals(5, fired.toSet().size)

        // the log is the evidence
        val log = File(dir, "evil-attempts.log").readText()
        for (rule in listOf("ILLEGAL_OPCODE", "BOOT_BUS_FAULT", "SPRITE_OVERFLOW", "PALETTE_TIMING", "BOOT_STACK_FAULT")) {
            assertTrue("log must show the fight ($rule)", log.contains(rule))
        }
        assertTrue(log.contains("result: FAILURE"))
    }

    // ------------------------------------------------------------------
    // 2. assembles fine, dies at boot (ROM write). Must be a RED attempt
    //    with the right rule, not a crash - then reprompt fixes it.
    // ------------------------------------------------------------------

    @Test fun assemblesButFailsBoot() {
        val dir = tmpDir()
        val evil = EvilGenerator()
        val spec = GameSpec("bootfault", mechanics = listOf("move"))
        val romWriteOnly = object : RepromptableGenerator, ChrProvider {
            override fun generate(spec: GameSpec) = evil.breakage(1)
            override fun reprompt(spec: GameSpec, f: FailedAttempt) = MockGenerator().generate(spec)
            override fun chr() = ChrData.default()
        }
        val composite = CompositeRepair(RuleBasedRepair(), RepromptRepair(spec, romWriteOnly))
        val result = testLoop(dir, romWriteOnly, composite).run(spec, "bootfault")

        assertTrue(result.attempts[0].assembled) // it DID assemble
        assertFalse(result.attempts[0].passed)
        assertTrue(result.attempts[0].firedRules.contains("BOOT_BUS_FAULT"))
        assertTrue("reprompt should fix it, note=${result.note}", result.success)
        assertEquals(2, result.attempts.size)
    }

    // ------------------------------------------------------------------
    // 3. spec injection: hostile titles/mechanics must not break the
    //    generator, the assembler, or the loop.
    // ------------------------------------------------------------------

    @Test fun specInjectionIsNeutralized() {
        val mock = MockGenerator()
        val hostileTitles = listOf(
            "Evil\nLDA #\$FF\nSTA \$2000\ninjected:",
            "A".repeat(5000),
            "",
            "'; DROP TABLE roms; --",
            "../../etc/passwd",
            "; .org \$FFFA\n.word \$0000,\$0000,\$0000",
        )
        for (title in hostileTitles) {
            val spec = GameSpec(title, mechanics = listOf("move"))
            val asm = mock.generate(spec)
            // must still assemble: the title is one comment line
            val symbols = Assembler.assemble(asm).symbols
            assertFalse("injected label must not exist", symbols.containsKey("injected"))
            assertFalse("injected label must not exist", symbols.containsKey("INJECTED"))
            val firstLine = asm.lines().first()
            assertFalse("title must be a single line", firstLine.contains("\n"))
            assertTrue("title must stay a comment", firstLine.startsWith(";"))
        }
        // hostile mechanics: unknown keywords fall back, never crash
        val spec = GameSpec("x", mechanics = listOf("rm -rf /", "draw 3D polygons", "", "\nRESET:"))
        val dir = tmpDir()
        val result = testLoop(dir, mock, RuleBasedRepair()).run(spec, "injection")
        assertTrue("hostile spec must still yield a green game", result.success)
        val report = NesValidator().validate(result.finalRom!!)
        assertTrue(report.passed)
    }

    // ------------------------------------------------------------------
    // 4. the classic silent generator bug: NMI-enable lines dropped.
    //    The loop must catch it (RED, NMI_ENABLED) and repair it.
    // ------------------------------------------------------------------

    class OffByOneGenerator : Generator, ChrProvider {
        private val mock = MockGenerator()
        override fun generate(spec: GameSpec): String =
            mock.generate(spec).replace(
                "    LDA #%10000000\n    STA \$2000           ; NMI on\n",
                "    ; off-by-one: NMI enable silently dropped\n",
            )
        override fun chr(): ByteArray = ChrData.default()
    }

    @Test fun offByOneDropsNmiEnableIsCaughtAndRepaired() {
        val dir = tmpDir()
        val spec = GameSpec("offbyone", mechanics = listOf("move"))
        val result = testLoop(dir, OffByOneGenerator(), RuleBasedRepair()).run(spec, "offbyone")
        assertTrue("repair should restore NMI, note=${result.note}", result.success)
        assertEquals(2, result.attempts.size)
        // the loop CAUGHT it: attempt 1 is RED with exactly the right rule
        assertEquals(listOf("NMI_ENABLED"), result.attempts[0].firedRules)
        assertTrue(result.attempts[1].passed)
        val log = File(dir, "offbyone-attempts.log").readText()
        assertTrue(log.contains("NMI_ENABLED"))
        assertTrue(log.contains("NMI enable"))
    }

    // ------------------------------------------------------------------
    // 5. throwing generator / empty output: honest failure, never a crash.
    // ------------------------------------------------------------------

    @Test fun throwingGeneratorIsAnHonestFailure() {
        val dir = tmpDir()
        val bomb = Generator { throw RuntimeException("model exploded") }
        val result = testLoop(dir, bomb, RuleBasedRepair()).run(GameSpec("bomb"), "bomb")
        assertFalse(result.success)
        assertTrue(result.attempts.isEmpty())
        assertTrue(result.note!!.contains("generator threw"))
        assertTrue(File(dir, "bomb-attempts.log").exists())
    }

    @Test fun emptyAsmIsAnHonestFailure() {
        val dir = tmpDir()
        val empty = object : Generator {
            override fun generate(spec: GameSpec): String = ""
        }
        // surgery alone cannot fix "no program": honest stop, no crash
        val result = testLoop(dir, empty, RuleBasedRepair(), maxAttempts = 3).run(GameSpec("empty"), "empty")
        assertFalse(result.success)
        assertTrue(result.attempts[0].buildError!!.contains("rom build"))
        assertTrue(result.note!!.contains("no progress") || result.note!!.contains("identical"))
        assertTrue(File(dir, "empty-attempts.log").exists())
    }

    @Test fun garbageAsmIsAnHonestFailure() {
        val dir = tmpDir()
        val garbage = object : Generator {
            override fun generate(spec: GameSpec): String = "LDA #\$ZZZ\n.this is not asm\n"
        }
        val result = testLoop(dir, garbage, RuleBasedRepair(), maxAttempts = 3).run(GameSpec("garbage"), "garbage")
        assertFalse(result.success)
        assertFalse(result.attempts[0].assembled)
        assertTrue(result.attempts[0].buildError!!.contains("assemble:"))
    }

    // ------------------------------------------------------------------
    // 6. repairer adversarial: repair must not corrupt a GREEN program.
    // ------------------------------------------------------------------

    @Test fun repairNeverTouchesGreenOutput() {
        // If the loop somehow repairs a passing program, that is a bug.
        // Direct check: RuleBasedRepair on a GREEN report-shaped failure with
        // no FAILs returns null (nothing to do).
        val mock = MockGenerator()
        val spec = GameSpec("untouched", mechanics = listOf("move"))
        val asm = mock.generate(spec)
        val report = NesValidator().validate(RomBuilder.build(Assembler.assemble(asm).bytes, ChrData.default()))
        assertTrue(report.passed)
        val outcome = RuleBasedRepair().repair(FailedAttempt(1, asm, report, null))
        assertNull("no FAILs -> no patch must apply", outcome.asm)
    }
}
