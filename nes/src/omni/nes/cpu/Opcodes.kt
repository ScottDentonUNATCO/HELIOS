package omni.nes.cpu

/**
 * The complete official 6502 opcode table (151 opcodes). Single source of truth
 * shared by the CPU core (decodes + executes) and the disassembler (decodes for
 * the validator's reachability sweep). Clean-room data: opcode numbers,
 * mnemonics, addressing modes and cycle counts are long-published hardware facts.
 *
 * Cycle counts are the documented base values; [OpInfo.pageCross] marks the
 * read instructions that take +1 cycle when the effective address crosses a
 * page boundary (and branches, which take +1/+2 when taken / taken across page).
 */
enum class AddrMode(val bytes: Int) {
    IMPLIED(1),
    ACCUMULATOR(1),
    IMMEDIATE(2),
    ZERO_PAGE(2),
    ZERO_PAGE_X(2),
    ZERO_PAGE_Y(2),
    ABSOLUTE(3),
    ABSOLUTE_X(3),
    ABSOLUTE_Y(3),
    INDIRECT(3),          // JMP (addr) only
    INDEXED_INDIRECT(2),  // (addr,X)
    INDIRECT_INDEXED(2),  // (addr),Y
    RELATIVE(2),
}

data class OpInfo(
    val mnemonic: String,
    val mode: AddrMode,
    val cycles: Int,
    val pageCross: Boolean = false,
)

object Opcodes {
    val table: Array<OpInfo?> = arrayOfNulls(256)

    init {
        fun def(op: Int, mnemonic: String, mode: AddrMode, cycles: Int, pageCross: Boolean = false) {
            require(table[op] == null) { "duplicate opcode definition: ${op.toString(16)}" }
            table[op] = OpInfo(mnemonic, mode, cycles, pageCross)
        }

        // 0x00 row
        def(0x00, "BRK", AddrMode.IMPLIED, 7)
        def(0x01, "ORA", AddrMode.INDEXED_INDIRECT, 6)
        def(0x05, "ORA", AddrMode.ZERO_PAGE, 3)
        def(0x06, "ASL", AddrMode.ZERO_PAGE, 5)
        def(0x08, "PHP", AddrMode.IMPLIED, 3)
        def(0x09, "ORA", AddrMode.IMMEDIATE, 2)
        def(0x0A, "ASL", AddrMode.ACCUMULATOR, 2)
        def(0x0D, "ORA", AddrMode.ABSOLUTE, 4)
        def(0x0E, "ASL", AddrMode.ABSOLUTE, 6)
        // 0x10 row
        def(0x10, "BPL", AddrMode.RELATIVE, 2, pageCross = true)
        def(0x11, "ORA", AddrMode.INDIRECT_INDEXED, 5, pageCross = true)
        def(0x15, "ORA", AddrMode.ZERO_PAGE_X, 4)
        def(0x16, "ASL", AddrMode.ZERO_PAGE_X, 6)
        def(0x18, "CLC", AddrMode.IMPLIED, 2)
        def(0x19, "ORA", AddrMode.ABSOLUTE_Y, 4, pageCross = true)
        def(0x1D, "ORA", AddrMode.ABSOLUTE_X, 4, pageCross = true)
        def(0x1E, "ASL", AddrMode.ABSOLUTE_X, 7)
        // 0x20 row
        def(0x20, "JSR", AddrMode.ABSOLUTE, 6)
        def(0x21, "AND", AddrMode.INDEXED_INDIRECT, 6)
        def(0x24, "BIT", AddrMode.ZERO_PAGE, 3)
        def(0x25, "AND", AddrMode.ZERO_PAGE, 3)
        def(0x26, "ROL", AddrMode.ZERO_PAGE, 5)
        def(0x28, "PLP", AddrMode.IMPLIED, 4)
        def(0x29, "AND", AddrMode.IMMEDIATE, 2)
        def(0x2A, "ROL", AddrMode.ACCUMULATOR, 2)
        def(0x2C, "BIT", AddrMode.ABSOLUTE, 4)
        def(0x2D, "AND", AddrMode.ABSOLUTE, 4)
        def(0x2E, "ROL", AddrMode.ABSOLUTE, 6)
        // 0x30 row
        def(0x30, "BMI", AddrMode.RELATIVE, 2, pageCross = true)
        def(0x31, "AND", AddrMode.INDIRECT_INDEXED, 5, pageCross = true)
        def(0x35, "AND", AddrMode.ZERO_PAGE_X, 4)
        def(0x36, "ROL", AddrMode.ZERO_PAGE_X, 6)
        def(0x38, "SEC", AddrMode.IMPLIED, 2)
        def(0x39, "AND", AddrMode.ABSOLUTE_Y, 4, pageCross = true)
        def(0x3D, "AND", AddrMode.ABSOLUTE_X, 4, pageCross = true)
        def(0x3E, "ROL", AddrMode.ABSOLUTE_X, 7)
        // 0x40 row
        def(0x40, "RTI", AddrMode.IMPLIED, 6)
        def(0x41, "EOR", AddrMode.INDEXED_INDIRECT, 6)
        def(0x45, "EOR", AddrMode.ZERO_PAGE, 3)
        def(0x46, "LSR", AddrMode.ZERO_PAGE, 5)
        def(0x48, "PHA", AddrMode.IMPLIED, 3)
        def(0x49, "EOR", AddrMode.IMMEDIATE, 2)
        def(0x4A, "LSR", AddrMode.ACCUMULATOR, 2)
        def(0x4C, "JMP", AddrMode.ABSOLUTE, 3)
        def(0x4D, "EOR", AddrMode.ABSOLUTE, 4)
        def(0x4E, "LSR", AddrMode.ABSOLUTE, 6)
        // 0x50 row
        def(0x50, "BVC", AddrMode.RELATIVE, 2, pageCross = true)
        def(0x51, "EOR", AddrMode.INDIRECT_INDEXED, 5, pageCross = true)
        def(0x55, "EOR", AddrMode.ZERO_PAGE_X, 4)
        def(0x56, "LSR", AddrMode.ZERO_PAGE_X, 6)
        def(0x58, "CLI", AddrMode.IMPLIED, 2)
        def(0x59, "EOR", AddrMode.ABSOLUTE_Y, 4, pageCross = true)
        def(0x5D, "EOR", AddrMode.ABSOLUTE_X, 4, pageCross = true)
        def(0x5E, "LSR", AddrMode.ABSOLUTE_X, 7)
        // 0x60 row
        def(0x60, "RTS", AddrMode.IMPLIED, 6)
        def(0x61, "ADC", AddrMode.INDEXED_INDIRECT, 6)
        def(0x65, "ADC", AddrMode.ZERO_PAGE, 3)
        def(0x66, "ROR", AddrMode.ZERO_PAGE, 5)
        def(0x68, "PLA", AddrMode.IMPLIED, 4)
        def(0x69, "ADC", AddrMode.IMMEDIATE, 2)
        def(0x6A, "ROR", AddrMode.ACCUMULATOR, 2)
        def(0x6C, "JMP", AddrMode.INDIRECT, 5)
        def(0x6D, "ADC", AddrMode.ABSOLUTE, 4)
        def(0x6E, "ROR", AddrMode.ABSOLUTE, 6)
        // 0x70 row
        def(0x70, "BVS", AddrMode.RELATIVE, 2, pageCross = true)
        def(0x71, "ADC", AddrMode.INDIRECT_INDEXED, 5, pageCross = true)
        def(0x75, "ADC", AddrMode.ZERO_PAGE_X, 4)
        def(0x76, "ROR", AddrMode.ZERO_PAGE_X, 6)
        def(0x78, "SEI", AddrMode.IMPLIED, 2)
        def(0x79, "ADC", AddrMode.ABSOLUTE_Y, 4, pageCross = true)
        def(0x7D, "ADC", AddrMode.ABSOLUTE_X, 4, pageCross = true)
        def(0x7E, "ROR", AddrMode.ABSOLUTE_X, 7)
        // 0x80 row
        def(0x81, "STA", AddrMode.INDEXED_INDIRECT, 6)
        def(0x84, "STY", AddrMode.ZERO_PAGE, 3)
        def(0x85, "STA", AddrMode.ZERO_PAGE, 3)
        def(0x86, "STX", AddrMode.ZERO_PAGE, 3)
        def(0x88, "DEY", AddrMode.IMPLIED, 2)
        def(0x8A, "TXA", AddrMode.IMPLIED, 2)
        def(0x8C, "STY", AddrMode.ABSOLUTE, 4)
        def(0x8D, "STA", AddrMode.ABSOLUTE, 4)
        def(0x8E, "STX", AddrMode.ABSOLUTE, 4)
        // 0x90 row
        def(0x90, "BCC", AddrMode.RELATIVE, 2, pageCross = true)
        def(0x91, "STA", AddrMode.INDIRECT_INDEXED, 6)
        def(0x94, "STY", AddrMode.ZERO_PAGE_X, 4)
        def(0x95, "STA", AddrMode.ZERO_PAGE_X, 4)
        def(0x96, "STX", AddrMode.ZERO_PAGE_Y, 4)
        def(0x98, "TYA", AddrMode.IMPLIED, 2)
        def(0x99, "STA", AddrMode.ABSOLUTE_Y, 5)
        def(0x9A, "TXS", AddrMode.IMPLIED, 2)
        def(0x9D, "STA", AddrMode.ABSOLUTE_X, 5)
        // 0xA0 row
        def(0xA0, "LDY", AddrMode.IMMEDIATE, 2)
        def(0xA1, "LDA", AddrMode.INDEXED_INDIRECT, 6)
        def(0xA2, "LDX", AddrMode.IMMEDIATE, 2)
        def(0xA4, "LDY", AddrMode.ZERO_PAGE, 3)
        def(0xA5, "LDA", AddrMode.ZERO_PAGE, 3)
        def(0xA6, "LDX", AddrMode.ZERO_PAGE, 3)
        def(0xA8, "TAY", AddrMode.IMPLIED, 2)
        def(0xA9, "LDA", AddrMode.IMMEDIATE, 2)
        def(0xAA, "TAX", AddrMode.IMPLIED, 2)
        def(0xAC, "LDY", AddrMode.ABSOLUTE, 4)
        def(0xAD, "LDA", AddrMode.ABSOLUTE, 4)
        def(0xAE, "LDX", AddrMode.ABSOLUTE, 4)
        // 0xB0 row
        def(0xB0, "BCS", AddrMode.RELATIVE, 2, pageCross = true)
        def(0xB1, "LDA", AddrMode.INDIRECT_INDEXED, 5, pageCross = true)
        def(0xB4, "LDY", AddrMode.ZERO_PAGE_X, 4)
        def(0xB5, "LDA", AddrMode.ZERO_PAGE_X, 4)
        def(0xB6, "LDX", AddrMode.ZERO_PAGE_Y, 4)
        def(0xB8, "CLV", AddrMode.IMPLIED, 2)
        def(0xB9, "LDA", AddrMode.ABSOLUTE_Y, 4, pageCross = true)
        def(0xBA, "TSX", AddrMode.IMPLIED, 2)
        def(0xBC, "LDY", AddrMode.ABSOLUTE_X, 4, pageCross = true)
        def(0xBD, "LDA", AddrMode.ABSOLUTE_X, 4, pageCross = true)
        def(0xBE, "LDX", AddrMode.ABSOLUTE_Y, 4, pageCross = true)
        // 0xC0 row
        def(0xC0, "CPY", AddrMode.IMMEDIATE, 2)
        def(0xC1, "CMP", AddrMode.INDEXED_INDIRECT, 6)
        def(0xC4, "CPY", AddrMode.ZERO_PAGE, 3)
        def(0xC5, "CMP", AddrMode.ZERO_PAGE, 3)
        def(0xC6, "DEC", AddrMode.ZERO_PAGE, 5)
        def(0xC8, "INY", AddrMode.IMPLIED, 2)
        def(0xC9, "CMP", AddrMode.IMMEDIATE, 2)
        def(0xCA, "DEX", AddrMode.IMPLIED, 2)
        def(0xCC, "CPY", AddrMode.ABSOLUTE, 4)
        def(0xCD, "CMP", AddrMode.ABSOLUTE, 4)
        def(0xCE, "DEC", AddrMode.ABSOLUTE, 6)
        // 0xD0 row
        def(0xD0, "BNE", AddrMode.RELATIVE, 2, pageCross = true)
        def(0xD1, "CMP", AddrMode.INDIRECT_INDEXED, 5, pageCross = true)
        def(0xD5, "CMP", AddrMode.ZERO_PAGE_X, 4)
        def(0xD6, "DEC", AddrMode.ZERO_PAGE_X, 6)
        def(0xD8, "CLD", AddrMode.IMPLIED, 2)
        def(0xD9, "CMP", AddrMode.ABSOLUTE_Y, 4, pageCross = true)
        def(0xDD, "CMP", AddrMode.ABSOLUTE_X, 4, pageCross = true)
        def(0xDE, "DEC", AddrMode.ABSOLUTE_X, 7)
        // 0xE0 row
        def(0xE0, "CPX", AddrMode.IMMEDIATE, 2)
        def(0xE1, "SBC", AddrMode.INDEXED_INDIRECT, 6)
        def(0xE4, "CPX", AddrMode.ZERO_PAGE, 3)
        def(0xE5, "SBC", AddrMode.ZERO_PAGE, 3)
        def(0xE6, "INC", AddrMode.ZERO_PAGE, 5)
        def(0xE8, "INX", AddrMode.IMPLIED, 2)
        def(0xE9, "SBC", AddrMode.IMMEDIATE, 2)
        def(0xEA, "NOP", AddrMode.IMPLIED, 2)
        def(0xEC, "CPX", AddrMode.ABSOLUTE, 4)
        def(0xED, "SBC", AddrMode.ABSOLUTE, 4)
        def(0xEE, "INC", AddrMode.ABSOLUTE, 6)
        // 0xF0 row
        def(0xF0, "BEQ", AddrMode.RELATIVE, 2, pageCross = true)
        def(0xF1, "SBC", AddrMode.INDIRECT_INDEXED, 5, pageCross = true)
        def(0xF5, "SBC", AddrMode.ZERO_PAGE_X, 4)
        def(0xF6, "INC", AddrMode.ZERO_PAGE_X, 6)
        def(0xF8, "SED", AddrMode.IMPLIED, 2)
        def(0xF9, "SBC", AddrMode.ABSOLUTE_Y, 4, pageCross = true)
        def(0xFD, "SBC", AddrMode.ABSOLUTE_X, 4, pageCross = true)
        def(0xFE, "INC", AddrMode.ABSOLUTE_X, 7)
    }

    /** Null for unofficial/illegal opcodes. */
    fun info(op: Int): OpInfo? = table[op and 0xFF]

    fun isOfficial(op: Int): Boolean = table[op and 0xFF] != null

    /** Must hold: exactly the 151 documented official opcodes. */
    fun officialCount(): Int = table.count { it != null }
}
