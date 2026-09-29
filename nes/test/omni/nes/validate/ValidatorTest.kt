package omni.nes.validate

import java.io.File
import omni.nes.asm.Assembler
import omni.nes.rom.RomBuilder
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for the Phase-1 NES hardware-truth validator ([NesValidator]).
 *
 * Clean-room tests: every ROM is synthesized byte-by-byte below (or assembled
 * from the repo's own proof.asm with the repo's own assembler). JUnit4 only.
 */
class ValidatorTest {

    // ---------- helpers ----------

    /** Build an Int list into bytes. */
    private fun b(vararg xs: Int): ByteArray = ByteArray(xs.size) { i -> xs[i].toByte() }

    /**
     * Build a 16KB NROM test ROM: [reset] code at $C000, [nmi] at $C100,
     * vectors at $FFFA (NMI, RESET, IRQ). [patch] places extra bytes at PRG
     * offsets (e.g. indirect-JMP targets).
     */
    private fun romOf(
        reset: ByteArray,
        nmi: ByteArray = b(0x40),
        irqAddr: Int = 0x0000,
        resetVec: Int = 0xC000,
        nmiVec: Int = 0xC100,
        patch: Map<Int, Int> = emptyMap(),
    ): ByteArray {
        val prg = ByteArray(16384)
        reset.copyInto(prg, 0)
        nmi.copyInto(prg, 0x100)
        for ((off, v) in patch) prg[off] = v.toByte()
        val v = 16384 - 6
        prg[v] = (nmiVec and 0xFF).toByte()
        prg[v + 1] = ((nmiVec ushr 8) and 0xFF).toByte()
        prg[v + 2] = (resetVec and 0xFF).toByte()
        prg[v + 3] = ((resetVec ushr 8) and 0xFF).toByte()
        prg[v + 4] = (irqAddr and 0xFF).toByte()
        prg[v + 5] = ((irqAddr ushr 8) and 0xFF).toByte()
        return RomBuilder.build(prg)
    }

    /** Minimal well-behaved reset: SEI, CLI, NMI on, rendering on, idle loop. */
    private fun cleanReset(): ByteArray = b(
        0x78, // SEI
        0x58, // CLI (interrupts must actually be able to fire)
        0xA9, 0x80, // LDA #$80
        0x8D, 0x00, 0x20, // STA $2000 (NMI on)
        0xA9, 0x1E, // LDA #$1E
        0x8D, 0x01, 0x20, // STA $2001 (rendering on)
        0x4C, 0x00, 0xC0, // JMP $C000
    )

    /**
     * cleanReset without the CLI: the I flag stays set, so the NMI vector is
     * never actually taken during boot. Used to isolate the static
     * illegal-opcode rule from the dynamic boot rule.
     */
    private fun maskedReset(): ByteArray = b(
        0x78, // SEI (and no CLI: NMI stays masked)
        0xA9, 0x80, // LDA #$80
        0x8D, 0x00, 0x20, // STA $2000 (NMI on)
        0xA9, 0x1E, // LDA #$1E
        0x8D, 0x01, 0x20, // STA $2001 (rendering on)
        0x4C, 0x00, 0xC0, // JMP $C000
    )

    private fun hasRule(report: ValidationReport, ruleId: String) =
        report.findings.any { it.ruleId == ruleId }

    private fun failsWith(report: ValidationReport, ruleId: String) =
        report.fails().any { it.ruleId == ruleId }

    /** Raw iNES file with a hand-built header (for header-rule tests). */
    private fun iNesFile(
        prgBanks: Int,
        chrBanks: Int = 1,
        f6: Int = 0,
        f7: Int = 0,
        prgSize: Int = prgBanks * 16384,
    ): ByteArray {
        val h = ByteArray(16)
        h[0] = 0x4E; h[1] = 0x45; h[2] = 0x53; h[3] = 0x1A
        h[4] = prgBanks.toByte(); h[5] = chrBanks.toByte()
        h[6] = f6.toByte(); h[7] = f7.toByte()
        return h + ByteArray(prgSize) + ByteArray(chrBanks * 8192)
    }

    // ---------- positive cases ----------

    @Test fun positiveMinimal() {
        val report = NesValidator().validate(romOf(cleanReset(), b(0x48, 0x68, 0x40)))
        assertTrue("expected zero fails, got ${report.fails()}", report.fails().isEmpty())
        assertTrue(report.passed)
    }

    @Test fun proofRomGreen() {
        val candidates = listOf(
            "roms/proof.asm",
            "../roms/proof.asm",
            "nes/roms/proof.asm",
            System.getProperty("user.home") + "/workspace/omni-app/nes/roms/proof.asm",
            System.getProperty("user.dir") + "/workspace/omni-app/nes/roms/proof.asm",
            "/home/hatch/workspace/omni-app/nes/roms/proof.asm",
        )
        val path = candidates.firstOrNull { File(it).exists() }
        assertNotNull("proof.asm not found; tried: $candidates", path)
        val assembled = Assembler.assemble(File(path!!).readText())
        val rom = RomBuilder.build(assembled.bytes)
        val report = NesValidator().validate(rom)
        assertTrue("proof ROM should pass, findings: ${report.findings}", report.passed)
    }

    // ---------- header rules ----------

    @Test fun badMagic() {
        val report = NesValidator().validate(ByteArray(64))
        assertEquals(listOf("HDR_MAGIC"), report.fails().map { it.ruleId })
    }

    @Test fun prgBanksZero() {
        val report = NesValidator().validate(iNesFile(0))
        assertEquals(listOf("HDR_PRG_SIZE"), report.fails().map { it.ruleId })
    }

    @Test fun prgBanksThree() {
        val report = NesValidator().validate(iNesFile(3))
        assertEquals(listOf("HDR_PRG_SIZE"), report.fails().map { it.ruleId })
    }

    @Test fun truncatedHeader() {
        // Header claims 2x16KB PRG, file supplies 1.
        val report = NesValidator().validate(iNesFile(2, prgSize = 16384))
        assertEquals(listOf("HDR_PRG_SIZE"), report.fails().map { it.ruleId })
    }

    @Test fun mapperOne() {
        val report = NesValidator().validate(iNesFile(1, f6 = 0x10))
        assertEquals(listOf("HDR_MAPPER"), report.fails().map { it.ruleId })
    }

    @Test fun trainerBit() {
        val report = NesValidator().validate(iNesFile(1, f6 = 0x04))
        assertEquals(listOf("HDR_TRAINER"), report.fails().map { it.ruleId })
    }

    // ---------- vector rules ----------

    @Test fun resetVecZero() {
        val report = NesValidator().validate(romOf(cleanReset(), resetVec = 0x0000))
        val f = report.fails().firstOrNull { it.ruleId == "VEC_RESET_IN_PRG" }
        assertNotNull("expected VEC_RESET_IN_PRG, got ${report.fails()}", f)
        assertTrue("message should include hex value, got: ${f!!.message}", "\$0000" in f.message)
    }

    @Test fun resetVecRam() {
        val report = NesValidator().validate(romOf(cleanReset(), resetVec = 0x1000))
        assertTrue(failsWith(report, "VEC_RESET_IN_PRG"))
        assertTrue(report.fails().any { it.ruleId == "VEC_RESET_IN_PRG" && "\$1000" in it.message })
    }

    @Test fun irqVecZeroWarns() {
        val report = NesValidator().validate(romOf(cleanReset()))
        assertTrue("expected pass, got ${report.fails()}", report.passed)
        assertTrue(report.warns().any { it.ruleId == "VEC_IRQ_IN_PRG" })
    }

    // ---------- illegal opcodes ----------

    /**
     * Illegal opcode ($02 = KIL) as the first byte of the NMI handler:
     * statically reachable from the NMI vector, but never executed during
     * boot (maskedReset keeps the I flag set, so the handler never runs).
     * Lets the strict and lax tests share one ROM — a $02 at reset start
     * would also trip BOOT_ILLEGAL_OPCODE and could never pass lax.
     */
    private fun illegalNmiRom(): ByteArray = romOf(maskedReset(), nmi = b(0x02, 0x40))

    @Test fun illegalStrict() {
        val report = NesValidator().validate(illegalNmiRom())
        assertTrue(failsWith(report, "ILLEGAL_OPCODE"))
        assertFalse(report.passed)
    }

    @Test fun illegalLax() {
        // requireNmi is also off: with the I flag masked this ROM can never
        // produce an NMI frame, and this test is about the strictness toggle.
        val v = NesValidator(ValidatorConfig(strictIllegalOpcodes = false, requireNmi = false))
        val report = v.validate(illegalNmiRom())
        assertTrue("expected pass, got ${report.fails()}", report.passed)
        assertTrue(report.warns().any { it.ruleId == "ILLEGAL_OPCODE" })
    }

    // ---------- NMI rules ----------

    @Test fun nmiNeverEnabled() {
        val reset = b(
            0xA9, 0x1E, // LDA #$1E
            0x8D, 0x01, 0x20, // STA $2001 (rendering on, but $2000 bit 7 never set)
            0x4C, 0x00, 0xC0, // JMP $C000
        )
        val report = NesValidator().validate(romOf(reset))
        assertTrue(failsWith(report, "NMI_ENABLED"))
        assertFalse(report.passed)
    }

    @Test fun nmiNeverEnabledLax() {
        val reset = b(
            0xA9, 0x1E, // LDA #$1E
            0x8D, 0x01, 0x20, // STA $2001
            0x4C, 0x00, 0xC0, // JMP $C000
        )
        val report = NesValidator(ValidatorConfig(requireNmi = false)).validate(romOf(reset))
        assertTrue("expected pass, got ${report.fails()}", report.passed)
        assertFalse(hasRule(report, "NMI_ENABLED"))
    }

    @Test fun emptyNmi() {
        val report = NesValidator().validate(romOf(cleanReset(), nmi = b(0x40)))
        assertTrue("expected pass, got ${report.fails()}", report.passed)
        assertTrue(report.warns().any { it.ruleId == "NMI_HANDLER_EMPTY" })
    }

    // ---------- sprite overflow ----------

    /** NMI handler writing [count] sprites at scanline [y] via $2003/$2004. */
    private fun spriteNmi(count: Int, y: Int = 0x40): ByteArray {
        val out = ArrayList<Byte>()
        for (i in 0 until count) {
            val oam = i * 4
            for (x in listOf(0xA2, oam, 0x8E, 0x03, 0x20, 0xA9, y, 0x8D, 0x04, 0x20)) {
                out.add(x.toByte())
            }
        }
        out.add(0x40.toByte()) // RTI
        return out.toByteArray()
    }

    @Test fun spriteOverflow9() {
        val report = NesValidator().validate(romOf(cleanReset(), nmi = spriteNmi(9)))
        assertTrue(failsWith(report, "SPRITE_OVERFLOW"))
        assertFalse(report.passed)
    }

    @Test fun spriteOk8() {
        val report = NesValidator().validate(romOf(cleanReset(), nmi = spriteNmi(8)))
        assertTrue("expected pass, got ${report.fails()}", report.passed)
        assertFalse(hasRule(report, "SPRITE_OVERFLOW"))
    }

    @Test fun spriteCheckOff() {
        val v = NesValidator(ValidatorConfig(checkSpriteOverflow = false))
        val report = v.validate(romOf(cleanReset(), nmi = spriteNmi(9)))
        assertTrue("expected pass, got ${report.fails()}", report.passed)
        assertFalse(hasRule(report, "SPRITE_OVERFLOW"))
    }

    // ---------- palette timing ----------

    /** Reset code doing a $3F00 palette write with rendering set to [ppumask]. */
    private fun paletteReset(ppumask: Int): ByteArray = b(
        0x58, // CLI (NMI must be able to fire or NMI_ENABLED trips)
        0xA9, 0x80, 0x8D, 0x00, 0x20, // LDA #$80; STA $2000 (NMI on)
        0xA9, ppumask, 0x8D, 0x01, 0x20, // LDA #ppumask; STA $2001
        0xA9, 0x3F, 0x8D, 0x06, 0x20, // LDA #$3F; STA $2006
        0xA9, 0x00, 0x8D, 0x06, 0x20, // LDA #$00; STA $2006 -> PPUADDR=$3F00
        0xA9, 0x0F, 0x8D, 0x07, 0x20, // LDA #$0F; STA $2007 -> palette write
        0x4C, 0x00, 0xC0, // JMP $C000
    )

    @Test fun paletteBad() {
        val report = NesValidator().validate(romOf(paletteReset(0x1E)))
        assertTrue(failsWith(report, "PALETTE_TIMING"))
        assertFalse(report.passed)
    }

    @Test fun paletteForcedBlank() {
        val report = NesValidator().validate(romOf(paletteReset(0x00)))
        assertTrue("expected pass, got ${report.fails()}", report.passed)
    }

    @Test fun paletteCheckOff() {
        val v = NesValidator(ValidatorConfig(checkPaletteTiming = false))
        val report = v.validate(romOf(paletteReset(0x1E)))
        assertTrue("expected pass, got ${report.fails()}", report.passed)
    }

    // ---------- boot rules ----------

    @Test fun bootIllegalIndirect() {
        // $C000: JMP ($C010); ($C010) -> $C020; $C020 holds $02 (KIL).
        // The sweep stops at the indirect JMP, so no static ILLEGAL_OPCODE;
        // the boot executes it and must report BOOT_ILLEGAL_OPCODE.
        val rom = romOf(
            reset = b(0x6C, 0x10, 0xC0),
            patch = mapOf(0x10 to 0x20, 0x11 to 0xC0, 0x20 to 0x02),
        )
        val report = NesValidator().validate(rom)
        assertFalse("static sweep must not see past indirect JMP", hasRule(report, "ILLEGAL_OPCODE"))
        assertTrue(failsWith(report, "BOOT_ILLEGAL_OPCODE"))
        assertFalse(report.passed)
    }

    @Test fun bootRomWrite() {
        val reset = b(
            0xA9, 0x01, // LDA #$01
            0x8D, 0x00, 0x80, // STA $8000 (PRG ROM: unwritable on hardware)
            0x4C, 0x00, 0xC0, // JMP $C000
        )
        val report = NesValidator().validate(romOf(reset))
        val f = report.fails().firstOrNull { it.ruleId == "BOOT_BUS_FAULT" }
        assertNotNull("expected BOOT_BUS_FAULT, got ${report.fails()}", f)
        assertTrue("message should name ROM_WRITE, got: ${f!!.message}", "ROM_WRITE" in f.message)
        assertFalse(report.passed)
    }

    @Test fun bootStackUnderflow() {
        val reset = b(
            0x68, // PLA (nothing was pushed)
            0x4C, 0x00, 0xC0, // JMP $C000
        )
        val report = NesValidator().validate(romOf(reset))
        assertTrue(failsWith(report, "BOOT_STACK_FAULT"))
        assertFalse(report.passed)
    }

    // ---------- destructive-QA regressions (holes found by adversarial probing) ----------

    @Test fun pcEscapedPrgFails() {
        // NMI on, then JMP $FFFF. $FFFF holds $EA (NOP, a 1-byte instruction)
        // via irqAddr=$EAC1, whose value is still inside PRG (no vector
        // finding). After the NOP the PC leaves the PRG window with no
        // control-flow instruction behind it: a fall off the end of the ROM.
        // On hardware this executes whatever the RAM powers up with.
        val reset = b(
            0x78, // SEI
            0x58, // CLI
            0xA9, 0x80, // LDA #$80
            0x8D, 0x00, 0x20, // STA $2000 (NMI on)
            0x4C, 0xFF, 0xFF, // JMP $FFFF
        )
        val report = NesValidator().validate(romOf(reset, b(0x40), irqAddr = 0xEAC1))
        assertTrue("expected BOOT_PC_ESCAPED_PRG, fails: ${report.fails()}", failsWith(report, "BOOT_PC_ESCAPED_PRG"))
        assertFalse(report.passed)
    }

    @Test fun deliberateJumpToRamPasses() {
        // A deliberate JMP (indirect) into RAM is a legal jump, not a fall-off.
        // Reset code copies `JMP $0200` into $0200-$0202, then jumps there.
        val reset = b(
            0x78, // SEI
            0x58, // CLI
            0xA9, 0x80, 0x8D, 0x00, 0x20, // LDA #$80 / STA $2000 (NMI on)
            0xA9, 0x4C, 0x8D, 0x00, 0x02, // LDA #$4C / STA $0200
            0xA9, 0x00, 0x8D, 0x01, 0x02, // LDA #$00 / STA $0201
            0xA9, 0x02, 0x8D, 0x02, 0x02, // LDA #$02 / STA $0202  -> ($0200) = JMP $0200
            0xA9, 0x00, 0x85, 0x10, // LDA #$00 / STA $10
            0xA9, 0x02, 0x85, 0x11, // LDA #$02 / STA $11  -> ($10) = $0200
            0x6C, 0x10, 0x00, // JMP ($0010)
        )
        val report = NesValidator().validate(romOf(reset, b(0x40)))
        assertTrue("expected pass, fails: ${report.fails()}", report.passed)
        assertFalse(failsWith(report, "BOOT_PC_ESCAPED_PRG"))
    }

    @Test fun paletteAcrossJsrPasses() {
        // Forced blank ($2001 = $00) in reset, palette upload in a JSR'd
        // subroutine: legal, and must not trip PALETTE_TIMING even though the
        // const tracker cannot see across the subroutine boundary.
        val upload = b(
            0xA9, 0x3F, 0x8D, 0x06, 0x20, // LDA #$3F / STA $2006
            0xA9, 0x00, 0x8D, 0x06, 0x20, // LDA #$00 / STA $2006 (-> $3F00)
            0xA9, 0x0F, 0x8D, 0x07, 0x20, // LDA #$0F / STA $2007
            0x60, // RTS
        )
        val uploadOff = 0x200
        val patch = upload.mapIndexed { i, v -> (uploadOff + i) to (v.toInt() and 0xFF) }.toMap()
        val reset = b(
            0x78, // SEI
            0x58, // CLI
            0xA9, 0x00, 0x8D, 0x01, 0x20, // LDA #$00 / STA $2001 (forced blank)
            0x20, 0x00, 0xC2, // JSR $C200 (palette upload)
            0xA9, 0x80, 0x8D, 0x00, 0x20, // LDA #$80 / STA $2000 (NMI on)
            0xA9, 0x1E, 0x8D, 0x01, 0x20, // LDA #$1E / STA $2001 (rendering on)
            0x4C, 0x00, 0xC0, // JMP $C000
        )
        val report = NesValidator().validate(romOf(reset, b(0x40), patch = patch))
        assertTrue("expected pass, fails: ${report.fails()}", report.passed)
        assertFalse("PALETTE_TIMING must not FAIL here", failsWith(report, "PALETTE_TIMING"))
        assertTrue("untracked ppumask should be INFO-flagged", hasRule(report, "PALETTE_TIMING"))
    }
}
