package omni.nes.cpu

/**
 * Clean-room MOS 6502 CPU core for the NES "hardware truth" validator.
 *
 * Original implementation; only opcode numbers, mnemonics, addressing modes
 * and cycle counts are used (long-published hardware facts, shared via
 * [Opcodes]). All 151 official opcodes are implemented. Unofficial/illegal
 * opcodes are NOT executed: [step] returns [StepResult.IllegalOpcode].
 *
 * Notes / deliberate deviations:
 * - Decimal mode: the D flag exists and SED/CLD set/clear it, but ADC/SBC
 *   always compute in binary. This matches the NES 2A03, which lacks the
 *   6502's BCD circuitry.
 * - JMP (indirect) implements the real hardware page-wrap bug: JMP ($xxFF)
 *   fetches the high byte from $xx00 instead of $(xx+1)00.
 * - Stack-depth tracking is stricter than hardware and is a validator aid,
 *   not hardware behavior: each push-type instruction (PHA/PHP/JSR/BRK/NMI/
 *   IRQ) increments a depth counter once, each pop-type instruction
 *   (PLA/PLP/RTI/RTS) decrements it once. Popping at depth 0 is an underflow
 *   fault; pushing past depth 256 is an overflow fault. Real hardware just
 *   wraps the 8-bit stack pointer.
 */
class Cpu6502(val bus: Bus) {
    var a = 0
    var x = 0
    var y = 0
    var sp = 0xFD
    var pc = 0
    var p = 0x24
    var cycles = 0L

    /** Instruction-level push depth; see class docs. Reset by [reset]. */
    private var depth = 0

    companion object {
        const val FLAG_C = 0x01
        const val FLAG_Z = 0x02
        const val FLAG_I = 0x04
        const val FLAG_D = 0x08
        const val FLAG_B = 0x10
        const val FLAG_U = 0x20
        const val FLAG_V = 0x40
        const val FLAG_N = 0x80
    }

    sealed interface StepResult {
        data class Ok(val cycles: Int) : StepResult
        data class IllegalOpcode(val opcode: Int, val pc: Int) : StepResult
        data class StackFault(val reason: String, val pc: Int) : StepResult
    }

    private fun read(addr: Int): Int = bus.read(addr and 0xFFFF) and 0xFF
    private fun write(addr: Int, v: Int) = bus.write(addr and 0xFFFF, v and 0xFF)
    private fun readWord(addr: Int): Int = read(addr) or (read((addr + 1) and 0xFFFF) shl 8)

    private fun setFlag(mask: Int, on: Boolean) {
        p = if (on) p or mask else p and mask.inv()
    }

    private fun setZN(v: Int) {
        setFlag(FLAG_Z, (v and 0xFF) == 0)
        setFlag(FLAG_N, (v and 0x80) != 0)
    }

    /** Raw byte push without depth accounting (used by NMI/IRQ, which return Unit). */
    private fun pushRaw(v: Int) {
        write(0x0100 or sp, v)
        sp = (sp - 1) and 0xFF
    }

    /**
     * One push-type instruction: faults with overflow if depth would exceed
     * 256, otherwise pushes [bytes] in order and increments depth once.
     */
    private fun checkedPushOp(pcNow: Int, vararg bytes: Int): StepResult? {
        if (depth >= 256) {
            return StepResult.StackFault(
                "overflow: stack push depth would exceed 256 (pc=${pcNow.toString(16)})",
                pcNow,
            )
        }
        for (b in bytes) pushRaw(b)
        depth++
        return null
    }

    /**
     * One pop-type instruction: faults with underflow at depth 0, otherwise
     * pulls [n] bytes (low byte first) and decrements depth once.
     */
    private fun checkedPopOp(pcNow: Int, n: Int): Pair<IntArray, StepResult?> {
        if (depth == 0) {
            return Pair(
                IntArray(0),
                StepResult.StackFault(
                    "underflow: stack pop with empty stack (pc=${pcNow.toString(16)})",
                    pcNow,
                ),
            )
        }
        val out = IntArray(n) {
            sp = (sp + 1) and 0xFF // real 6502: SP increments BEFORE the read
            read(0x0100 or sp)
        }
        depth--
        return Pair(out, null)
    }

    fun reset() {
        sp = 0xFD
        p = 0x24 // I flag set, U bit set
        cycles = 0
        depth = 0
        pc = readWord(0xFFFC)
    }

    /**
     * Non-maskable interrupt. Only taken when the I flag is clear: pushes PC
     * (high, low) then P with B clear, sets I, and jumps to the $FFFA vector.
     * The push counts as one push-type op for depth tracking; 7 cycles.
     */
    fun nmi() {
        if (p and FLAG_I != 0) return
        pushRaw((pc ushr 8) and 0xFF)
        pushRaw(pc and 0xFF)
        pushRaw(p or FLAG_U) // B clear
        depth++
        p = p or FLAG_I
        pc = readWord(0xFFFA)
        cycles += 7
        bus.tick(7)
    }

    /** Maskable interrupt, same as [nmi] but through the $FFFE vector. */
    fun irq() {
        if (p and FLAG_I != 0) return
        pushRaw((pc ushr 8) and 0xFF)
        pushRaw(pc and 0xFF)
        pushRaw(p or FLAG_U) // B clear
        depth++
        p = p or FLAG_I
        pc = readWord(0xFFFE)
        cycles += 7
        bus.tick(7)
    }

    private fun doAdc(m: Int) {
        // Binary only; see class docs re: decimal mode on the 2A03.
        val carryIn = if (p and FLAG_C != 0) 1 else 0
        val sum = a + m + carryIn
        val r = sum and 0xFF
        setFlag(FLAG_C, sum > 0xFF)
        setFlag(FLAG_V, ((a xor r) and (m xor r) and 0x80) != 0)
        a = r
        setZN(a)
    }

    private fun doSbc(m: Int) {
        val carryIn = if (p and FLAG_C != 0) 1 else 0
        val diff = a - m - (1 - carryIn)
        val r = diff and 0xFF
        setFlag(FLAG_C, diff >= 0)
        setFlag(FLAG_V, ((a xor r) and (a xor m) and 0x80) != 0)
        a = r
        setZN(a)
    }

    private fun doCmp(reg: Int, m: Int) {
        setFlag(FLAG_C, reg >= m)
        setZN((reg - m) and 0xFF)
    }

    private fun doBit(m: Int) {
        setFlag(FLAG_Z, (a and m) == 0)
        setFlag(FLAG_N, (m and 0x80) != 0)
        setFlag(FLAG_V, (m and 0x40) != 0)
    }

    /**
     * Execute one instruction. Fetch/decode via [Opcodes]; unofficial opcodes
     * return [StepResult.IllegalOpcode] WITHOUT executing. On success the
     * instruction's cycles are accumulated, bus.tick() is called, and
     * [StepResult.Ok] carries the cycle count.
     */
    fun step(): StepResult {
        val opAddr = pc
        val opcode = read(pc)
        pc = (pc + 1) and 0xFFFF
        val info = Opcodes.info(opcode) ?: return StepResult.IllegalOpcode(opcode, opAddr)
        var cyclesUsed = info.cycles

        var effAddr = -1
        var operand = 0
        var crossed = false
        var branchTarget = 0

        when (info.mode) {
            AddrMode.IMPLIED, AddrMode.ACCUMULATOR -> Unit
            AddrMode.IMMEDIATE -> {
                operand = read(pc)
                pc = (pc + 1) and 0xFFFF
            }
            AddrMode.ZERO_PAGE -> {
                effAddr = read(pc)
                pc = (pc + 1) and 0xFFFF
            }
            AddrMode.ZERO_PAGE_X -> {
                effAddr = (read(pc) + x) and 0xFF
                pc = (pc + 1) and 0xFFFF
            }
            AddrMode.ZERO_PAGE_Y -> {
                effAddr = (read(pc) + y) and 0xFF
                pc = (pc + 1) and 0xFFFF
            }
            AddrMode.ABSOLUTE -> {
                effAddr = read(pc) or (read((pc + 1) and 0xFFFF) shl 8)
                pc = (pc + 2) and 0xFFFF
            }
            AddrMode.ABSOLUTE_X -> {
                val base = read(pc) or (read((pc + 1) and 0xFFFF) shl 8)
                effAddr = (base + x) and 0xFFFF
                crossed = (base and 0xFF00) != (effAddr and 0xFF00)
                pc = (pc + 2) and 0xFFFF
            }
            AddrMode.ABSOLUTE_Y -> {
                val base = read(pc) or (read((pc + 1) and 0xFFFF) shl 8)
                effAddr = (base + y) and 0xFFFF
                crossed = (base and 0xFF00) != (effAddr and 0xFF00)
                pc = (pc + 2) and 0xFFFF
            }
            AddrMode.INDEXED_INDIRECT -> {
                val zp = (read(pc) + x) and 0xFF
                pc = (pc + 1) and 0xFFFF
                effAddr = read(zp) or (read((zp + 1) and 0xFF) shl 8)
            }
            AddrMode.INDIRECT_INDEXED -> {
                val zp = read(pc)
                pc = (pc + 1) and 0xFFFF
                val base = read(zp) or (read((zp + 1) and 0xFF) shl 8)
                effAddr = (base + y) and 0xFFFF
                crossed = (base and 0xFF00) != (effAddr and 0xFF00)
            }
            AddrMode.RELATIVE -> {
                val off = read(pc).toByte().toInt()
                pc = (pc + 1) and 0xFFFF
                branchTarget = (pc + off) and 0xFFFF
                crossed = (pc and 0xFF00) != (branchTarget and 0xFF00)
            }
            AddrMode.INDIRECT -> {
                // JMP (addr) only; effAddr holds the pointer.
                effAddr = read(pc) or (read((pc + 1) and 0xFFFF) shl 8)
                pc = (pc + 2) and 0xFFFF
            }
        }

        /** Read the instruction's source operand, applying the page-cross penalty. */
        fun readOperand(): Int {
            val v = if (info.mode == AddrMode.IMMEDIATE) operand else read(effAddr)
            if (info.pageCross && crossed) cyclesUsed++
            return v
        }

        fun doBranch(cond: Boolean) {
            if (cond) {
                pc = branchTarget
                cyclesUsed += 1 + if (crossed) 1 else 0
            }
        }

        fun doShift(kind: String) {
            val isAcc = info.mode == AddrMode.ACCUMULATOR
            val v = if (isAcc) a else read(effAddr)
            val carryIn = if (p and FLAG_C != 0) 1 else 0
            var r = 0
            var carryOut = false
            when (kind) {
                "ASL" -> { carryOut = (v and 0x80) != 0; r = (v shl 1) and 0xFF }
                "LSR" -> { carryOut = (v and 0x01) != 0; r = v ushr 1 }
                "ROL" -> { carryOut = (v and 0x80) != 0; r = ((v shl 1) or carryIn) and 0xFF }
                "ROR" -> { carryOut = (v and 0x01) != 0; r = (v ushr 1) or (carryIn shl 7) }
            }
            setFlag(FLAG_C, carryOut)
            if (isAcc) a = r else write(effAddr, r)
            setZN(r)
        }

        when (info.mnemonic) {
            // Loads
            "LDA" -> { a = readOperand(); setZN(a) }
            "LDX" -> { x = readOperand(); setZN(x) }
            "LDY" -> { y = readOperand(); setZN(y) }
            // Stores
            "STA" -> write(effAddr, a)
            "STX" -> write(effAddr, x)
            "STY" -> write(effAddr, y)
            // Transfers
            "TAX" -> { x = a; setZN(x) }
            "TAY" -> { y = a; setZN(y) }
            "TXA" -> { a = x; setZN(a) }
            "TYA" -> { a = y; setZN(a) }
            "TSX" -> { x = sp; setZN(x) }
            "TXS" -> { sp = x } // no flags; not a push op for depth tracking
            // Increments / decrements
            "INX" -> { x = (x + 1) and 0xFF; setZN(x) }
            "INY" -> { y = (y + 1) and 0xFF; setZN(y) }
            "DEX" -> { x = (x - 1) and 0xFF; setZN(x) }
            "DEY" -> { y = (y - 1) and 0xFF; setZN(y) }
            "INC" -> { val r = (read(effAddr) + 1) and 0xFF; write(effAddr, r); setZN(r) }
            "DEC" -> { val r = (read(effAddr) - 1) and 0xFF; write(effAddr, r); setZN(r) }
            // Arithmetic
            "ADC" -> doAdc(readOperand())
            "SBC" -> doSbc(readOperand())
            // Logic
            "AND" -> { a = a and readOperand(); setZN(a) }
            "ORA" -> { a = a or readOperand(); setZN(a) }
            "EOR" -> { a = a xor readOperand(); setZN(a) }
            // Compares
            "CMP" -> doCmp(a, readOperand())
            "CPX" -> doCmp(x, readOperand())
            "CPY" -> doCmp(y, readOperand())
            "BIT" -> doBit(readOperand())
            // Shifts / rotates
            "ASL" -> doShift("ASL")
            "LSR" -> doShift("LSR")
            "ROL" -> doShift("ROL")
            "ROR" -> doShift("ROR")
            // Branches
            "BPL" -> doBranch((p and FLAG_N) == 0)
            "BMI" -> doBranch((p and FLAG_N) != 0)
            "BVC" -> doBranch((p and FLAG_V) == 0)
            "BVS" -> doBranch((p and FLAG_V) != 0)
            "BCC" -> doBranch((p and FLAG_C) == 0)
            "BCS" -> doBranch((p and FLAG_C) != 0)
            "BNE" -> doBranch((p and FLAG_Z) == 0)
            "BEQ" -> doBranch((p and FLAG_Z) != 0)
            // Jumps / calls
            "JMP" -> {
                pc = if (info.mode == AddrMode.ABSOLUTE) {
                    effAddr
                } else {
                    // Indirect: real hardware page-wrap bug — JMP ($xxFF)
                    // reads the high byte from $xx00.
                    val lo = read(effAddr)
                    val hi = read((effAddr and 0xFF00) or ((effAddr + 1) and 0xFF))
                    lo or (hi shl 8)
                }
            }
            "JSR" -> {
                val ret = (pc - 1) and 0xFFFF
                checkedPushOp(opAddr, (ret ushr 8) and 0xFF, ret and 0xFF)?.let { return it }
                pc = effAddr
            }
            "RTS" -> {
                val (bytes, fault) = checkedPopOp(opAddr, 2)
                fault?.let { return it }
                pc = ((bytes[0] or (bytes[1] shl 8)) + 1) and 0xFFFF
            }
            "RTI" -> {
                val (bytes, fault) = checkedPopOp(opAddr, 3)
                fault?.let { return it }
                p = (bytes[0] and 0xEF) or FLAG_U // B ignored on pull, U forced
                pc = bytes[1] or (bytes[2] shl 8)
            }
            "BRK" -> {
                val ret = (opAddr + 2) and 0xFFFF
                checkedPushOp(opAddr, (ret ushr 8) and 0xFF, ret and 0xFF, p or FLAG_B or FLAG_U)
                    ?.let { return it }
                p = p or FLAG_I
                pc = readWord(0xFFFE)
            }
            // Stack
            "PHA" -> { checkedPushOp(opAddr, a)?.let { return it } }
            "PHP" -> { checkedPushOp(opAddr, p or FLAG_B or FLAG_U)?.let { return it } }
            "PLA" -> {
                val (bytes, fault) = checkedPopOp(opAddr, 1)
                fault?.let { return it }
                a = bytes[0]
                setZN(a)
            }
            "PLP" -> {
                val (bytes, fault) = checkedPopOp(opAddr, 1)
                fault?.let { return it }
                p = (bytes[0] and 0xEF) or FLAG_U
            }
            // Flag ops
            "CLC" -> setFlag(FLAG_C, false)
            "SEC" -> setFlag(FLAG_C, true)
            "CLI" -> setFlag(FLAG_I, false)
            "SEI" -> setFlag(FLAG_I, true)
            "CLD" -> setFlag(FLAG_D, false)
            "SED" -> setFlag(FLAG_D, true)
            "CLV" -> setFlag(FLAG_V, false)
            "NOP" -> Unit
            else -> return StepResult.IllegalOpcode(opcode, opAddr) // unreachable: table is official-only
        }

        cycles += cyclesUsed
        bus.tick(cyclesUsed)
        return StepResult.Ok(cyclesUsed)
    }
}
