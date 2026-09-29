package omni.nes.ppu

import omni.nes.cpu.StubBus
import omni.nes.rom.Mirroring
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * Destructive QA for the PPU register interface: random $2000-$2007
 * writes/reads at random cycle positions, OAM DMA abuse, mid-frame scroll
 * changes, CHR-RAM games. The bar is 0 crashes; invariants are checked
 * after every batch.
 */
class PpuFuzzTest {
    private fun checkInvariants(bus: StubBus, tag: String) {
        val p = bus.ppu
        val s = p.readReg(2)
        assertTrue("$tag: \$2002 out of range", s in 0..0xFF)
        assertTrue("$tag: oamAddr out of range", p.oamAddr in 0..0xFF)
        assertTrue("$tag: ppuctrl out of range", p.ppuctrl in 0..0xFF)
        assertTrue("$tag: ppumask out of range", p.ppumask in 0..0xFF)
        for (c in p.framebuffer) {
            assertTrue("$tag: bad pixel $c", c in 0..0xFFFFFF)
        }
    }

    @Test fun fuzzRegisterInterfaceNoCrashes() {
        val rnd = Random(0x1F3A5C7D)
        val bus = StubBus(ByteArray(16384), true, ByteArray(8192), Mirroring.VERTICAL)
        repeat(1500) { i ->
            when (rnd.nextInt(10)) {
                in 0..4 -> bus.write(0x2000 + rnd.nextInt(8), rnd.nextInt(256))
                5 -> bus.read(0x2000 + rnd.nextInt(8))
                6 -> bus.tick(rnd.nextInt(4000))
                7 -> {
                    // OAM DMA abuse: random page, random OAMADDR.
                    bus.write(0x2003, rnd.nextInt(256))
                    bus.write(0x4014, rnd.nextInt(256))
                }
                8 -> {
                    // Mid-frame scroll change, then force a render.
                    bus.write(0x2005, rnd.nextInt(256))
                    bus.write(0x2005, rnd.nextInt(256))
                    bus.tick(rnd.nextInt(30000))
                    bus.ppu.takeFrame()
                }
                else -> {
                    // $2006/$2007 walk over the whole VRAM range.
                    val addr = rnd.nextInt(0x4000)
                    bus.write(0x2006, (addr ushr 8) and 0xFF)
                    bus.write(0x2006, addr and 0xFF)
                    if (rnd.nextBoolean()) bus.write(0x2007, rnd.nextInt(256))
                    else bus.read(0x2007)
                }
            }
            if (i % 100 == 0) checkInvariants(bus, "iter $i")
        }
        checkInvariants(bus, "final")
        // The PPU still renders a coherent frame afterwards.
        bus.tick(100000)
        assertTrue(bus.ppu.takeFrame())
        checkInvariants(bus, "post-render")
    }

    @Test fun fuzzChrRamGameNoCrashes() {
        // CHR-RAM game: $2007 writes anywhere in $0000-$3FFF must be safe.
        val rnd = Random(0x0C4A22)
        val bus = StubBus(ByteArray(16384), true, ByteArray(0), Mirroring.HORIZONTAL)
        repeat(1500) {
            val addr = rnd.nextInt(0x4000)
            bus.write(0x2006, (addr ushr 8) and 0xFF)
            bus.write(0x2006, addr and 0xFF)
            bus.write(0x2007, rnd.nextInt(256))
            if (it % 3 == 0) bus.read(0x2007)
            if (it % 7 == 0) bus.tick(rnd.nextInt(5000))
        }
        // CHR-RAM round-trips through the buffered read path.
        bus.write(0x2006, 0x0F); bus.write(0x2006, 0xF0)
        bus.write(0x2007, 0x5A)
        bus.write(0x2006, 0x0F); bus.write(0x2006, 0xF0)
        bus.read(0x2007)
        assertEquals(0x5A, bus.read(0x2007))
        checkInvariants(bus, "chr-ram")
    }

    @Test fun fuzzDmaFromWeirdPagesNoCrashes() {
        val rnd = Random(7)
        val bus = StubBus(ByteArray(16384), true, ByteArray(8192))
        // Fill SRAM and RAM with junk so DMA reads something everywhere.
        for (i in 0 until 0x800) bus.write(i, rnd.nextInt(256))
        for (i in 0 until 0x2000) bus.write(0x6000 + i, rnd.nextInt(256))
        val pages = listOf(0x00, 0x02, 0x06, 0x60, 0x7F, 0x80, 0xC0, 0xFF)
        repeat(200) {
            bus.write(0x2003, rnd.nextInt(256))
            bus.write(0x4014, pages[rnd.nextInt(pages.size)])
            bus.tick(rnd.nextInt(2000))
        }
        checkInvariants(bus, "dma-weird")
        assertEquals(200, bus.oamDmaCount)
    }

    @Test fun fuzzRapidVblankPolling() {
        // Hammer $2002 + takeNmi/takeFrame like a tight game loop would.
        val bus = StubBus(ByteArray(16384), true, ByteArray(8192))
        bus.write(0x2000, 0x80)
        bus.write(0x2001, 0x1E)
        var frames = 0
        var nmis = 0
        repeat(20000) {
            bus.tick(7)
            bus.read(0x2002)
            if (bus.ppu.takeFrame()) frames++
            if (bus.ppu.takeNmi()) nmis++
        }
        // 140k cycles / 29780 = ~4.7 frames; each frame latches exactly one NMI.
        assertTrue("frames=$frames", frames in 3..6)
        assertEquals(frames, nmis)
        checkInvariants(bus, "rapid-poll")
    }
}
