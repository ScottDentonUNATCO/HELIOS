package omni.nes.validate

import omni.nes.cpu.AddrMode
import omni.nes.cpu.Cpu6502
import omni.nes.cpu.StubBus
import omni.nes.rom.Disassembler
import omni.nes.rom.INes
import omni.nes.rom.INesError
import omni.nes.rom.Mirroring

/**
 * OMNI Phase 1 NES "hardware truth" gate.
 *
 * All code here is original clean-room Kotlin. The hardware facts it checks
 * (iNES layout, 6502 opcode semantics, PPU register behavior, NMI timing) are
 * long-published and reused from the existing cpu/rom packages; nothing is
 * copied from any emulator or ROM.
 *
 * Pipeline: header parse -> vector sanity -> reachability sweep from the
 * vectors -> illegal-opcode gate -> const-propagation mini-interpreter
 * (sprite overflow + palette timing) -> headless boot on [StubBus] ->
 * NMI-enabled check.
 */
enum class Severity { FAIL, WARN, INFO }

/** One validator finding; [ruleId] is the stable machine-readable id. */
data class Finding(val severity: Severity, val ruleId: String, val message: String)

data class ValidatorConfig(
    val strictIllegalOpcodes: Boolean = true,
    val requireNmi: Boolean = true,
    val checkSpriteOverflow: Boolean = true,
    val checkPaletteTiming: Boolean = true,
    val bootCycleBudget: Long = 300_000L,
    val nmiFrameCycles: Int = 29780,
)

data class ValidationReport(val findings: List<Finding>) {
    val passed: Boolean get() = findings.none { it.severity == Severity.FAIL }
    fun fails(): List<Finding> = findings.filter { it.severity == Severity.FAIL }
    fun warns(): List<Finding> = findings.filter { it.severity == Severity.WARN }
}

private fun hex2(v: Int) = "$" + (v and 0xFF).toString(16).uppercase().padStart(2, '0')
private fun hex4(v: Int) = "$" + (v and 0xFFFF).toString(16).uppercase().padStart(4, '0')

/**
 * Opcodes that deliberately transfer control. If the PC leaves the PRG window
 * right after one of these, it was an intentional jump (e.g. executing code
 * copied to RAM) — not a fall off the end of the ROM.
 */
private val CONTROL_FLOW_OPS = setOf(
    "JMP", "JSR", "RTS", "RTI", "BRK",
    "BPL", "BMI", "BVC", "BVS", "BCC", "BCS", "BNE", "BEQ",
)

class NesValidator(val config: ValidatorConfig = ValidatorConfig()) {

    fun validate(romBytes: ByteArray): ValidationReport {
        val findings = mutableListOf<Finding>()
        val infos = mutableListOf<Finding>()
        fun fail(ruleId: String, message: String) {
            findings.add(Finding(Severity.FAIL, ruleId, message))
        }
        fun warn(ruleId: String, message: String) {
            findings.add(Finding(Severity.WARN, ruleId, message))
        }
        fun info(ruleId: String, message: String) {
            infos.add(Finding(Severity.INFO, ruleId, message))
        }

        // ---- a. header ----
        val prg: ByteArray
        val chr: ByteArray
        val mirroring: Mirroring
        try {
            val rom = INes.parse(romBytes)
            prg = rom.prg
            chr = rom.chr
            mirroring = rom.mirroring
        } catch (e: INesError) {
            val ruleId = e.reason.substringBefore(":")
            return ValidationReport(listOf(Finding(Severity.FAIL, ruleId, e.reason)))
        }

        val prgBase = if (prg.size == 16384) 0xC000 else 0x8000
        val prgEnd = prgBase + prg.size
        fun inPrg(addr: Int) = addr in prgBase until prgEnd
        fun readWord(addr: Int): Int {
            val i = addr - prgBase
            return (prg[i].toInt() and 0xFF) or ((prg[i + 1].toInt() and 0xFF) shl 8)
        }
        val nmiVec = readWord(0xFFFA)
        val resetVec = readWord(0xFFFC)
        val irqVec = readWord(0xFFFE)
        val prgWindow = "[${hex4(prgBase)},${hex4(prgEnd)})"

        // ---- b. vectors ----
        if (!inPrg(nmiVec)) {
            fail("VEC_NMI_IN_PRG", "NMI vector ${hex4(nmiVec)} points outside PRG $prgWindow")
        }
        if (!inPrg(resetVec)) {
            fail("VEC_RESET_IN_PRG", "RESET vector ${hex4(resetVec)} points outside PRG $prgWindow")
        }
        if (!inPrg(irqVec)) {
            if (irqVec == 0x0000) {
                warn("VEC_IRQ_IN_PRG", "IRQ vector \$0000 is conventionally unused; wire it up if the game uses IRQs")
            } else {
                fail("VEC_IRQ_IN_PRG", "IRQ vector ${hex4(irqVec)} points outside PRG $prgWindow")
            }
        }

        // ---- c. reachability sweep from the in-range vectors ----
        val entries = listOf(nmiVec, resetVec, irqVec).filter(::inPrg)
        val sweep = Disassembler.reachable(prg, prgBase, entries)

        // ---- d. illegal opcodes on reachable paths ----
        for ((addr, op) in sweep.illegal.toSortedMap()) {
            val msg = "illegal opcode ${hex2(op)} at ${hex4(addr)} (reachable from vectors)"
            if (config.strictIllegalOpcodes) fail("ILLEGAL_OPCODE", msg)
            else warn("ILLEGAL_OPCODE", msg)
        }

        // ---- e. empty NMI handler ----
        if (inPrg(nmiVec)) {
            val first = Disassembler.decode(prg, prgBase, nmiVec)
            if (first != null && first.mnemonic == "RTI") {
                warn("NMI_HANDLER_EMPTY", "NMI handler at ${hex4(nmiVec)} is a bare RTI; it does no work")
            }
        }

        // Addresses statically reachable from the NMI vector (vblank work).
        val nmiReachable: Set<Int> =
            if (inPrg(nmiVec)) Disassembler.reachable(prg, prgBase, listOf(nmiVec)).code.keys
            else emptySet()

        // ---- f/g/h. const-propagation mini-interpreter over the swept code ----
        //
        // Cheap heuristic: a single linear pass over sweep.code sorted by
        // address. Tracked state resets at the first entry and at every
        // address discontinuity (the previous instruction in sorted order is
        // not the linear predecessor). Fallthroughs of branches and jumps
        // keep state, which is exact for the fallthrough path.
        //
        // Limitation: control-flow merge points (branch targets, loop-back
        // edges, JSR/JMP targets) are NOT re-analyzed per incoming path, so a
        // const seen here can be stale when the instruction is reached via a
        // different path, and consts established only along a non-fallthrough
        // path are missed. Unknowns are null, and nulls only suppress
        // findings, so the pass degrades to "no finding" rather than inventing
        // false ones. Good enough for straight-line init and NMI-handler
        // code, which is what the sprite/palette rules target.
        var ra: Int? = null
        var rx: Int? = null
        var ry: Int? = null
        var latchHi: Int? = null // pending $2006 high byte
        var ppuAddr: Int? = null // tracked PPU address from $2006 double-writes
        var lastPpumask: Int? = null
        var ppuctrlConst: Int? = null
        var oamIndex: Int? = null // pending $2003 index (NMI-reachable code only)
        val sprites = mutableListOf<Pair<Int, Int>>() // (sprite number, y)
        var dmaInNmi = false
        var dynamicYInNmi = false
        var paletteUnknownPpumask = false

        fun resetConsts() {
            ra = null; rx = null; ry = null
            latchHi = null; ppuAddr = null
            lastPpumask = null; ppuctrlConst = null; oamIndex = null
        }

        val storeOps = setOf("STA", "STX", "STY")
        var prevEnd = -1
        for ((addr, d) in sweep.code.toSortedMap()) {
            if (addr != prevEnd) resetConsts()
            prevEnd = addr + d.length
            val inNmi = addr in nmiReachable

            // register const tracking
            when (d.mnemonic) {
                "LDA" -> ra = if (d.mode == AddrMode.IMMEDIATE) d.operand else null
                "LDX" -> rx = if (d.mode == AddrMode.IMMEDIATE) d.operand else null
                "LDY" -> ry = if (d.mode == AddrMode.IMMEDIATE) d.operand else null
                "TAX" -> rx = ra
                "TAY" -> ry = ra
                "TXA" -> ra = rx
                "TYA" -> ra = ry
                "TSX" -> rx = null
                "INX" -> rx = rx?.let { (it + 1) and 0xFF }
                "DEX" -> rx = rx?.let { (it - 1) and 0xFF }
                "INY" -> ry = ry?.let { (it + 1) and 0xFF }
                "DEY" -> ry = ry?.let { (it - 1) and 0xFF }
                "PLA" -> ra = null
                "ADC", "SBC", "AND", "ORA", "EOR" -> ra = null
                "ASL", "LSR", "ROL", "ROR" -> if (d.mode == AddrMode.ACCUMULATOR) ra = null
            }
            // $2002 reads reset the $2006 write latch on hardware.
            if (d.mode == AddrMode.ABSOLUTE && d.operand == 0x2002 &&
                (d.mnemonic == "BIT" || d.mnemonic == "LDA" || d.mnemonic == "LDX" || d.mnemonic == "LDY")
            ) {
                latchHi = null
            }

            // PPU-register stores (exact addresses only; mirrors ignored).
            if (d.mnemonic in storeOps && (d.mode == AddrMode.ABSOLUTE || d.mode == AddrMode.ZERO_PAGE)) {
                val v: Int? = when (d.mnemonic) {
                    "STA" -> ra
                    "STX" -> rx
                    else -> ry
                }
                when (d.operand) {
                    0x2000 -> ppuctrlConst = v
                    0x2001 -> lastPpumask = v
                    0x2003 -> oamIndex = if (inNmi) v else null
                    0x2004 -> {
                        if (v != null) {
                            val idx = oamIndex
                            if (idx != null && inNmi) sprites.add(Pair(idx / 4, v))
                        } else if (inNmi) {
                            dynamicYInNmi = true
                        }
                    }
                    0x2006 -> {
                        if (v != null) {
                            val hi = latchHi
                            if (hi == null) {
                                latchHi = v
                            } else {
                                ppuAddr = hi * 256 + v
                                latchHi = null
                            }
                        } else {
                            latchHi = null
                            ppuAddr = null
                        }
                    }
                    0x2007 -> {
                        val pa = ppuAddr
                        if (pa != null && pa in 0x3F00..0x3F1F && config.checkPaletteTiming) {
                            val pm = lastPpumask
                            when {
                                inNmi -> { /* palette upload during vblank: legal */ }
                                pm != null && (pm and 0x18) != 0 -> fail(
                                    "PALETTE_TIMING",
                                    "PALETTE_TIMING: palette write at ${hex4(addr)} outside vblank " +
                                        "with rendering enabled (ppumask=${hex2(pm)})",
                                )
                                // Unknown $2001 state (e.g. forced blank set in a different
                                // subroutine, or $2001 never written): not provably wrong,
                                // so it must not fail. Flagged once as INFO below.
                                pm == null -> paletteUnknownPpumask = true
                            }
                        }
                        if (pa != null) {
                            val pctl = ppuctrlConst
                            val step = if (pctl != null && (pctl and 0x04) != 0) 32 else 1
                            ppuAddr = (pa + step) and 0xFFFF
                        }
                    }
                    0x4014 -> if (inNmi) dmaInNmi = true
                }
            }
        }

        // ---- g. sprite overflow (hardware shows max 8 sprites per scanline) ----
        if (config.checkSpriteOverflow) {
            val buckets = mutableMapOf<Int, MutableList<Int>>()
            for ((num, y) in sprites) buckets.getOrPut(y / 8) { mutableListOf() }.add(num)
            for ((bucket, members) in buckets.toSortedMap()) {
                if (members.size > 8) {
                    fail(
                        "SPRITE_OVERFLOW",
                        "SPRITE_OVERFLOW: ${members.size} sprites share scanlines near " +
                            "Y=${hex2(bucket * 8)} (hardware shows max 8 per scanline)",
                    )
                }
            }
            if (dmaInNmi || dynamicYInNmi) {
                info(
                    "SPRITE_OVERFLOW",
                    "sprite overflow not statically decidable (DMA or dynamic Y positions); " +
                        "runtime flicker still possible",
                )
            }
        }

        if (paletteUnknownPpumask && config.checkPaletteTiming) {
            info(
                "PALETTE_TIMING",
                "palette write(s) outside NMI with untracked \$2001 state; " +
                    "vblank timing not statically verifiable (not provably wrong)",
            )
        }

        // ---- j. headless boot on the StubBus (before the NMI check, which reads its results) ----
        val bus = StubBus(prg, prg.size == 16384, chr, mirroring)
        val cpu = Cpu6502(bus)
        cpu.reset()
        var frames = 0
        var frameCountdown = config.nmiFrameCycles
        var steps = 0
        var halted = false
        while (!halted && cpu.cycles < config.bootCycleBudget && steps < 4_000_000) {
            steps++
            val prevPc = cpu.pc
            when (val r = cpu.step()) {
                is Cpu6502.StepResult.Ok -> {
                    // Did the PC just leave the PRG window? A deliberate jump
                    // (JMP/branch/...) is legal (e.g. running code copied to
                    // RAM); running off the end of the ROM is not — on
                    // hardware that executes whatever the RAM powers up with.
                    // Judged only at the moment of leaving: once the PC is
                    // already outside (a deliberate RAM jump), later steps
                    // are not re-judged. A null decode (instruction straddling
                    // the PRG end) is also a fail: the bytes aren't all there.
                    if (cpu.pc !in prgBase until prgEnd && prevPc in prgBase until prgEnd) {
                        val d = Disassembler.decode(prg, prgBase, prevPc)
                        if (d == null || d.mnemonic !in CONTROL_FLOW_OPS) {
                            fail(
                                "BOOT_PC_ESCAPED_PRG",
                                "PC escaped PRG ROM to ${hex4(cpu.pc)} during boot " +
                                    "(fell off the end of ${hex4(prevPc)})",
                            )
                            halted = true
                        }
                    }
                    frameCountdown -= r.cycles
                    if (frameCountdown <= 0) {
                        frameCountdown += config.nmiFrameCycles
                        if (bus.ppuctrl and 0x80 != 0) {
                            // Count only NMIs that actually took effect: nmi()
                            // is a no-op while the I flag is set, and crediting
                            // those would fake the "NMI ran" evidence.
                            val before = cpu.pc
                            cpu.nmi()
                            if (cpu.pc != before) frames++
                        }
                    }
                }
                is Cpu6502.StepResult.IllegalOpcode -> {
                    fail("BOOT_ILLEGAL_OPCODE", "illegal opcode ${hex2(r.opcode)} at ${hex4(r.pc)} during boot")
                    halted = true
                }
                is Cpu6502.StepResult.StackFault -> {
                    fail("BOOT_STACK_FAULT", "stack fault during boot: ${r.reason} at ${hex4(r.pc)}")
                    halted = true
                }
            }
        }
        if (bus.faults.isNotEmpty()) {
            val f = bus.faults.first()
            val kind = when {
                "ROM_WRITE" in f -> "ROM_WRITE"
                "UNMAPPED" in f -> "UNMAPPED access"
                else -> "bus"
            }
            fail("BOOT_BUS_FAULT", "bus fault during boot ($kind): $f")
        }
        info("BOOT", "boot: ${cpu.cycles} cycles, $frames NMI frames, ppuctrl=${hex2(bus.ppuctrl)}")

        // ---- i. did NMI ever take effect? ----
        // Enabling $2000 bit 7 is not enough: the frame loop must actually run
        // at least once during the boot window. A ROM that sets the bit but
        // never gets an NMI (I flag stuck set, or hung before vblank) is as
        // dead as one that never enables it.
        if (config.requireNmi && frames == 0) {
            fail(
                "NMI_ENABLED",
                "NMI_ENABLED: no NMI frame executed in ${cpu.cycles} boot cycles " +
                    "(ppuctrl=${hex2(bus.ppuctrl)}; the NMI never took effect)",
            )
        }

        return ValidationReport(findings + infos)
    }
}
