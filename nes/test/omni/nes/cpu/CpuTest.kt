package omni.nes.cpu

import org.junit.Test
import org.junit.Assert.*

/**
 * Hardware-truth tests for [Cpu6502] + [StubBus]. Clean-room tests against
 * documented 6502/NES behavior. JUnit4 only.
 */
class CpuTest {

    // ---------- helpers ----------

    private fun cpuFor(vararg bytes: Int, resetVector: Int = 0x8000): Pair<Cpu6502, StubBus> {
        val prg = ByteArray(32768)
        bytes.forEachIndexed { i, b -> prg[i] = b.toByte() }
        prg[0x7FFC] = (resetVector and 0xFF).toByte()
        prg[0x7FFD] = ((resetVector ushr 8) and 0xFF).toByte()
        val bus = StubBus(prg, false, ByteArray(8192))
        val cpu = Cpu6502(bus)
        cpu.reset()
        return Pair(cpu, bus)
    }

    /** Load [bytes] at an arbitrary PRG address (for page-cross tests). */
    private fun cpuAt(addr: Int, vararg bytes: Int): Pair<Cpu6502, StubBus> {
        val prg = ByteArray(32768)
        bytes.forEachIndexed { i, b -> prg[addr - 0x8000 + i] = b.toByte() }
        prg[0x7FFC] = (addr and 0xFF).toByte()
        prg[0x7FFD] = ((addr ushr 8) and 0xFF).toByte()
        val bus = StubBus(prg, false, ByteArray(8192))
        val cpu = Cpu6502(bus)
        cpu.reset()
        return Pair(cpu, bus)
    }

    private fun Cpu6502.stepOk(): Int {
        val r = step()
        assertTrue("expected Ok but was $r", r is Cpu6502.StepResult.Ok)
        return (r as Cpu6502.StepResult.Ok).cycles
    }

    // ---------- opcode table ----------

    @Test
    fun opcodeTableHasExactly151OfficialEntries() {
        assertEquals(151, Opcodes.officialCount())
    }

    // ---------- LDA / STA addressing modes ----------

    @Test
    fun ldaImmediate() {
        val (cpu, _) = cpuFor(0xA9, 0x42)
        assertEquals(2, cpu.stepOk())
        assertEquals(0x42, cpu.a)
    }

    @Test
    fun ldaAllAddressingModes() {
        val (cpu, bus) = cpuFor(
            0xA2, 0x05, // LDX #$05
            0xA0, 0x06, // LDY #$06
            0xA9, 0x42, // LDA #$42
            0xA5, 0x10, // LDA $10
            0xB5, 0x20, // LDA $20,X
            0xAD, 0x00, 0x03, // LDA $0300
            0xBD, 0x05, 0x03, // LDA $0305,X
            0xB9, 0x06, 0x03, // LDA $0306,Y
            0xA1, 0x30, // LDA ($30,X)
            0xB1, 0x40, // LDA ($40),Y
        )
        bus.write(0x0010, 0x11)
        bus.write(0x0025, 0x22)
        bus.write(0x0300, 0x33)
        bus.write(0x030A, 0x44)
        bus.write(0x030C, 0x55)
        bus.write(0x0035, 0x00); bus.write(0x0036, 0x04) // ($35) -> $0400
        bus.write(0x0400, 0x66)
        bus.write(0x0040, 0x00); bus.write(0x0041, 0x05) // ($40) -> $0500
        bus.write(0x0506, 0x77)
        cpu.stepOk(); cpu.stepOk() // LDX, LDY
        assertEquals(0x42, cpu.stepOk().let { cpu.a })
        assertEquals(0x11, cpu.stepOk().let { cpu.a })
        assertEquals(0x22, cpu.stepOk().let { cpu.a })
        assertEquals(0x33, cpu.stepOk().let { cpu.a })
        assertEquals(0x44, cpu.stepOk().let { cpu.a })
        assertEquals(0x55, cpu.stepOk().let { cpu.a })
        assertEquals(0x66, cpu.stepOk().let { cpu.a })
        assertEquals(0x77, cpu.stepOk().let { cpu.a })
        // zero-page,X + absolute,X crossed no page here: 4 cycles each
        assertEquals(0, cpu.p and Cpu6502.FLAG_Z)
    }

    @Test
    fun staAllAddressingModes() {
        val (cpu, bus) = cpuFor(
            0xA2, 0x05, // LDX #$05
            0xA0, 0x06, // LDY #$06
            0xA9, 0x99, // LDA #$99
            0x85, 0x10, // STA $10
            0x95, 0x20, // STA $20,X
            0x8D, 0x00, 0x03, // STA $0300
            0x9D, 0x05, 0x03, // STA $0305,X
            0x99, 0x06, 0x03, // STA $0306,Y
            0x81, 0x30, // STA ($30,X)
            0x91, 0x40, // STA ($40),Y
        )
        bus.write(0x0035, 0x00); bus.write(0x0036, 0x04)
        bus.write(0x0040, 0x00); bus.write(0x0041, 0x05)
        repeat(10) { cpu.stepOk() }
        assertEquals(0x99, bus.read(0x0010))
        assertEquals(0x99, bus.read(0x0025))
        assertEquals(0x99, bus.read(0x0300))
        assertEquals(0x99, bus.read(0x030A))
        assertEquals(0x99, bus.read(0x030C))
        assertEquals(0x99, bus.read(0x0400))
        assertEquals(0x99, bus.read(0x0506))
    }

    // ---------- ADC / SBC flags ----------

    @Test
    fun adcCarryAndZero() {
        val (cpu, _) = cpuFor(0xA9, 0xFF, 0x18, 0x69, 0x01) // LDA #$FF; CLC; ADC #$01
        cpu.stepOk(); cpu.stepOk(); cpu.stepOk()
        assertEquals(0x00, cpu.a)
        assertTrue(cpu.p and Cpu6502.FLAG_C != 0)
        assertTrue(cpu.p and Cpu6502.FLAG_Z != 0)
        assertTrue(cpu.p and Cpu6502.FLAG_N == 0)
        assertTrue(cpu.p and Cpu6502.FLAG_V == 0)
    }

    @Test
    fun adcOverflowAndNegative() {
        val (cpu, _) = cpuFor(0xA9, 0x50, 0x18, 0x69, 0x50) // 80 + 80 = 160
        cpu.stepOk(); cpu.stepOk(); cpu.stepOk()
        assertEquals(0xA0, cpu.a)
        assertTrue(cpu.p and Cpu6502.FLAG_C == 0)
        assertTrue(cpu.p and Cpu6502.FLAG_V != 0)
        assertTrue(cpu.p and Cpu6502.FLAG_N != 0)
        assertTrue(cpu.p and Cpu6502.FLAG_Z == 0)
    }

    @Test
    fun adcHonorsCarryIn() {
        val (cpu, _) = cpuFor(0x38, 0xA9, 0x00, 0x69, 0x00) // SEC; LDA #$00; ADC #$00
        cpu.stepOk(); cpu.stepOk(); cpu.stepOk()
        assertEquals(0x01, cpu.a)
        assertTrue(cpu.p and Cpu6502.FLAG_C == 0)
    }

    @Test
    fun sbcBasicSetsCarry() {
        val (cpu, _) = cpuFor(0xA9, 0x50, 0x38, 0xE9, 0x10) // LDA #$50; SEC; SBC #$10
        cpu.stepOk(); cpu.stepOk(); cpu.stepOk()
        assertEquals(0x40, cpu.a)
        assertTrue(cpu.p and Cpu6502.FLAG_C != 0)
        assertTrue(cpu.p and Cpu6502.FLAG_Z == 0)
        assertTrue(cpu.p and Cpu6502.FLAG_N == 0)
        assertTrue(cpu.p and Cpu6502.FLAG_V == 0)
    }

    @Test
    fun sbcBorrowClearsCarryAndSetsNegative() {
        val (cpu, _) = cpuFor(0xA9, 0x00, 0x18, 0xE9, 0x01) // LDA #$00; CLC; SBC #$01
        cpu.stepOk(); cpu.stepOk(); cpu.stepOk()
        assertEquals(0xFE, cpu.a)
        assertTrue(cpu.p and Cpu6502.FLAG_C == 0)
        assertTrue(cpu.p and Cpu6502.FLAG_N != 0)
        assertTrue(cpu.p and Cpu6502.FLAG_V == 0)
    }

    @Test
    fun sbcOverflow() {
        val (cpu, _) = cpuFor(0xA9, 0x7F, 0x38, 0xE9, 0xFF) // 127 - (-1) = 128
        cpu.stepOk(); cpu.stepOk(); cpu.stepOk()
        assertEquals(0x80, cpu.a)
        assertTrue(cpu.p and Cpu6502.FLAG_V != 0)
        assertTrue(cpu.p and Cpu6502.FLAG_N != 0)
        assertTrue(cpu.p and Cpu6502.FLAG_C == 0)
    }

    // ---------- branches ----------

    @Test
    fun branchTakenSamePageCosts3Cycles() {
        val (cpu, _) = cpuFor(
            0xA9, 0x01, // LDA #$01
            0xD0, 0x02, // BNE +2 -> $8006
            0xA9, 0xFF, // (skipped)
            0xA9, 0x07, // LDA #$07
        )
        cpu.stepOk()
        assertEquals(3, cpu.stepOk())
        cpu.stepOk()
        assertEquals(0x07, cpu.a)
        assertEquals(7L, cpu.cycles)
    }

    @Test
    fun branchNotTakenCosts2Cycles() {
        val (cpu, _) = cpuFor(
            0xA9, 0x00, // LDA #$00
            0xD0, 0x02, // BNE +2 (not taken)
            0xA9, 0x05, // LDA #$05
        )
        cpu.stepOk()
        assertEquals(2, cpu.stepOk())
        cpu.stepOk()
        assertEquals(0x05, cpu.a)
        assertEquals(6L, cpu.cycles)
    }

    @Test
    fun branchTakenAcrossPageCosts4Cycles() {
        val (cpu, _) = cpuAt(
            0x80F8,
            0xA9, 0x01, // $80F8 LDA #$01
            0xD0, 0x04, // $80FA BNE +4 -> $8100 (page cross)
            0xA9, 0xFF, // $80FC (skipped)
            0xEA, // $80FE
            0xEA, // $80FF
            0xA9, 0x09, // $8100 LDA #$09
        )
        cpu.stepOk()
        assertEquals(4, cpu.stepOk())
        assertEquals(0x8100, cpu.pc)
        cpu.stepOk()
        assertEquals(0x09, cpu.a)
        assertEquals(8L, cpu.cycles)
    }

    // ---------- subroutines / stack ----------

    @Test
    fun jsrRtsNesting() {
        val (cpu, bus) = cpuFor(
            0x20, 0x0A, 0x80, // $8000 JSR $800A
            0xA9, 0x01, // $8003 LDA #$01
            0x8D, 0x00, 0x02, // $8005 STA $0200
            0xEA, // $8008
            0xEA, // $8009
            0x20, 0x0E, 0x80, // $800A JSR $800E
            0x60, // $800D RTS
            0xA9, 0x77, // $800E LDA #$77
            0x8D, 0x01, 0x02, // $8010 STA $0201
            0x60, // $8013 RTS
        )
        repeat(8) { cpu.stepOk() }
        assertEquals(0x77, bus.read(0x0201))
        assertEquals(0x01, bus.read(0x0200))
        assertEquals(0x01, cpu.a)
        assertEquals(0x8008, cpu.pc) // sitting on the NOP after STA $0200
    }

    @Test
    fun phaPlaRoundTrip() {
        val (cpu, _) = cpuFor(0xA9, 0xAB, 0x48, 0xA9, 0x00, 0x68) // LDA #$AB; PHA; LDA #$00; PLA
        cpu.stepOk(); cpu.stepOk(); cpu.stepOk()
        assertEquals(4, cpu.stepOk())
        assertEquals(0xAB, cpu.a)
        assertTrue(cpu.p and Cpu6502.FLAG_N != 0)
        assertTrue(cpu.p and Cpu6502.FLAG_Z == 0)
    }

    @Test
    fun phpPlpRoundTrip() {
        val (cpu, _) = cpuFor(0x38, 0x08, 0x18, 0x28) // SEC; PHP; CLC; PLP
        cpu.stepOk(); cpu.stepOk(); cpu.stepOk(); cpu.stepOk()
        assertTrue(cpu.p and Cpu6502.FLAG_C != 0)
    }

    @Test
    fun stackUnderflowFault() {
        val (cpu, _) = cpuFor(0xA9, 0x33, 0x68) // LDA #$33; PLA (empty stack)
        cpu.stepOk()
        val r = cpu.step()
        assertTrue("expected StackFault but was $r", r is Cpu6502.StepResult.StackFault)
        val fault = r as Cpu6502.StepResult.StackFault
        assertTrue(fault.reason.contains("underflow"))
        assertEquals(0x33, cpu.a) // not executed: A untouched
    }

    @Test
    fun stackOverflowFaultOn257thPush() {
        val (cpu, _) = cpuFor(*IntArray(257) { 0x48 }) // 257 x PHA
        repeat(256) { cpu.stepOk() }
        val r = cpu.step()
        assertTrue("expected StackFault but was $r", r is Cpu6502.StepResult.StackFault)
        assertTrue((r as Cpu6502.StepResult.StackFault).reason.contains("overflow"))
    }

    // ---------- illegal opcodes ----------

    @Test
    fun illegalOpcode02ReturnsWithoutExecuting() {
        val (cpu, _) = cpuFor(0xA9, 0x01, 0x02, 0xA9, 0x02) // LDA #$01; $02; LDA #$02
        cpu.stepOk()
        val r = cpu.step()
        assertTrue("expected IllegalOpcode but was $r", r is Cpu6502.StepResult.IllegalOpcode)
        val ill = r as Cpu6502.StepResult.IllegalOpcode
        assertEquals(0x02, ill.opcode)
        assertEquals(0x8002, ill.pc)
        assertEquals(0x01, cpu.a) // nothing after LDA #$01 ran
        assertEquals(2L, cpu.cycles)
    }

    // ---------- jumps ----------

    @Test
    fun jmpAbsolute() {
        val (cpu, _) = cpuFor(0x4C, 0x34, 0x12)
        assertEquals(3, cpu.stepOk())
        assertEquals(0x1234, cpu.pc)
    }

    @Test
    fun jmpIndirectPageWrapBug() {
        val (cpu, bus) = cpuFor(0x6C, 0xFF, 0x10) // JMP ($10FF)
        bus.write(0x10FF, 0x34)
        bus.write(0x1000, 0x12) // hardware bug: high byte from $1000...
        bus.write(0x1100, 0x99) // ...NOT from $1100
        assertEquals(5, cpu.stepOk())
        assertEquals(0x1234, cpu.pc)
    }

    @Test
    fun brkPushesPcPlus2AndJumpsToIrqVector() {
        val (cpu, bus) = cpuFor(0x00) // BRK at $8000
        bus.prg[0x7FFE] = 0x00
        bus.prg[0x7FFF] = 0x81.toByte() // IRQ vector -> $8100
        assertEquals(7, cpu.stepOk())
        assertEquals(0x8100, cpu.pc)
        assertTrue(cpu.p and Cpu6502.FLAG_I != 0)
        assertEquals(0x80, bus.read(0x1FD)) // PCH of $8002
        assertEquals(0x02, bus.read(0x1FC)) // PCL of $8002
        assertEquals(0x30, bus.read(0x1FB) and 0x30) // B and U set in pushed P
    }

    // ---------- interrupts ----------

    @Test
    fun nmiPushesPcAndJumpsToNmiVector() {
        val (cpu, bus) = cpuFor(0xA9, 0x42, 0xEA) // LDA #$42; NOP
        bus.prg[0x7FFA] = 0x00
        bus.prg[0x7FFB] = 0x90.toByte() // NMI vector -> $9000
        cpu.p = cpu.p and Cpu6502.FLAG_I.inv() // clear I so NMI is taken
        cpu.stepOk() // pc now $8002
        cpu.nmi()
        assertEquals(0x9000, cpu.pc)
        assertTrue(cpu.p and Cpu6502.FLAG_I != 0)
        assertEquals(0x80, bus.read(0x1FD)) // PCH
        assertEquals(0x02, bus.read(0x1FC)) // PCL
        val status = bus.read(0x1FB)
        assertEquals(0, status and Cpu6502.FLAG_B) // B clear on interrupt push
        assertTrue(status and Cpu6502.FLAG_U != 0)
    }

    @Test
    fun nmiIgnoredWhenIFlagSet() {
        val (cpu, _) = cpuFor(0xEA)
        assertTrue(cpu.p and Cpu6502.FLAG_I != 0) // reset() sets I
        cpu.nmi()
        assertEquals(0x8000, cpu.pc)
    }

    @Test
    fun irqJumpsToIrqVector() {
        val (cpu, bus) = cpuFor(0xEA)
        bus.prg[0x7FFE] = 0x00
        bus.prg[0x7FFF] = 0xA0.toByte() // IRQ vector -> $A000
        cpu.p = cpu.p and Cpu6502.FLAG_I.inv()
        cpu.irq()
        assertEquals(0xA000, cpu.pc)
        assertTrue(cpu.p and Cpu6502.FLAG_I != 0)
    }

    // ---------- reset ----------

    @Test
    fun resetLoadsPcFromResetVector() {
        val (cpu, _) = cpuFor(resetVector = 0x8123)
        assertEquals(0x8123, cpu.pc)
        assertEquals(0xFD, cpu.sp)
        assertEquals(0x24, cpu.p)
        assertEquals(0L, cpu.cycles)
    }

    // ---------- StubBus ----------

    @Test
    fun stubBusRomWriteAppendsFault() {
        val bus = StubBus(ByteArray(16384), true, ByteArray(8192))
        bus.write(0x8000, 0xFF)
        assertTrue(bus.faults.any { it.contains("ROM_WRITE") && it.contains("8000") })
        assertEquals(1, bus.faults.size)
    }

    @Test
    fun stubBusUnmappedAccessAppendsFault() {
        val bus = StubBus(ByteArray(16384), true, ByteArray(8192))
        assertEquals(0, bus.read(0x4020))
        assertTrue(bus.faults.any { it.contains("UNMAPPED_READ") && it.contains("4020") })
        bus.write(0x5FFF, 0x01)
        assertTrue(bus.faults.any { it.contains("UNMAPPED_WRITE") && it.contains("5fff") })
    }

    @Test
    fun stubBusRamMirroring() {
        val bus = StubBus(ByteArray(16384), true, ByteArray(8192))
        bus.write(0x0001, 0x5A)
        assertEquals(0x5A, bus.read(0x0001))
        assertEquals(0x5A, bus.read(0x0801))
        assertEquals(0x5A, bus.read(0x1001))
        assertEquals(0x5A, bus.read(0x1801))
    }

    @Test
    fun stubBusVblankBitSetAndClearedByRead() {
        val bus = StubBus(ByteArray(16384), true, ByteArray(8192))
        assertEquals(0, bus.read(0x2002) and 0x80) // frame start: no vblank
        bus.tick(27600) // into the last ~2273 cycles of the frame
        assertTrue(bus.read(0x2002) and 0x80 != 0) // vblank bit high
        assertEquals(0, bus.read(0x2002) and 0x80) // reading $2002 cleared it
        bus.tick(3000) // wraps past frame end, out of vblank
        assertEquals(0, bus.read(0x2002) and 0x80)
        bus.tick(27000) // back into vblank: re-armed
        assertTrue(bus.read(0x2002) and 0x80 != 0)
    }

    @Test
    fun stubBusPpuctrlAndPpumaskRecorded() {
        val bus = StubBus(ByteArray(16384), true, ByteArray(8192))
        bus.write(0x2000, 0x90)
        bus.write(0x2001, 0x1E)
        assertEquals(0x90, bus.ppuctrl)
        assertEquals(0x1E, bus.ppumask)
    }

    @Test
    fun stubBusPpuAddrLatchAndIncrementModes() {
        // Real hardware buffers $2007 reads: the first read after setting the
        // address returns the stale buffer, the second returns the value.
        // (Palette reads are the exception — they return immediately.)
        val bus = StubBus(ByteArray(16384), true, ByteArray(8192))
        bus.write(0x2000, 0x00) // bit 2 clear -> +1
        bus.write(0x2006, 0x20); bus.write(0x2006, 0x00) // ppuAddr = $2000
        bus.write(0x2007, 0xAB) // -> $2001
        bus.write(0x2006, 0x20); bus.write(0x2006, 0x00)
        bus.read(0x2007) // prime the read buffer
        assertEquals(0xAB, bus.read(0x2007))
        bus.write(0x2000, 0x04) // bit 2 set -> +32
        bus.write(0x2006, 0x21); bus.write(0x2006, 0x00) // ppuAddr = $2100
        bus.write(0x2007, 0xCD) // stored at $2100; ppuAddr -> $2120
        bus.write(0x2006, 0x21); bus.write(0x2006, 0x00) // ppuAddr = $2100
        bus.read(0x2007) // prime the read buffer
        assertEquals(0xCD, bus.read(0x2007))
        // prove the +32 step: two writes land 32 apart
        bus.write(0x2006, 0x21); bus.write(0x2006, 0x00) // $2100
        bus.write(0x2007, 0x11) // at $2100 -> $2120
        bus.write(0x2007, 0x22) // at $2120 -> $2140
        bus.write(0x2006, 0x21); bus.write(0x2006, 0x20) // $2120
        bus.read(0x2007) // prime the read buffer
        assertEquals(0x22, bus.read(0x2007))
    }

    @Test
    fun stubBusOamDmaCounted() {
        val bus = StubBus(ByteArray(16384), true, ByteArray(8192))
        bus.write(0x4014, 0x02)
        bus.write(0x4014, 0x02)
        assertEquals(2, bus.oamDmaCount)
        assertTrue(bus.faults.isEmpty())
    }
}
