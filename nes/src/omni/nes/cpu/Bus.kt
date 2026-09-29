package omni.nes.cpu

import omni.nes.ppu.Ppu2C02
import omni.nes.rom.Mirroring

/**
 * Clean-room NES CPU bus abstraction + headless validation bus.
 *
 * All code here is original. Address map layout and register semantics are
 * long-published NES hardware facts; no Nintendo code or assets are used.
 */

/** Minimal bus the [Cpu6502] talks to. Addresses 0..0xFFFF, values 0..0xFF. */
interface Bus {
    fun read(addr: Int): Int
    fun write(addr: Int, value: Int)
    /** Called by the CPU after every executed instruction. Default: no-op. */
    fun tick(cycles: Int) {}
}

/**
 * Headless NES bus for the hardware-truth validator.
 *
 * Memory map:
 * - $0000-$1FFF: 2KB RAM, mirrored every $800 through $1FFF.
 * - $2000-$3FFF: PPU registers ($2000-$2007) mirrored every 8 bytes, backed
 *   by a real [Ppu2C02]: loopy scroll registers, $2007 buffered reads,
 *   nametable mirroring from the cartridge header, CHR ROM/RAM, OAM, palette
 *   RAM, scanline-stepped vblank/NMI timing (one frame = 29780 CPU cycles).
 *   $2002 bit 7 is the vblank flag (set at scanline 241, cleared on read,
 *   along with the $2005/$2006 write latch); bits 6/5 are sprite-0-hit /
 *   sprite-overflow once a frame has rendered.
 * - $4000-$4017: APU/IO stub — writes recorded, reads return 0, never faults.
 *   $4014 (OAM DMA) copies a 256-byte CPU page into OAM and bumps
 *   [oamDmaCount]; the 513-dot DMA stall advances the PPU clock.
 * - $4018-$401F: stub, never faults.
 * - $4020-$5FFF: unmapped — accesses append "UNMAPPED_..." to [faults];
 *   reads return 0, writes are ignored.
 * - $6000-$7FFF: 8KB SRAM stub.
 * - $8000-$FFFF: PRG ROM. If [prgIs16k], $C000-$FFFF mirrors $8000-$BFFF.
 *   WRITES append "ROM_WRITE pc=$xxxx addr=$xxxx" to [faults] — real
 *   cartridges cannot do this, so it catches self-modifying code.
 *
 * The `pc=` in ROM_WRITE faults is best-effort: it tracks the address of the
 * first read after each [tick], which for [Cpu6502] is always the opcode
 * fetch of the instruction performing the write.
 */
class StubBus(
    val prg: ByteArray,
    val prgIs16k: Boolean,
    val chr: ByteArray,
    mirroring: Mirroring = Mirroring.HORIZONTAL,
) : Bus {
    val ram = ByteArray(0x800)
    val sram = ByteArray(0x2000)

    /** The real PPU behind $2000-$2007. Empty [chr] means CHR-RAM. */
    val ppu = Ppu2C02(chr, chr.isEmpty(), mirroring)

    /** Every fault string names the access kind and the hex address. */
    val faults = mutableListOf<String>()

    /** Last value written to $2000. */
    val ppuctrl: Int get() = ppu.ppuctrl
    /** Last value written to $2001. */
    val ppumask: Int get() = ppu.ppumask
    /** Number of $4014 (OAMDMA) writes observed. */
    var oamDmaCount = 0
        private set

    private val apu = IntArray(0x18)
    private var currentPc = 0
    private var afterTick = false

    private fun hex4(v: Int): String = "$" + (v and 0xFFFF).toString(16).padStart(4, '0')

    override fun tick(cycles: Int) {
        ppu.advanceDots(cycles * 3L)
        afterTick = true
    }

    private fun prgIndex(a: Int): Int {
        if (prg.isEmpty()) return -1
        val off = a - 0x8000
        return ((if (prgIs16k) off and 0x3FFF else off) % prg.size + prg.size) % prg.size
    }

    override fun read(addr: Int): Int {
        val a = addr and 0xFFFF
        if (afterTick) {
            currentPc = a // first read after tick() is the opcode fetch
            afterTick = false
        }
        return when {
            a < 0x2000 -> ram[a and 0x07FF].toInt() and 0xFF
            a < 0x4000 -> ppu.readReg(a and 0x7)
            a < 0x4018 -> 0 // APU/IO stub reads
            a < 0x4020 -> 0 // $4018-$401F stub reads
            a < 0x6000 -> {
                faults.add("UNMAPPED_READ addr=${hex4(a)}")
                0
            }
            a < 0x8000 -> sram[a - 0x6000].toInt() and 0xFF
            else -> {
                val i = prgIndex(a)
                if (i < 0) 0 else prg[i].toInt() and 0xFF
            }
        }
    }

    override fun write(addr: Int, value: Int) {
        val a = addr and 0xFFFF
        val v = value and 0xFF
        when {
            a < 0x2000 -> ram[a and 0x07FF] = v.toByte()
            a < 0x4000 -> ppu.writeReg(a and 0x7, v)
            a == 0x4014 -> {
                oamDmaCount++
                ppu.oamDma(v) { src -> read(src) }
            }
            a < 0x4018 -> apu[a - 0x4000] = v // APU/IO stub: recorded, never faults
            a < 0x4020 -> { /* $4018-$401F stub */ }
            a < 0x6000 -> faults.add("UNMAPPED_WRITE addr=${hex4(a)}")
            a < 0x8000 -> sram[a - 0x6000] = v.toByte()
            else -> faults.add("ROM_WRITE pc=${hex4(currentPc)} addr=${hex4(a)}")
        }
    }
}
