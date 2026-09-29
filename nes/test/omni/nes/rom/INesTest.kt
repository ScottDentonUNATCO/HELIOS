package omni.nes.rom

import org.junit.Test
import org.junit.Assert.*

/** Clean-room tests for the iNES parser/builder. JUnit4 only. */
class INesTest {

    private fun iNesFile(prgBanks: Int, chrBanks: Int = 1, f6: Int = 0, f7: Int = 0): ByteArray {
        val h = ByteArray(16)
        h[0] = 0x4E; h[1] = 0x45; h[2] = 0x53; h[3] = 0x1A
        h[4] = prgBanks.toByte(); h[5] = chrBanks.toByte()
        h[6] = f6.toByte(); h[7] = f7.toByte()
        return h + ByteArray(prgBanks * 16384) + ByteArray(chrBanks * 8192)
    }

    @Test fun roundTrip() {
        val prg = ByteArray(16384) { it.toByte() }
        val chr = ByteArray(8192) { (it * 3).toByte() }
        val rom = INes.parse(RomBuilder.build(prg, chr))
        assertArrayEquals(prg, rom.prg)
        assertArrayEquals(chr, rom.chr)
        assertEquals(0, rom.mapper)
        assertEquals(Mirroring.VERTICAL, rom.mirroring)
        assertFalse(rom.battery)
    }

    @Test fun chrRamWhenZeroBanks() {
        val rom = INes.parse(iNesFile(1, chrBanks = 0))
        assertEquals(8192, rom.chr.size)
        assertTrue(rom.chr.all { it == 0.toByte() })
    }

    @Test fun mirroringBit() {
        assertEquals(Mirroring.HORIZONTAL, INes.parse(iNesFile(1, f6 = 0x00)).mirroring)
        assertEquals(Mirroring.VERTICAL, INes.parse(iNesFile(1, f6 = 0x01)).mirroring)
    }

    @Test fun batteryBit() {
        assertTrue(INes.parse(iNesFile(1, f6 = 0x02)).battery)
        assertFalse(INes.parse(iNesFile(1, f6 = 0x00)).battery)
    }
}
