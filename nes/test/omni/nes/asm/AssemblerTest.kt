package omni.nes.asm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * JUnit4 tests for [Assembler]. Covers every addressing mode, forward and
 * backward label references, the `<`/`>` operators, `.org` padding, `.res`,
 * string literals, `=` constants, all required error cases, and an
 * end-to-end assembly of roms/proof.asm asserting a valid 16384-byte
 * NROM-128 PRG image with correct vectors.
 */
class AssemblerTest {

    private fun asm(src: String): AssembleResult = Assembler.assemble(src)

    private fun expected(vararg bytes: Int): ByteArray =
        bytes.map { (it and 0xFF).toByte() }.toByteArray()

    private fun assertAssembles(src: String, vararg bytes: Int) {
        assertArrayEquals(expected(*bytes), asm(src).bytes)
    }

    private fun assertFails(src: String, line: Int) {
        try {
            asm(src)
            fail("expected AssembleError for:\n$src")
        } catch (e: AssembleError) {
            assertEquals("wrong error line", line, e.line)
        }
    }

    // ---------------- addressing modes ----------------

    @Test fun impliedMode() {
        assertAssembles("NOP", 0xEA)
        assertAssembles("SEI", 0x78)
        assertAssembles("CLD", 0xD8)
        assertAssembles("RTS", 0x60)
        assertAssembles("BRK", 0x00)
        assertAssembles("TAX", 0xAA)
        assertAssembles("DEX", 0xCA)
    }

    @Test fun accumulatorMode() {
        assertAssembles("LSR A", 0x4A)
        assertAssembles("ASL A", 0x0A)
        assertAssembles("ROL a", 0x2A) // case-insensitive
        assertAssembles("ROR A", 0x6A)
    }

    @Test fun immediateMode() {
        assertAssembles("LDA #\$01", 0xA9, 0x01)
        assertAssembles("LDX #%1010", 0xA2, 0x0A)
        assertAssembles("LDY #10", 0xA0, 0x0A)
        assertAssembles("CPY #\$FF", 0xC0, 0xFF)
        assertAssembles("ADC #1+2", 0x69, 0x03)
        assertAssembles("SBC #-1", 0xE9, 0xFF)
    }

    @Test fun zeroPageModes() {
        assertAssembles("LDA \$10", 0xA5, 0x10)
        assertAssembles("STA \$FF", 0x85, 0xFF)
        assertAssembles("LDA \$10,X", 0xB5, 0x10)
        assertAssembles("STA \$20,X", 0x95, 0x20)
        assertAssembles("LDX \$10,Y", 0xB6, 0x10)
        assertAssembles("STX \$10,Y", 0x96, 0x10)
        assertAssembles("LDY \$10,X", 0xB4, 0x10)
        assertAssembles("STY \$10,X", 0x94, 0x10)
        assertAssembles("BIT \$24", 0x24, 0x24)
        assertAssembles("ASL \$40", 0x06, 0x40)
        assertAssembles("INC \$40,X", 0xF6, 0x40)
        assertAssembles("DEC \$40", 0xC6, 0x40)
    }

    @Test fun absoluteModes() {
        assertAssembles("LDA \$1234", 0xAD, 0x34, 0x12)
        assertAssembles("STA \$2000", 0x8D, 0x00, 0x20)
        assertAssembles("LDA \$1234,X", 0xBD, 0x34, 0x12)
        assertAssembles("LDA \$1234,Y", 0xB9, 0x34, 0x12)
        assertAssembles("STA \$ABCD,X", 0x9D, 0xCD, 0xAB)
        assertAssembles("STA \$ABCD,Y", 0x99, 0xCD, 0xAB)
        assertAssembles("LDX \$1234,Y", 0xBE, 0x34, 0x12)
        assertAssembles("LDY \$1234,X", 0xBC, 0x34, 0x12)
        assertAssembles("BIT \$2002", 0x2C, 0x02, 0x20)
        assertAssembles("JSR \$FF00", 0x20, 0x00, 0xFF)
    }

    @Test fun indexedIndirectMode() {
        assertAssembles("LDA (\$10,X)", 0xA1, 0x10)
        assertAssembles("STA (\$FE,X)", 0x81, 0xFE)
        assertAssembles("ADC (\$00,X)", 0x61, 0x00)
        assertAssembles("CMP (\$44,X)", 0xC1, 0x44)
    }

    @Test fun indirectIndexedMode() {
        assertAssembles("LDA (\$10),Y", 0xB1, 0x10)
        assertAssembles("STA (\$20),Y", 0x91, 0x20)
        assertAssembles("ORA (\$30),Y", 0x11, 0x30)
        assertAssembles("SBC (\$40),Y", 0xF1, 0x40)
    }

    @Test fun jmpModes() {
        assertAssembles("JMP \$C000", 0x4C, 0x00, 0xC0)
        assertAssembles("JMP (\$1234)", 0x6C, 0x34, 0x12)
        assertAssembles("JMP ( \$FFFC )", 0x6C, 0xFC, 0xFF)
    }

    @Test fun branchesForwardAndBackward() {
        val r = asm(
            """
            .org ${'$'}1000
            start:  NOP
                    BEQ start
                    BNE fwd
                    NOP
            fwd:    RTS
            """.trimIndent(),
        )
        // BEQ at $1001 -> target $1000: offset -3. BNE at $1003 -> fwd $1006: offset +1.
        assertArrayEquals(expected(0xEA, 0xF0, 0xFD, 0xD0, 0x01, 0xEA, 0x60), r.bytes)
        assertEquals(0x1000, r.symbols["start"])
        assertEquals(0x1006, r.symbols["fwd"])
    }

    @Test fun allBranchesEncode() {
        val src = ".org \$8000\n" + listOf(
            "BPL" to 0x10, "BMI" to 0x30, "BVC" to 0x50, "BVS" to 0x70,
            "BCC" to 0x90, "BCS" to 0xB0, "BNE" to 0xD0, "BEQ" to 0xF0,
        ).joinToString("\n") { (m, _) -> "$m there" } + "\nthere: NOP"
        val bytes = asm(src).bytes
        val ops = listOf(0x10, 0x30, 0x50, 0x70, 0x90, 0xB0, 0xD0, 0xF0)
        ops.forEachIndexed { i, op ->
            // branch i sits at $8000+2i, target "there" is at $8010 -> offset = 14-2i
            assertEquals(op.toByte(), bytes[i * 2])
            assertEquals((14 - 2 * i).toByte(), bytes[i * 2 + 1])
        }
        assertEquals(0xEA.toByte(), bytes[16])
    }

    // ---------------- labels, operators, pc ----------------

    @Test fun forwardLabelReference() {
        val r = asm(".org \$C000\nJMP target\nNOP\ntarget: RTS")
        assertArrayEquals(expected(0x4C, 0x04, 0xC0, 0xEA, 0x60), r.bytes)
        assertEquals(0xC004, r.symbols["target"])
    }

    @Test fun backwardLabelReference() {
        val r = asm(".org \$C000\nloop: DEX\nBNE loop")
        // BNE at $C001, target $C000 -> offset = C000 - C003 = -3
        assertArrayEquals(expected(0xCA, 0xD0, 0xFD), r.bytes)
    }

    @Test fun lowHighOperators() {
        val r = asm(".org \$1234\nptr:\n.byte <ptr, >ptr\nLDA #<ptr\nLDA #>ptr\nSTA <ptr")
        assertArrayEquals(
            expected(0x34, 0x12, 0xA9, 0x34, 0xA9, 0x12, 0x85, 0x34),
            r.bytes,
        )
    }

    @Test fun pcSymbol() {
        assertAssembles(".org \$1000\nJMP *", 0x4C, 0x00, 0x10)
        val r = asm(".org \$2000\nhere: .word *")
        assertArrayEquals(expected(0x00, 0x20), r.bytes)
        assertEquals(0x2000, r.symbols["here"])
    }

    @Test fun labelOnOwnLineAndInline() {
        val r = asm(".org \$0\nalone:\ninline: NOP\nJMP inline")
        assertEquals(0, r.symbols["alone"])
        assertEquals(0, r.symbols["inline"])
        assertArrayEquals(expected(0xEA, 0x4C, 0x00, 0x00), r.bytes)
    }

    // ---------------- directives ----------------

    @Test fun orgPadding() {
        val r = asm(".org \$10\nNOP\n.org \$14\nNOP")
        assertEquals(0x10, r.origin)
        assertArrayEquals(expected(0xEA, 0x00, 0x00, 0x00, 0xEA), r.bytes)
    }

    @Test fun resDirective() {
        val r = asm(".org \$100\n.res 3\n.res 2,\$FF\nNOP")
        assertArrayEquals(expected(0x00, 0x00, 0x00, 0xFF, 0xFF, 0xEA), r.bytes)
    }

    @Test fun resWithExpressionCount() {
        assertAssembles(".org \$0\n.res 2*2+1", 0x00, 0x00, 0x00, 0x00, 0x00)
    }

    @Test fun stringLiteralsInByte() {
        assertAssembles(".org \$0\n.byte \"Hi\", \$21", 0x48, 0x69, 0x21)
        // ';' inside a string is not a comment
        assertAssembles(".org \$0\n.byte \"a;b\"", 0x61, 0x3B, 0x62)
    }

    @Test fun dbDwAliases() {
        assertAssembles(".org \$0\n.db \$01,\$02\n.dw \$1234", 0x01, 0x02, 0x34, 0x12)
    }

    @Test fun wordList() {
        val r = asm(".org \$0\n.word \$1234, \$ABCD, 1+1")
        assertArrayEquals(expected(0x34, 0x12, 0xCD, 0xAB, 0x02, 0x00), r.bytes)
    }

    @Test fun constantsWithEquals() {
        val r = asm("K = 10\nBASE = \$20\n.org \$0\nLDA BASE\n.byte K+5\n.word BASE*2")
        assertArrayEquals(expected(0xA5, 0x20, 0x0F, 0x40, 0x00), r.bytes)
        assertEquals(10, r.symbols["K"])
        assertEquals(0x20, r.symbols["BASE"])
    }

    @Test fun commentsAndBlankLines() {
        val r = asm("; full-line comment\n\n.org \$0 ; trailing comment\nLDA #\$01 ; c\n")
        assertArrayEquals(expected(0xA9, 0x01), r.bytes)
    }

    @Test fun expressionPrecedenceAndParens() {
        // 2+3*4=14 ; (2+3)*4=20 ; <$1234+1 = low($1234)+1 = $35 (ca65-style tight <)
        assertAssembles(".org \$0\n.byte 2+3*4, (2+3)*4, <\$1234+1", 0x0E, 0x14, 0x35)
        assertAssembles(".org \$0\n.byte >\$1234, %1010, 8-10", 0x12, 0x0A, 0xFE)
    }

    // ---------------- errors ----------------

    @Test fun undefinedLabelFails() {
        assertFails("LDA nowhere", 1)
    }

    @Test fun undefinedLabelInBranchFails() {
        assertFails(".org \$1000\nBNE nowhere", 2)
    }

    @Test fun branchOutOfRangeFails() {
        assertFails(".org \$1000\nBNE far\n.org \$2000\nfar: NOP", 2)
    }

    @Test fun badAddressingModeFails() {
        assertFails("STA #\$01", 1) // STA has no immediate mode
        assertFails("JMP (\$10),Y", 1) // JMP has no (ind),Y
        assertFails("LDA (\$1234)", 1) // (ind) is JMP-only
    }

    @Test fun orgBackwardsFails() {
        assertFails(".org \$100\nNOP\n.org \$50", 3)
    }

    @Test fun unknownMnemonicFails() {
        assertFails("FOO", 1)
        assertFails(".org \$0\nBAR \$10", 2)
    }

    @Test fun impliedWithOperandFails() {
        assertFails("NOP \$10", 1)
    }

    @Test fun missingOperandFails() {
        assertFails("LDA", 1)
        assertFails("JMP", 1)
    }

    @Test fun duplicateSymbolFails() {
        assertFails(".org \$0\nfoo: NOP\nfoo: NOP", 3)
    }

    @Test fun errorMessageCarriesLine() {
        try {
            asm(".org \$0\nNOP\nLDA missing")
            fail("expected AssembleError")
        } catch (e: AssembleError) {
            assertEquals(3, e.line)
            assertTrue(e.message!!.contains("line 3"))
        }
    }

    // ---------------- end-to-end: proof.asm ----------------

    @Test fun proofRomAssemblesToValidNrom128() {
        val proofFile = listOf(
            File(System.getProperty("user.home"), "workspace/omni-app/nes/roms/proof.asm"),
            File("/home/hatch/workspace/omni-app/nes/roms/proof.asm"),
            File(System.getProperty("user.dir"), "workspace/omni-app/nes/roms/proof.asm"),
        ).firstOrNull { it.isFile }
        assertTrue("proof.asm not found in any candidate location", proofFile != null)
        val r = asm(proofFile!!.readText())

        // PRG must be exactly 16384 bytes ($C000-$FFFF)
        assertEquals(16384, r.bytes.size)
        assertEquals(0xC000, r.origin)

        // reset vector lives at file offsets $3FFC-$3FFD, little-endian $C000
        val resetVec = (r.bytes[0x3FFC].toInt() and 0xFF) or
            ((r.bytes[0x3FFD].toInt() and 0xFF) shl 8)
        assertEquals(0xC000, resetVec)
        assertEquals(0xC000, r.symbols["RESET"])

        // NMI vector at $3FFA-$3FFB must be a real handler, not $0000
        val nmiVec = (r.bytes[0x3FFA].toInt() and 0xFF) or
            ((r.bytes[0x3FFB].toInt() and 0xFF) shl 8)
        assertNotEquals(0x0000, nmiVec)
        assertEquals(r.symbols["NMI"], nmiVec)

        // IRQ/BRK vector is intentionally $0000
        val irqVec = (r.bytes[0x3FFE].toInt() and 0xFF) or
            ((r.bytes[0x3FFF].toInt() and 0xFF) shl 8)
        assertEquals(0x0000, irqVec)

        // first two bytes are the RESET prologue: SEI, CLD
        assertEquals(0x78.toByte(), r.bytes[0])
        assertEquals(0xD8.toByte(), r.bytes[1])

        // spot-checks against the symbol table
        val nmi = r.symbols["NMI"]!!
        val nmiOff = nmi - 0xC000
        // NMI prologue PHA / TXA / PHA
        assertEquals(0x48.toByte(), r.bytes[nmiOff])
        assertEquals(0x8A.toByte(), r.bytes[nmiOff + 1])
        assertEquals(0x48.toByte(), r.bytes[nmiOff + 2])
        // INC $0203 (sprite slides right) and the OAM DMA pair LDA #$02 / STA $4014
        val incIdx = r.bytes.indexOfWindow(byteArrayOf(0xEE.toByte(), 0x03, 0x02))
        assertTrue("INC \$0203 missing", incIdx >= 0)
        val dmaIdx = r.bytes.indexOfWindow(byteArrayOf(0xA9.toByte(), 0x02, 0x8D.toByte(), 0x14, 0x40))
        assertTrue("LDA #\$02 / STA \$4014 missing", dmaIdx >= 0)
        // palette label + 32 data bytes exist
        assertTrue(r.symbols.containsKey("palette"))
    }

    /** Index of the first occurrence of [window], or -1. */
    private fun ByteArray.indexOfWindow(window: ByteArray): Int {
        if (window.isEmpty() || window.size > size) return -1
        outer@ for (i in 0..size - window.size) {
            for (j in window.indices) if (this[i + j] != window[j]) continue@outer
            return i
        }
        return -1
    }
}
