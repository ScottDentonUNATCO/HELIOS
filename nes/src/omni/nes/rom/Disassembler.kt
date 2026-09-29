package omni.nes.rom

import omni.nes.cpu.AddrMode
import omni.nes.cpu.Opcodes

/** One decoded instruction. [operand] holds the raw little-endian operand bytes as an Int. */
data class Decoded(
    val address: Int,
    val opcode: Int,
    val mnemonic: String,
    val mode: AddrMode,
    val length: Int,
    val operand: Int,
)

/** Result of a reachability sweep: decoded code, plus illegal opcodes hit on reachable paths. */
data class Sweep(
    val code: Map<Int, Decoded>,
    val illegal: Map<Int, Int>, // address -> opcode byte
)

/**
 * Clean-room 6502 disassembler used by the validator. Decoding is driven by the
 * shared [Opcodes] table so the disassembler can never disagree with the CPU
 * about what is or isn't an official opcode.
 */
object Disassembler {
    /** Null for illegal opcodes and for addresses outside the PRG window. */
    fun decode(prg: ByteArray, prgBase: Int, address: Int): Decoded? {
        val idx = address - prgBase
        if (idx < 0 || idx >= prg.size) return null
        val op = prg[idx].toInt() and 0xFF
        val info = Opcodes.info(op) ?: return null
        if (idx + info.mode.bytes > prg.size) return null
        var operand = 0
        for (i in 1 until info.mode.bytes) {
            operand = operand or ((prg[idx + i].toInt() and 0xFF) shl (8 * (i - 1)))
        }
        return Decoded(address, op, info.mnemonic, info.mode, info.mode.bytes, operand)
    }

    /**
     * Recursive-descent sweep from [entries] (normally the three hardware
     * vectors). Follows branches (both arms), JSR targets (and fallthrough),
     * and absolute JMPs (target only). Stops at RTS/RTI/BRK, at indirect JMPs
     * (target unknowable statically), and at illegal opcodes — which are
     * recorded in [Sweep.illegal] instead of aborting the sweep.
     *
     * Unreachable bytes (data tables, padding, vectors) are never decoded, so
     * embedded data can never be misreported as an illegal opcode.
     */
    fun reachable(prg: ByteArray, prgBase: Int, entries: List<Int>): Sweep {
        val code = mutableMapOf<Int, Decoded>()
        val illegal = mutableMapOf<Int, Int>()
        val queue = ArrayDeque<Int>()
        for (e in entries) {
            if (e in prgBase until prgBase + prg.size) queue.add(e)
        }
        val seen = mutableSetOf<Int>()
        while (queue.isNotEmpty()) {
            var pc = queue.removeFirst()
            while (true) {
                if (pc in seen) break
                seen.add(pc)
                val d = decode(prg, prgBase, pc)
                if (d == null) {
                    val idx = pc - prgBase
                    if (idx in prg.indices) illegal[pc] = prg[idx].toInt() and 0xFF
                    break
                }
                code[pc] = d
                when (d.mnemonic) {
                    "RTS", "RTI", "BRK" -> break
                    "JMP" -> {
                        // Absolute JMP: follow the target, no fallthrough.
                        // Indirect JMP: target is a runtime value; stop the path.
                        if (d.mode == AddrMode.ABSOLUTE) queue.add(d.operand)
                        break
                    }
                    "JSR" -> queue.add(d.operand) // target + fallthrough
                    "BPL", "BMI", "BVC", "BVS", "BCC", "BCS", "BNE", "BEQ" -> {
                        val off = d.operand.toByte().toInt() // sign-extend
                        queue.add(d.address + d.length + off)
                        // fallthrough continues below
                    }
                }
                pc += d.length
                if (pc >= prgBase + prg.size) break
            }
        }
        return Sweep(code, illegal)
    }
}
