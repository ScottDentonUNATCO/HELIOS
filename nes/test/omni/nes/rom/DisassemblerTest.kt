package omni.nes.rom

import org.junit.Test
import org.junit.Assert.*

/** Clean-room tests for the reachability-sweep disassembler. JUnit4 only. */
class DisassemblerTest {

    private fun ByteArray.poke(addr: Int, vararg bytes: Int) {
        bytes.forEachIndexed { i, v -> this[addr - 0xC000 + i] = v.toByte() }
    }

    private fun prgWith(init: ByteArray.() -> Unit): ByteArray {
        val prg = ByteArray(16384)
        prg.init()
        return prg
    }

    @Test fun reachableFollowsBranchesAndJsr() {
        val prg = prgWith {
            poke(0xC000, 0xA9, 0x01) // LDA #$01
            poke(0xC002, 0x20, 0x10, 0xC0) // JSR $C010
            poke(0xC005, 0xD0, 0xFE) // BNE $C005 (self)
            poke(0xC007, 0x60) // RTS (branch fallthrough)
            poke(0xC010, 0xA9, 0x02) // LDA #$02 (JSR target)
            poke(0xC012, 0x60) // RTS
            poke(0xC020, 0x02) // $02 sitting in unreachable data
        }
        val s = Disassembler.reachable(prg, 0xC000, listOf(0xC000))
        assertEquals(setOf(0xC000, 0xC002, 0xC005, 0xC007, 0xC010, 0xC012), s.code.keys)
        assertTrue("unreachable data must not be reported, got ${s.illegal}", s.illegal.isEmpty())
    }

    @Test fun stopsAtIndirectJmp() {
        val prg = prgWith {
            poke(0xC000, 0x6C, 0x10, 0xC0) // JMP ($C010): target unknowable statically
            poke(0xC003, 0xA9, 0x01) // LDA #$01 (unreachable fallthrough)
        }
        val s = Disassembler.reachable(prg, 0xC000, listOf(0xC000))
        assertEquals(setOf(0xC000), s.code.keys)
        assertTrue(s.illegal.isEmpty())
    }

    @Test fun illegalRecordedOnReachablePath() {
        val prg = prgWith {
            poke(0xC000, 0xA9, 0x01) // LDA #$01
            poke(0xC002, 0x02) // KIL: illegal, on the reachable path
            poke(0xC003, 0xA9, 0x03) // LDA #$03 (never reached)
        }
        val s = Disassembler.reachable(prg, 0xC000, listOf(0xC000))
        assertEquals(mapOf(0xC002 to 0x02), s.illegal)
        assertEquals(setOf(0xC000), s.code.keys)
    }
}
