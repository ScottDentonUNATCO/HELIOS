package com.omni.app.gamemaker.knowledge

import com.omni.memory.MemoryRecord
import com.omni.memory.MemoryScope

/**
 * Curated NES development knowledge, written FROM the proven Phase-1 pipeline
 * (cpu/rom/asm/validate/ppu) — not scraped, not vibes. Helios agents consult
 * these records when generating NES games so the code they emit is already
 * shaped like something the hardware-truth validator will accept.
 *
 * Every record carries a comment citing the pipeline source it is verifiable
 * against. Records are pinned: hardware facts do not decay.
 *
 * Seeds into a [com.omni.memory.MemoryStore] exactly once via [KnowledgeSeeder].
 */
object NesKnowledgeBase {

    /** Bump when the record set changes; the seeder re-seeds on version change. */
    const val VERSION = 1L

    val records: List<MemoryRecord> = listOf(

        // =================================================================
        // SECTION A — iNES header gate (6 rule IDs, all FAIL).
        // Source: rom/INes.kt — parser throws INesError("RULE_ID: ...").
        // =================================================================

        // rom/INes.kt — "HDR_MAGIC" in INes.parse: first 4 bytes must be 4E 45 53 1A ("NES\x1A").
        rec("nes-kb-hdr-magic", MemoryScope.PROCEDURE, "nes.validator.hdr-magic",
            "RULE HDR_MAGIC (FAIL): first 4 bytes of the file must be 4E 45 53 1A ('NES\\x1A'). " +
                "A file shorter than the 16-byte iNES header fails too. Always emit the 16-byte " +
                "header before PRG/CHR bytes.",
            0.95),

        // rom/INes.kt — "HDR_PRG_SIZE": prgBanks==0 fails; >2 fails; truncated file fails.
        rec("nes-kb-hdr-prg-size", MemoryScope.PROCEDURE, "nes.validator.hdr-prg-size",
            "RULE HDR_PRG_SIZE (FAIL): header byte 4 (PRG 16KB banks) must be 1 or 2 — NROM max " +
                "is 32KB. 0 banks fails ('a ROM needs code'), >2 fails. The file must be at " +
                "least 16 + prgBanks*16384 + chrBanks*8192 bytes or it fails as truncated.",
            0.95),

        // rom/INes.kt — "HDR_CHR_SIZE": chrBanks>1 fails.
        rec("nes-kb-hdr-chr-size", MemoryScope.PROCEDURE, "nes.validator.hdr-chr-size",
            "RULE HDR_CHR_SIZE (FAIL): header byte 5 (CHR 8KB banks) must be 0 or 1 — max 8KB. " +
                "0 banks is legal: the parser then treats CHR as blank RAM (writeable CHR). " +
                ">1 bank fails.",
            0.9),

        // rom/INes.kt — "HDR_TRAINER": flag6 bit 2 fails.
        rec("nes-kb-hdr-trainer", MemoryScope.PROCEDURE, "nes.validator.hdr-trainer",
            "RULE HDR_TRAINER (FAIL): flag 6 bit 2 (512-byte trainer) must be 0. Trainers are " +
                "obsolete and rejected; never emit the trainer bytes.",
            0.85),

        // rom/INes.kt — "HDR_MAPPER": mapper=(f7&0xF0)|((f6&0xF0)>>4) must be 0.
        rec("nes-kb-hdr-mapper", MemoryScope.PROCEDURE, "nes.validator.hdr-mapper",
            "RULE HDR_MAPPER (FAIL): mapper number (flag 6 bits 4-7 plus flag 7 bits 4-7) must " +
                "be 0 (NROM). Phase 1 supports mapper 0 only — no bank switching of any kind.",
            0.95),

        // rom/INes.kt — "HDR_NES20": flag7 bits 2-3 == 0x08 fails.
        rec("nes-kb-hdr-nes20", MemoryScope.PROCEDURE, "nes.validator.hdr-nes20",
            "RULE HDR_NES20 (FAIL): NES 2.0 headers are rejected. Keep flag 7 bits 2-3 clear " +
                "(byte 7 should be 0x00 for plain NROM); the builder emits iNES, not NES 2.0.",
            0.85),

        // =================================================================
        // SECTION B — NesValidator rejection rules (11 FAIL rule IDs).
        // Source: validate/Validator.kt — enum-free string rule IDs.
        // =================================================================

        // validate/Validator.kt rule "VEC_NMI_IN_PRG" — NMI vector at ${'$'}FFFA must land in PRG window.
        rec("nes-kb-vec-nmi", MemoryScope.PROCEDURE, "nes.validator.vec-nmi-in-prg",
            "RULE VEC_NMI_IN_PRG (FAIL): the NMI vector at ${'$'}FFFA must point inside the PRG " +
                "window (NROM-128: [${'$'}C000,${'$'}10000); NROM-256: [${'$'}8000,${'$'}10000)). A vector into " +
                "RAM, PPU regs, or empty space fails.",
            0.95),

        // validate/Validator.kt rule "VEC_RESET_IN_PRG" — RESET vector at ${'$'}FFFC must land in PRG.
        rec("nes-kb-vec-reset", MemoryScope.PROCEDURE, "nes.validator.vec-reset-in-prg",
            "RULE VEC_RESET_IN_PRG (FAIL): the RESET vector at ${'$'}FFFC must point inside the PRG " +
                "window. Boot always starts at readWord(${'$'}FFFC), so this must be your init code.",
            0.95),

        // validate/Validator.kt rule "VEC_IRQ_IN_PRG" — IRQ vector at ${'$'}FFFE; ${'$'}0000 is WARN-only.
        rec("nes-kb-vec-irq", MemoryScope.PROCEDURE, "nes.validator.vec-irq-in-prg",
            "RULE VEC_IRQ_IN_PRG (FAIL unless ${'$'}0000): the IRQ vector at ${'$'}FFFE must point inside " +
                "the PRG window. ${'$'}0000 is allowed with a WARN (conventionally unused — wire it " +
                "up if the game uses IRQs). Anything else outside PRG fails.",
            0.9),

        // validate/Validator.kt rule "ILLEGAL_OPCODE" — swept illegal opcodes fail (or warn if toggled).
        rec("nes-kb-illegal-opcode", MemoryScope.PROCEDURE, "nes.validator.illegal-opcode",
            "RULE ILLEGAL_OPCODE (FAIL by default): any unofficial/illegal 6502 opcode on code " +
                "reachable from the vectors fails validation. Only the 151 official opcodes are " +
                "safe; the CPU core refuses to execute the rest. The strictness toggle can " +
                "downgrade this to WARN, but off means off — default on.",
            0.95),

        // validate/Validator.kt rule "PALETTE_TIMING" — palette writes outside vblank with rendering on fail.
        rec("nes-kb-palette-timing", MemoryScope.PROCEDURE, "nes.validator.palette-timing",
            "RULE PALETTE_TIMING (FAIL): a ${'$'}2007 write whose tracked ${'$'}2006 address is in " +
                "${'$'}3F00-${'$'}3F1F (palette RAM) fails if it is NOT in NMI-reachable code AND " +
                "rendering is on (tracked ${'$'}2001 with bits 3/4 set). Upload palettes during " +
                "vblank (inside the NMI handler), or write ${'$'}2001 to force blank first. " +
                "Untracked ${'$'}2001 state only yields an INFO, not a fail.",
            0.9),

        // validate/Validator.kt rule "SPRITE_OVERFLOW" — >8 sprites on one scanline fails.
        rec("nes-kb-sprite-overflow", MemoryScope.PROCEDURE, "nes.validator.sprite-overflow",
            "RULE SPRITE_OVERFLOW (FAIL): more than 8 sprites sharing one scanline fails. The " +
                "validator buckets NMI-reachable ${'$'}2004 writes by Y/8 and fails any bucket with " +
                ">8 entries. If sprites are uploaded via ${'$'}4014 DMA or use dynamic Y, the " +
                "validator emits INFO ('not statically decidable') — but hardware still " +
                "flickers/drops sprites past 8 per scanline, so budget for 8.",
            0.9),

        // validate/Validator.kt rule "BOOT_PC_ESCAPED_PRG" — falling off the ROM during boot fails.
        rec("nes-kb-boot-pc-escaped", MemoryScope.PROCEDURE, "nes.validator.boot-pc-escaped-prg",
            "RULE BOOT_PC_ESCAPED_PRG (FAIL): during the headless boot (300,000-cycle budget) " +
                "the PC must not leave the PRG window except via a deliberate control-flow op " +
                "(JMP/JSR/RTS/RTI/BRK/branches) — e.g. jumping into RAM you copied code to is " +
                "fine. Falling off the end of the ROM (an instruction straddling the PRG end " +
                "also fails) means hardware would execute whatever RAM powers up with.",
            0.9),

        // validate/Validator.kt rule "BOOT_ILLEGAL_OPCODE".
        rec("nes-kb-boot-illegal", MemoryScope.PROCEDURE, "nes.validator.boot-illegal-opcode",
            "RULE BOOT_ILLEGAL_OPCODE (FAIL): an illegal opcode actually executed during the " +
                "headless boot fails immediately and halts validation. The static gate checks " +
                "reachable code; this is the dynamic backstop.",
            0.85),

        // validate/Validator.kt rule "BOOT_STACK_FAULT" — stricter-than-hardware depth tracking.
        rec("nes-kb-boot-stack", MemoryScope.PROCEDURE, "nes.validator.boot-stack-fault",
            "RULE BOOT_STACK_FAULT (FAIL): the validator tracks stack push depth across the " +
                "boot — popping (PLA/PLP/RTI/RTS) at depth 0 is an underflow fault, pushing " +
                "past depth 256 is an overflow fault. Real hardware just wraps the 8-bit SP; " +
                "this rule is stricter than hardware on purpose, so keep push/pop balanced.",
            0.85),

        // validate/Validator.kt rule "BOOT_BUS_FAULT" — ROM writes / unmapped access.
        rec("nes-kb-boot-bus", MemoryScope.PROCEDURE, "nes.validator.boot-bus-fault",
            "RULE BOOT_BUS_FAULT (FAIL): any bus fault during boot fails — writing to PRG ROM " +
                "(ROM_WRITE: writes to ${'$'}8000-${'$'}FFFF on NROM are ignored by hardware, a bug " +
                "source) or accessing unmapped addresses (UNMAPPED). RAM is ${'$'}0000-${'$'}1FFF " +
                "(mirrored), PPU regs ${'$'}2000-${'$'}3FFF, PRG ROM ${'$'}8000-${'$'}FFFF.",
            0.9),

        // validate/Validator.kt rule "NMI_ENABLED" — a real NMI frame must execute within the boot.
        rec("nes-kb-nmi-enabled", MemoryScope.PROCEDURE, "nes.validator.nmi-enabled",
            "RULE NMI_ENABLED (FAIL): at least one NMI frame must actually execute within the " +
                "300,000-cycle boot window. Enabling ${'$'}2000 bit 7 is not enough — the frame is " +
                "only credited if the NMI really fired (I flag clear, PC changed). A ROM that " +
                "sets the bit but hangs before vblank fails. One frame = 29,780 CPU cycles.",
            0.95),

        // =================================================================
        // SECTION C — PPU hardware truth (WORLD_DETAIL).
        // Source: ppu/Ppu2C02.kt (2C02-ish, cycle-approximate by design).
        // =================================================================

        // ppu/Ppu2C02.kt companion: FRAME_DOTS=89340, 341 dots/scanline, 262 scanlines, vblank at 241.
        rec("nes-kb-ppu-timing", MemoryScope.WORLD_DETAIL, "nes.ppu.frame-timing",
            "NTSC frame: 89,340 PPU dots = 29,780 CPU cycles (3 dots per CPU cycle), 341 " +
                "dots x 262 scanlines. Scanlines 0-239 are visible; vblank starts at scanline " +
                "241 (vblank flag ${'$'}2002 bit 7 set there), scanlines 241-261 are vblank-ish. " +
                "60Hz: all game timing derives from the once-per-frame NMI.",
            0.95),

        // ppu/Ppu2C02.kt renderFrame: 256x240, per-pixel bg+sprite fetch.
        rec("nes-kb-ppu-resolution", MemoryScope.WORLD_DETAIL, "nes.ppu.resolution",
            "Resolution is 256x240 pixels. Background is 32x30 tiles from the nametables; " +
                "each tile is 8x8 pixels = 2 bits per pixel = 4 colors. ppumask bits select " +
                "left-8px clipping (bit 1 bg, bit 2 sprites), greyscale (bit 0), bg enable " +
                "(bit 3), sprite enable (bit 4).",
            0.85),

        // ppu/Ppu2C02.kt ntPhys: 2KB nametable RAM, H/V mirroring from header flag 6 bit 0.
        rec("nes-kb-ppu-nametables", MemoryScope.WORLD_DETAIL, "nes.ppu.nametables",
            "2KB of nametable RAM holds 4 logical 1KB tables, mirrored by the header's flag-6 " +
                "bit 0. Horizontal mirroring: ${'$'}2000=${'$'}2400 and ${'$'}2800=${'$'}2C00 (good for vertical " +
                "scrolling). Vertical mirroring: ${'$'}2000=${'$'}2800 and ${'$'}2400=${'$'}2C00 (good for " +
                "horizontal scrolling). Each nametable: 960 tile bytes + 64 attribute bytes " +
                "(2 bits of palette per 2x2 tile block).",
            0.9),

        // ppu/Ppu2C02.kt: 64 sprites x 4 bytes; 8 per scanline; ${'$'}2002 bits 5/6/7.
        rec("nes-kb-ppu-sprites", MemoryScope.WORLD_DETAIL, "nes.ppu.sprites",
            "OAM holds 64 sprites of 4 bytes each: Y (top of sprite minus 1), tile index, " +
                "attributes (bit 7 vflip, bit 6 hflip, bit 5 behind-bg priority, bits 0-1 " +
                "palette), X. 8x8 only in Phase 1 — ${'$'}2000 bit 5 (8x16 mode) is ignored and " +
                "tall sprites render as 8x8. Hardware shows max 8 sprites per scanline, " +
                "evaluated in OAM order (lower index wins). Status bits: ${'$'}2002 bit 7 vblank, " +
                "bit 6 sprite-0 hit (needs bg+sprites on, x != 255), bit 5 sprite overflow.",
            0.95),

        // ppu/Ppu2C02.kt paletteRam: 32 bytes; ${'$'}3F10/${'$'}3F14/${'$'}3F18/${'$'}3F1C mirror the ${'$'}3F00 row.
        rec("nes-kb-ppu-palette", MemoryScope.WORLD_DETAIL, "nes.ppu.palette",
            "32 bytes of palette RAM at ${'$'}3F00-${'$'}3F1F (only 6 bits per byte stored). Layout: " +
                "${'$'}3F00 universal backdrop; ${'$'}3F01-${'$'}3F0F four background palettes of 3 colors " +
                "each; ${'$'}3F10-${'$'}3F1F four sprite palettes (same structure). ${'$'}3F10/${'$'}3F14/${'$'}3F18/" +
                "${'$'}3F1C mirror ${'$'}3F00/${'$'}3F04/${'$'}3F08/${'$'}3F0C. Colors are indices 0-63 into the fixed " +
                "64-entry NTSC decode table. Palette writes outside vblank with rendering on " +
                "fail validation (PALETTE_TIMING).",
            0.9),

        // ppu/Ppu2C02.kt renderFrame + gen/ChrData.kt: 16 bytes per tile, bitplanes.
        rec("nes-kb-chr-format", MemoryScope.WORLD_DETAIL, "nes.chr.tile-format",
            "CHR tile format: 16 bytes per 8x8 tile — bytes 0-7 are the low bitplane (one " +
                "byte per row), bytes 8-15 the high bitplane. Pixel color = low bit + 2*high " +
                "bit; color 0 is transparent for sprites. 8KB CHR = 512 tiles. Pattern-table " +
                "base: ${'$'}0000 or ${'$'}1000, selected by ${'$'}2000 bit 4 (background) and bit 3 " +
                "(sprites). With 0 CHR banks in the header, CHR is blank writeable RAM.",
            0.95),

        // ppu/Ppu2C02.kt readReg/writeReg: ${'$'}2000-${'$'}2007 behavior; ${'$'}4014 DMA.
        rec("nes-kb-ppu-registers", MemoryScope.WORLD_DETAIL, "nes.ppu.registers",
            "PPU registers at ${'$'}2000-${'$'}2007 (mirrored every 8 bytes through ${'$'}3FFF). ${'$'}2000 " +
                "PPUCTRL write-only (bit 7 NMI enable, bit 2 VRAM +1/+32, bits 3/4 pattern " +
                "bases, bits 0-1 base nametable). ${'$'}2001 PPUMASK write-only. ${'$'}2002 PPUSTATUS " +
                "read-only: reading clears vblank and resets the ${'$'}2005/${'$'}2006 write latch. " +
                "${'$'}2003 OAM address, ${'$'}2004 OAM data (write auto-increments). ${'$'}2005/${'$'}2006 are " +
                "double-write (first then second); ${'$'}2006 double-write sets the VRAM address " +
                "for ${'$'}2007. ${'$'}2007 reads are buffered (stale byte first, palette immediate); " +
                "writes go through. ${'$'}4014 copies 256 bytes from a CPU page into OAM (OAMADDR-" +
                "relative wrap), stalling ~513 cycles.",
            0.95),

        // =================================================================
        // SECTION D — CPU / memory map / mapper (WORLD_DETAIL).
        // Source: cpu/Cpu6502.kt, cpu/Bus.kt, cpu/Opcodes.kt.
        // =================================================================

        // cpu/Opcodes.kt: AddrMode enum (13) + 151 official opcodes. cpu/Cpu6502.kt deviations.
        rec("nes-kb-cpu-facts", MemoryScope.WORLD_DETAIL, "nes.cpu.facts",
            "The NES CPU is a 6502 (2A03) running the 151 official opcodes in 13 addressing " +
                "modes (implied, accumulator, immediate, zero-page, zp,X, zp,Y, absolute, " +
                "abs,X, abs,Y, indirect (JMP only), (ind,X), (ind),Y). Deliberate pipeline " +
                "facts: no decimal mode — ADC/SBC always compute binary even with D set; " +
                "JMP (indirect) has the real page-wrap bug (JMP (${'$'}xxFF) fetches high byte " +
                "from ${'$'}xx00); illegal opcodes are NOT executed (boot fails). Reset sets " +
                "SP=${'$'}FD and P=${'$'}24 (I flag set: interrupts masked until you CLI).",
            0.95),

        // cpu/Bus.kt memory map: ${'$'}0000-${'$'}1FFF RAM, ${'$'}2000-${'$'}3FFF PPU, ${'$'}8000-${'$'}FFFF PRG (+ NROM-128 mirror).
        rec("nes-kb-bus-map", MemoryScope.WORLD_DETAIL, "nes.cpu.bus-map",
            "CPU memory map: ${'$'}0000-${'$'}1FFF 2KB internal RAM (mirrored every ${'$'}800). " +
                "${'$'}2000-${'$'}3FFF PPU registers (mirrored every 8 bytes). ${'$'}4000-${'$'}401F APU/IO " +
                "(${'$'}4014 OAM DMA, ${'$'}4016/${'$'}4017 controllers). ${'$'}6000-${'$'}7FFF cartridge SRAM " +
                "(unused on NROM). ${'$'}8000-${'$'}FFFF PRG ROM: NROM-256 fills it all; NROM-128 " +
                "places 16KB at ${'$'}C000-${'$'}FFFF and mirrors it at ${'$'}8000-${'$'}BFFF. Writes to PRG " +
                "ROM are ignored by hardware and fail the boot gate (ROM_WRITE).",
            0.9),

        // rom/INes.kt mapper 0 + Bus.kt prgIs16k + Ppu2C02 chrWritable.
        rec("nes-kb-mapper0", MemoryScope.WORLD_DETAIL, "nes.mapper0.constraints",
            "Mapper 0 (NROM) constraints, Phase 1 only: PRG is 16KB or 32KB with NO bank " +
                "switching — the whole game must fit. CHR is a fixed 8KB bank of tiles " +
                "(ROM, or RAM if header byte 5 is 0). No scanline IRQs, no extra sound " +
                "channels, no save RAM on the reference build. Generated games must be " +
                "designed like real NROM games: tiny code, tile-based graphics, NMI-driven " +
                "frame loop.",
            0.9),

        // =================================================================
        // SECTION E — assembler truth (PROCEDURE).
        // Source: asm/Assembler.kt (two-pass, ca65 subset).
        // =================================================================

        // asm/Assembler.kt MNEMONICS: all 56 official 6502 mnemonics supported.
        rec("nes-kb-asm-mnemonics", MemoryScope.PROCEDURE, "nes.asm.mnemonics",
            "The pipeline assembler supports exactly the 56 official 6502 mnemonics: LDA " +
                "STA LDX STX LDY STY TAX TAY TXA TYA TSX TXS DEX DEY INX INY ADC SBC AND " +
                "ORA EOR CMP CPX CPY BIT ASL LSR ROL ROR INC DEC BPL BMI BVC BVS BCC BCS " +
                "BNE BEQ JMP JSR RTS RTI BRK PHA PHP PLA PLP CLC SEC CLI SEI CLV CLD SED " +
                "NOP. No unofficial opcodes — an unknown mnemonic is an assemble error, " +
                "and an illegal opcode on the ROM fails validation.",
            0.9),

        // asm/Assembler.kt Mode enum + planInstr mode selection.
        rec("nes-kb-asm-modes", MemoryScope.PROCEDURE, "nes.asm.addressing-modes",
            "Supported addressing syntax: implied (NOP), A accumulator (LSR A), #imm " +
                "(LDA #${'$'}10, also #<label / #>label), zp / zp,X / zp,Y, abs / abs,X / " +
                "abs,Y, (addr) for JMP only, (zp,X), (zp),Y. Branches (BPL..BEQ) always " +
                "assemble as relative. Zero-page is chosen automatically when the operand " +
                "resolves below ${'$'}100 and the mnemonic supports it.",
            0.9),

        // asm/Assembler.kt directives + expressions (Stmts + ExprParser).
        rec("nes-kb-asm-directives", MemoryScope.PROCEDURE, "nes.asm.directives",
            "Assembler directives: .org addr (forward only; gaps zero-filled, backwards " +
                "is an error), .byte/.db (numbers or \"strings\"), .word/.dw (little-endian " +
                "words — use for the ${'$'}FFFA/${'$'}FFFC/${'$'}FFFE vectors), .res count[,fill]. " +
                "Constants: name = expr (must be defined before use). Expressions: ${'$'}hex, " +
                "%binary, decimal, <low byte, >high byte, + - *, parentheses, symbols, " +
                "and * for the current PC. ';' starts a comment (kept inside strings). " +
                "Labels: name: alone on a line or before an instruction.",
            0.9),

        // asm/Assembler.kt two-pass rule: forward refs assumed ABSOLUTE (extra byte).
        rec("nes-kb-asm-forward-refs", MemoryScope.PROCEDURE, "nes.asm.forward-refs",
            "Two-pass rule that costs bytes: a symbol referenced before it is defined is " +
                "assumed ABSOLUTE (3-byte instruction) for sizing in pass 1, and pass 2 " +
                "keeps that choice even if the symbol later resolves below ${'$'}100. So a " +
                "forward-referenced zero-page operand costs one extra byte forever. " +
                "Define zero-page symbols before use to keep code tight.",
            0.8),

        // asm/Assembler.kt Mode.REL: branch offset must be -128..127.
        rec("nes-kb-asm-branch-range", MemoryScope.PROCEDURE, "nes.asm.branch-range",
            "Branches reach only -128..+127 bytes from the instruction after the branch " +
                "— a farther branch is an assemble error ('branch out of range'), not " +
                "silently wrong code. Long jumps need JMP (or a branch-around-JMP); JSR " +
                "is absolute-only. Generated code must keep branch targets local.",
            0.85),

        // =================================================================
        // SECTION F — the generation pipeline contract (PROCEDURE).
        // Source: gen/GenerationLoop.kt, gen/Repair.kt, validate/Validator.kt.
        // =================================================================

        // gen/GenerationLoop.kt: generate -> assemble -> validate -> repair loop; ValidatorConfig toggles.
        rec("nes-kb-gen-loop", MemoryScope.PROCEDURE, "nes.gen.pipeline",
            "Generation contract (gen/GenerationLoop): spec -> assembly text -> Assembler " +
                "-> NesValidator -> Repair-on-fail loop, each attempt recorded. A game is " +
                "done only when the ROM passes the full validator GREEN — assembly that " +
                "merely assembles is not enough. The validator's strictness toggles " +
                "(strictIllegalOpcodes, requireNmi, checkSpriteOverflow, checkPaletteTiming) " +
                "are proven off=off: disable a check only if you accept what it was " +
                "catching. Boot budget 300,000 cycles, NMI frame 29,780 cycles.",
            0.9),
    )

    /** IDs are stable across versions; used by the seeder for idempotency checks. */
    val ids: Set<String> get() = records.map { it.id }.toSet()

    private fun rec(
        id: String,
        scope: MemoryScope,
        key: String,
        value: String,
        salience: Double,
    ): MemoryRecord {
        val now = System.currentTimeMillis()
        return MemoryRecord(
            id = id,
            scope = scope,
            key = key,
            value = value,
            salience = salience,
            createdAtMs = now,
            updatedAtMs = now,
            version = VERSION,
            provenance = "knowledge:nes",
            pinned = true,
        )
    }
}
