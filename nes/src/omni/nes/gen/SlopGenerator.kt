package omni.nes.gen

/**
 * Deliberately broken generators for testing the loop. Each [SlopVariant]
 * is derived from [MockGenerator]'s known-good D-pad game by a precise,
 * documented sabotage, so the *expected* validator rule IDs are known up
 * front and asserted in the tests.
 *
 * [SlopGenerator] is also [RepromptableGenerator]: [reprompt] returns the
 * clean template, modelling an LLM that actually listens to the validator's
 * findings. (The adversarial tests use generators whose reprompt does NOT
 * listen.)
 */
enum class SlopVariant {
    /** Vector table zeroed: VEC_NMI_IN_PRG + VEC_RESET_IN_PRG. Surgically fixable. */
    ZERO_VECTORS,

    /** Vectors present but pointing outside PRG: VEC_*_IN_PRG. Surgically fixable. */
    BAD_VECTORS,

    /** `$2000` bit 7 never set: NMI_ENABLED. Surgically fixable. */
    NMI_NEVER_ENABLED,

    /** NMI enabled but CLI missing (I flag stuck): NMI_ENABLED. Surgically fixable. */
    MISSING_CLI,

    /** Raw illegal opcode byte in the RESET path: ILLEGAL_OPCODE (+BOOT_ILLEGAL_OPCODE). */
    ILLEGAL_OPCODES,

    /** 9 sprites sharing one scanline via constant $2004 writes: SPRITE_OVERFLOW. */
    SPRITE_OVERFLOW,

    /** Palette upload with rendering on, outside NMI: PALETTE_TIMING. */
    PALETTE_VIOLATION,
}

class SlopGenerator(val variant: SlopVariant) : RepromptableGenerator, ChrProvider {

    private val mock = MockGenerator()
    private val spec = GameSpec("slop", mechanics = listOf("move"))

    /** The clean program this slop was sabotaged from. */
    fun cleanAsm(): String = mock.generate(spec)

    override fun chr(): ByteArray = ChrData.default()

    override fun generate(spec: GameSpec): String = when (variant) {
        SlopVariant.ZERO_VECTORS -> sabotageVectors(cleanAsm(), zero = true)
        SlopVariant.BAD_VECTORS -> sabotageVectors(cleanAsm(), zero = false)
        SlopVariant.NMI_NEVER_ENABLED ->
            cleanAsm().replace("    LDA #%10000000\n    STA \$2000           ; NMI on\n", "")
        SlopVariant.MISSING_CLI ->
            cleanAsm().replace("    CLI\n", "")
        SlopVariant.ILLEGAL_OPCODES ->
            cleanAsm().replace(
                "RESET:\n",
                "RESET:\n    .byte \$0B               ; slop: illegal opcode in RESET path\n",
            )
        SlopVariant.SPRITE_OVERFLOW -> spriteOverflowAsm()
        SlopVariant.PALETTE_VIOLATION -> paletteViolationAsm()
    }

    /** A cooperative model: the reprompt fixes the slop. */
    override fun reprompt(spec: GameSpec, failure: FailedAttempt): String = cleanAsm()

    private fun sabotageVectors(asm: String, zero: Boolean): String {
        val tail = if (zero) {
            "    .org \$FFFA\n    .res 6                  ; slop: vectors zeroed\n"
        } else {
            "    .org \$FFFA\n    .word \$0000, \$1234, \$0000 ; slop: vectors outside PRG\n"
        }
        val idx = asm.indexOf("    .org \$FFFA")
        require(idx >= 0) { "clean template lost its vector tail" }
        return asm.substring(0, idx) + tail
    }

    /** NMI handler writes 9 sprites at Y=$50 via constant $2003/$2004 stores. */
    internal fun spriteOverflowAsm(): String {
        val nmi = buildString {
            appendLine("    LDX #\$00")
            appendLine("    STX \$2003           ; OAM index 0")
            for (i in 0 until 9) {
                val x = 0x10 + i * 0x10
                appendLine("    ; slop: sprite $i, all at Y=\$50 (same scanlines)")
                appendLine("    LDA #\$50")
                appendLine("    STA \$2004           ; Y")
                appendLine("    LDA #\$01")
                appendLine("    STA \$2004           ; tile")
                appendLine("    LDA #\$00")
                appendLine("    STA \$2004           ; attributes")
                appendLine("    LDA #\$${x.toString(16).uppercase()}")
                appendLine("    STA \$2004           ; X")
            }
        }
        val init = """
    LDA #$78
    STA $0200
    LDA #$01
    STA $0201
    LDA #$00
    STA $0202
    STA $0203
""".trimIndent().lines().joinToString("\n") { "    $it" } + "\n"
        return mock.scaffold("slop sprite overflow", spec, "", init, nmi.toString())
    }

    /** Palette upload in RESET with rendering already on: provable vblank violation. */
    internal fun paletteViolationAsm(): String {
        val init = """
    ; slop: rendering ON before the palette upload, outside NMI
    CLI
    LDA #%10000000
    STA $2000
    LDA #%00011110
    STA $2001           ; rendering on NOW
    LDA #$3F
    STA $2006
    LDA #$00
    STA $2006
    LDX #$00
pslop:
    LDA palette,X
    STA $2007           ; slop: palette write, rendering on, not in NMI
    INX
    CPX #$20
    BNE pslop
    LDA #$80
    STA $0200
    LDA #$01
    STA $0201
    LDA #$00
    STA $0202
    LDA #$80
    STA $0203
""".trimIndent().lines().joinToString("\n") { "    $it" } + "\n"
        // NOTE: the shared scaffold appends its own CLI/$2000/$2001 enable
        // after initCode; a second enable is harmless (idempotent).
        return mock.scaffold("slop palette violation", spec, "", init, "    NOP\n")
    }
}
