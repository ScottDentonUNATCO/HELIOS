package omni.nes.gen

/**
 * STAR DODGER — a real, playable single-screen arcade game produced by the
 * OMNI NES game-maker's deterministic template path.
 *
 * The game: pilot a ship along the bottom of the screen with the D-pad.
 * Meteors rain from the top (dodge them); stars drift down (grab them for
 * +10 points). Three lives; a meteor hit costs one and grants 90 frames of
 * mercy invincibility (blinking). Lose all three and it's GAME OVER —
 * press START to fly again.
 *
 * - [DodgerChr.data] is the 8 KB CHR image the game assumes (ship, meteor,
 *   star, digits, and a 3x5 letter set for the title / GAME OVER text).
 * - [DodgerGame.generate] emits reviewed 6502 in the assembler's syntax,
 *   built on [MockGenerator.scaffold] (same NROM-128 boot, NMI, and
 *   controller conventions as the other template games).
 *
 * All game state lives in zero page / low RAM; all graphics updates happen
 * in the NMI handler; the main loop idles. Nametable text is written during
 * forced blank (init/title) or inside vblank (game-over screen), so the
 * validator's PALETTE_TIMING rule stays quiet.
 */
object DodgerChr {

    private fun tile(rows: List<String>): ByteArray {
        require(rows.size == 8 && rows.all { it.length == 8 })
        val out = ByteArray(16)
        for (r in 0 until 8) {
            var lo = 0
            for (c in 0 until 8) {
                if (rows[r][c] == '#') lo = lo or (0x80 ushr c)
            }
            out[r] = lo.toByte()
            out[r + 8] = 0
        }
        return out
    }

    private val SHIP = tile(
        listOf(
            "...##...",
            "...##...",
            "..####..",
            ".######.",
            "########",
            ".######.",
            "..#..#..",
            "........",
        ),
    )

    private val METEOR = tile(
        listOf(
            "..####..",
            ".######.",
            "########",
            "##.##.##",
            "########",
            "##.##.##",
            ".######.",
            "..####..",
        ),
    )

    private val STAR = tile(
        listOf(
            "...##...",
            "...##...",
            "########",
            "########",
            "...##...",
            "...##...",
            "........",
            "........",
        ),
    )

    // 3x5 capitals, centered (rows 1..5, cols 2..4) — same cut as the digits.
    private val LETTERS = linkedMapOf(
        'A' to listOf(".#.", "#.#", "###", "#.#", "#.#"),
        'C' to listOf(".##", "#..", "#..", "#..", ".##"),
        'D' to listOf("##.", "#.#", "#.#", "#.#", "##."),
        'E' to listOf("###", "#..", "##.", "#..", "###"),
        'G' to listOf(".##", "#..", "#.#", "#.#", ".##"),
        'M' to listOf("#.#", "###", "###", "#.#", "#.#"),
        'O' to listOf(".#.", "#.#", "#.#", "#.#", ".#."),
        'P' to listOf("##.", "#.#", "##.", "#..", "#.."),
        'R' to listOf("##.", "#.#", "##.", "#.#", "#.#"),
        'S' to listOf(".##", "#..", ".#.", "..#", "##."),
        'T' to listOf("###", ".#.", ".#.", ".#.", ".#."),
        'V' to listOf("#.#", "#.#", "#.#", "#.#", ".#."),
    )

    /** Tile index of each letter in the dodger CHR (digits stay at 16..25). */
    val letterTile: Map<Char, Int> =
        LETTERS.keys.mapIndexed { i, c -> c to (32 + i) }.toMap()

    /** Full 8 KB CHR: default tiles + ship/meteor/star + letters at 32..43. */
    fun data(): ByteArray {
        val chr = ChrData.default()
        fun put(tileIndex: Int, data: ByteArray) {
            data.copyInto(chr, tileIndex * 16)
        }
        put(1, SHIP)
        put(2, METEOR)
        put(3, STAR)
        LETTERS.values.forEachIndexed { i, glyph ->
            val rows = MutableList(8) { "........" }
            for (r in 0 until 5) rows[r + 1] = ".." + glyph[r] + "..."
            put(32 + i, tile(rows))
        }
        return chr
    }
}

/**
 * Deterministic STAR DODGER generator. Implements [ChrProvider] so the
 * build loop wraps the ROM with [DodgerChr.data] instead of the default
 * tiles — the letter tiles the GAME OVER screen needs actually have to be
 * in the ROM.
 */
class DodgerGenerator : Generator, ChrProvider {

    override fun chr(): ByteArray = DodgerChr.data()

    override fun generate(spec: GameSpec): String {
        val title = MockGenerator().sanitizeTitle(spec.title)
        return MockGenerator().scaffold(title, spec, zpDecls, initCode, nmiCode, tailCode)
    }

    private val zpDecls: String = """
score0 = ${'$'}00
score1 = ${'$'}01
score2 = ${'$'}02
score3 = ${'$'}03
framectr = ${'$'}04
lives = ${'$'}05
state = ${'$'}06
invinctr = ${'$'}07
prng = ${'$'}08
txtlo = ${'$'}09
txthi = ${'$'}0A
nmlo = ${'$'}0B
nmhi = ${'$'}0C
txtlen = ${'$'}0D
tmp = ${'$'}0E
tmp2 = ${'$'}0F
T_A = 32
T_C = 33
T_D = 34
T_E = 35
T_G = 36
T_M = 37
T_O = 38
T_P = 39
T_R = 40
T_S = 41
T_T = 42
T_V = 43
dg_py = ${'$'}0360
dg_px = ${'$'}0361
dg_godrawn = ${'$'}0362
dg_my = ${'$'}0300
dg_mx = ${'$'}0310
dg_mspeed = ${'$'}0320
dg_sy = ${'$'}0330
dg_sx = ${'$'}0340
dg_sact = ${'$'}0350
""".trimIndent() + "\n"

    /** Runs once at boot with rendering off: palettes, sprite clear, title. */
    private val initCode: String = """
    ; ---- sprite palettes (rendering off: legal) ----
    LDA #${'$'}3F
    STA ${'$'}2006
    LDA #${'$'}10
    STA ${'$'}2006
    LDA #${'$'}0F
    STA ${'$'}2007
    LDA #${'$'}16
    STA ${'$'}2007
    LDA #${'$'}26
    STA ${'$'}2007
    LDA #${'$'}36
    STA ${'$'}2007
    LDA #${'$'}0F
    STA ${'$'}2007
    LDA #${'$'}30
    STA ${'$'}2007
    LDA #${'$'}10
    STA ${'$'}2007
    LDA #${'$'}00
    STA ${'$'}2007
    LDA #${'$'}0F
    STA ${'$'}2007
    LDA #${'$'}28
    STA ${'$'}2007
    LDA #${'$'}38
    STA ${'$'}2007
    LDA #${'$'}18
    STA ${'$'}2007
    ; ---- background palette 0 (nametable text; the scaffold leaves it
    ;      all-black, which would render the title/GAME OVER text in
    ;      black-on-black) ----
    LDA #${'$'}3F
    STA ${'$'}2006
    LDA #${'$'}01
    STA ${'$'}2006
    LDA #${'$'}30
    STA ${'$'}2007
    LDA #${'$'}16
    STA ${'$'}2007
    LDA #${'$'}0F
    STA ${'$'}2007
    ; ---- hide all 16 sprites ----
    LDX #${'$'}00
    LDA #${'$'}FF
dg_hide:
    STA ${'$'}0200,X
    INX
    INX
    INX
    INX
    BNE dg_hide
    ; ---- game vars ----
    LDA #${'$'}00
    STA score0
    STA score1
    STA score2
    STA score3
    STA framectr
    STA state
    STA invinctr
    LDA #${'$'}A5
    STA prng
    LDA #${'$'}03
    STA lives
    ; ---- title text (rendering off) ----
    LDA #<dg_title1
    STA txtlo
    LDA #>dg_title1
    STA txthi
    LDA #${'$'}8A
    STA nmlo
    LDA #${'$'}21
    STA nmhi
    LDA #${'$'}0B
    STA txtlen
    JSR dg_write_str
    LDA #<dg_title2
    STA txtlo
    LDA #>dg_title2
    STA txthi
    LDA #${'$'}CA
    STA nmlo
    LDA #${'$'}21
    STA nmhi
    LDA #${'$'}0B
    STA txtlen
    JSR dg_write_str
""".trimIndent().lines().joinToString("\n") { "    $it" } + "\n"

    /** Per-frame: state machine (title / play / game over) + HUD refresh. */
    private val nmiCode: String = """
    JSR read_pad
    INC framectr
    LDA state
    BEQ dg_is_title
    CMP #${'$'}01
    BEQ dg_is_play
    JSR dg_go_logic
    JMP dg_nmi_tail
dg_is_title:
    JSR dg_title_logic
    JMP dg_nmi_tail
dg_is_play:
    JSR dg_play_logic
dg_nmi_tail:
    JSR dg_update_hud
    ; the init/game-over text and palette writes leave the PPU address
    ; latch pointed into the nametable; re-zero the scroll every frame so
    ; the background renders from the top-left (the scaffold never does).
    LDA #$00
    STA $2005
    STA $2005
""".trimIndent().lines().joinToString("\n") { "    $it" } + "\n"

    private val tailCode: String = """
; ---- STAR DODGER subroutines ----

; write txtlen tiles from (txtlo) to the nametable at (nmhi/nmlo).
; Call with rendering off, or from inside the NMI (vblank).
dg_write_str:
    LDA ${'$'}2002
    LDA nmhi
    STA ${'$'}2006
    LDA nmlo
    STA ${'$'}2006
    LDY #${'$'}00
dg_ws:
    LDA (txtlo),Y
    STA ${'$'}2007
    INY
    CPY txtlen
    BNE dg_ws
    RTS

; title state: START begins the game (edge-triggered)
dg_title_logic:
    LDA padstate
    AND #${'$'}10
    BEQ dg_tl_none
    LDA padprev
    AND #${'$'}10
    BNE dg_tl_none
    JSR dg_start_game
dg_tl_none:
    LDA padstate
    STA padprev
    RTS

; gameplay state, one frame
dg_play_logic:
    JSR dg_move_player
    LDA invinctr
    BEQ dg_pl_noinv
    DEC invinctr
    BNE dg_pl_blink
    LDA dg_py
    STA ${'$'}0200
    JMP dg_pl_noinv
dg_pl_blink:
    LDA framectr
    AND #${'$'}04
    BEQ dg_pl_show
    LDA #${'$'}FF
    STA ${'$'}0200
    JMP dg_pl_noinv
dg_pl_show:
    LDA dg_py
    STA ${'$'}0200
dg_pl_noinv:
    JSR dg_update_meteors
    JSR dg_update_stars
    LDA padstate
    STA padprev
    RTS

; D-pad moves the ship 2px/frame, clamped to the play area
dg_move_player:
    LDA padstate
    AND #${'$'}08
    BEQ dg_mp_noup
    LDA dg_py
    SEC
    SBC #${'$'}02
    CMP #${'$'}10
    BCC dg_mp_noup
    STA dg_py
    STA ${'$'}0200
dg_mp_noup:
    LDA padstate
    AND #${'$'}04
    BEQ dg_mp_nodown
    LDA dg_py
    CLC
    ADC #${'$'}02
    CMP #${'$'}E1
    BCS dg_mp_nodown
    STA dg_py
    STA ${'$'}0200
dg_mp_nodown:
    LDA padstate
    AND #${'$'}02
    BEQ dg_mp_noleft
    LDA dg_px
    SEC
    SBC #${'$'}02
    CMP #${'$'}08
    BCC dg_mp_noleft
    STA dg_px
    STA ${'$'}0203
dg_mp_noleft:
    LDA padstate
    AND #${'$'}01
    BEQ dg_mp_noright
    LDA dg_px
    CLC
    ADC #${'$'}02
    CMP #${'$'}F9
    BCS dg_mp_noright
    STA dg_px
    STA ${'$'}0203
dg_mp_noright:
    RTS

; meteors fall; respawn at the top when they leave the bottom.
; A meteor overlapping the ship (while vulnerable) costs a life.
dg_update_meteors:
    LDX #${'$'}00
dg_um_loop:
    LDA dg_mspeed,X
    CLC
    ADC dg_my,X
    STA dg_my,X
    CMP #${'$'}E8
    BCC dg_um_norsp
    JSR dg_meteor_respawn
dg_um_norsp:
    TXA
    ASL A
    ASL A
    TAY
    LDA dg_my,X
    STA ${'$'}0204,Y
    LDA #${'$'}02
    STA ${'$'}0205,Y
    LDA #${'$'}01
    STA ${'$'}0206,Y
    LDA dg_mx,X
    STA ${'$'}0207,Y
    LDA invinctr
    BNE dg_um_next
    LDA dg_my,X
    STA tmp+1
    LDA dg_mx,X
    STA tmp
    TXA
    PHA
    TYA
    PHA
    LDX tmp
    LDA tmp+1
    JSR dg_overlap8
    PLA
    TAY
    PLA
    TAX
    BCC dg_um_next
    JSR dg_player_hit
    LDA state
    CMP #${'$'}01
    BNE dg_um_done
dg_um_next:
    INX
    CPX #${'$'}04
    BNE dg_um_loop
dg_um_done:
    RTS

; respawn meteor X at the top in a random lane with its table speed
dg_meteor_respawn:
    LDA #${'$'}08
    STA dg_my,X
    JSR dg_rand8
    AND #${'$'}F0
    ORA #${'$'}08
    STA dg_mx,X
    LDA dg_met_speed,X
    STA dg_mspeed,X
    RTS

; tiny PRNG: xorshift-ish step mixed with the frame counter
dg_rand8:
    LDA prng
    ASL A
    BCC dg_rnd1
    EOR #${'$'}2D
dg_rnd1:
    CLC
    ADC framectr
    STA prng
    RTS

; stars drift down slowly; overlapping one scores +10 and respawns it
dg_update_stars:
    LDX #${'$'}00
dg_us_loop:
    LDA dg_sact,X
    BEQ dg_us_next
    LDA framectr
    AND #${'$'}01
    BNE dg_us_draw
    INC dg_sy,X
    LDA dg_sy,X
    CMP #${'$'}E8
    BCC dg_us_draw
    JSR dg_star_respawn
    JMP dg_us_next
dg_us_draw:
    TXA
    ASL A
    ASL A
    CLC
    ADC #${'$'}14
    TAY
    LDA dg_sy,X
    STA ${'$'}0200,Y
    LDA #${'$'}03
    STA ${'$'}0201,Y
    LDA #${'$'}02
    STA ${'$'}0202,Y
    LDA dg_sx,X
    STA ${'$'}0203,Y
    LDA dg_sy,X
    STA tmp+1
    LDA dg_sx,X
    STA tmp
    TXA
    PHA
    TYA
    PHA
    LDX tmp
    LDA tmp+1
    JSR dg_overlap8
    PLA
    TAY
    PLA
    TAX
    BCC dg_us_next
    JSR dg_add10
    JSR dg_star_respawn
dg_us_next:
    INX
    CPX #${'$'}03
    BNE dg_us_loop
    RTS

; respawn star X at the top in a random lane
dg_star_respawn:
    LDA #${'$'}08
    STA dg_sy,X
    JSR dg_rand8
    AND #${'$'}F0
    ORA #${'$'}08
    STA dg_sx,X
    LDA #${'$'}01
    STA dg_sact,X
    RTS

; 8x8 box overlap vs the player sprite. A=entY, X=entX. C=1 on overlap.
dg_overlap8:
    STX tmp
    STA tmp+1
    LDA ${'$'}0203
    SEC
    SBC tmp
    BPL dg_ox_ok
    EOR #${'$'}FF
    CLC
    ADC #${'$'}01
dg_ox_ok:
    CMP #${'$'}08
    BCS dg_no_ov
    LDA ${'$'}0200
    SEC
    SBC tmp+1
    BPL dg_oy_ok
    EOR #${'$'}FF
    CLC
    ADC #${'$'}01
dg_oy_ok:
    CMP #${'$'}08
    BCS dg_no_ov
    SEC
    RTS
dg_no_ov:
    CLC
    RTS

; +10 to the 4-digit score with decimal carry
dg_add10:
    LDX #${'$'}01
dg_a10:
    INC score0,X
    LDA score0,X
    CMP #${'$'}0A
    BCC dg_a10_done
    LDA #${'$'}00
    STA score0,X
    INX
    CPX #${'$'}04
    BCC dg_a10
dg_a10_done:
    RTS

; meteor hit: lose a life, hide its icon, 90 frames of mercy invincibility.
; Losing the last life ends the game. Preserves X.
dg_player_hit:
    TXA
    PHA
    DEC lives
    LDA #${'$'}5A
    STA invinctr
    LDA lives
    ASL A
    ASL A
    CLC
    ADC #${'$'}30
    TAX
    LDA #${'$'}FF
    STA ${'$'}0200,X
    PLA
    TAX
    LDA lives
    BEQ dg_ph_dead
    RTS
dg_ph_dead:
    JSR dg_do_gameover
    RTS

; game over: freeze, hide the playfield sprites, draw the text next frame
dg_do_gameover:
    LDA #${'$'}02
    STA state
    LDA #${'$'}00
    STA dg_godrawn
    LDX #${'$'}00
    LDA #${'$'}FF
dg_go_hide:
    STA ${'$'}0200,X
    INX
    INX
    INX
    INX
    CPX #${'$'}20
    BNE dg_go_hide
    RTS

; game-over state: draw the text once, then wait for START
dg_go_logic:
    LDA dg_godrawn
    BNE dg_go_start
    INC dg_godrawn
    LDA #<dg_gameov1
    STA txtlo
    LDA #>dg_gameov1
    STA txthi
    LDA #${'$'}AB
    STA nmlo
    LDA #${'$'}21
    STA nmhi
    LDA #${'$'}09
    STA txtlen
    JSR dg_write_str
    LDA #<dg_title2
    STA txtlo
    LDA #>dg_title2
    STA txthi
    LDA #${'$'}EA
    STA nmlo
    LDA #${'$'}21
    STA nmhi
    LDA #${'$'}0B
    STA txtlen
    JSR dg_write_str
dg_go_start:
    LDA padstate
    AND #${'$'}10
    BEQ dg_go_none
    LDA padprev
    AND #${'$'}10
    BNE dg_go_none
    JSR dg_start_game
    JMP dg_go_done
dg_go_none:
dg_go_done:
    LDA padstate
    STA padprev
    RTS

; (re)start a run: blank the text rows, reset vars, spawn everything
dg_start_game:
    LDA ${'$'}2002
    LDA #${'$'}21
    STA ${'$'}2006
    LDA #${'$'}8A
    STA ${'$'}2006
    LDX #${'$'}0B
dg_sg_b1:
    LDA #${'$'}00
    STA ${'$'}2007
    DEX
    BNE dg_sg_b1
    LDA #${'$'}21
    STA ${'$'}2006
    LDA #${'$'}AB
    STA ${'$'}2006
    LDX #${'$'}09
dg_sg_b2:
    LDA #${'$'}00
    STA ${'$'}2007
    DEX
    BNE dg_sg_b2
    LDA #${'$'}21
    STA ${'$'}2006
    LDA #${'$'}CA
    STA ${'$'}2006
    LDX #${'$'}0B
dg_sg_b3:
    LDA #${'$'}00
    STA ${'$'}2007
    DEX
    BNE dg_sg_b3
    LDA #${'$'}21
    STA ${'$'}2006
    LDA #${'$'}EA
    STA ${'$'}2006
    LDX #${'$'}0B
dg_sg_b4:
    LDA #${'$'}00
    STA ${'$'}2007
    DEX
    BNE dg_sg_b4
    LDA #${'$'}00
    STA score0
    STA score1
    STA score2
    STA score3
    STA invinctr
    LDA #${'$'}03
    STA lives
    LDA #${'$'}01
    STA state
    LDA #${'$'}C8
    STA dg_py
    STA ${'$'}0200
    LDA #${'$'}01
    STA ${'$'}0201
    LDA #${'$'}00
    STA ${'$'}0202
    LDA #${'$'}78
    STA dg_px
    STA ${'$'}0203
    LDA #${'$'}08
    STA tmp2
    LDX #${'$'}00
dg_sg_met:
    JSR dg_meteor_respawn
    LDA tmp2
    STA dg_my,X
    CLC
    ADC #${'$'}30
    STA tmp2
    INX
    CPX #${'$'}04
    BNE dg_sg_met
    LDA #${'$'}10
    STA tmp2
    LDX #${'$'}00
dg_sg_star:
    JSR dg_star_respawn
    LDA tmp2
    STA dg_sy,X
    CLC
    ADC #${'$'}46
    STA tmp2
    INX
    CPX #${'$'}03
    BNE dg_sg_star
    LDX #${'$'}00
dg_sg_dig:
    TXA
    ASL A
    ASL A
    CLC
    ADC #${'$'}20
    TAY
    LDA #${'$'}08
    STA ${'$'}0200,Y
    LDA #${'$'}10
    STA ${'$'}0201,Y
    LDA #${'$'}00
    STA ${'$'}0202,Y
    LDA dg_dig_x,X
    STA ${'$'}0203,Y
    INX
    CPX #${'$'}04
    BNE dg_sg_dig
    LDX #${'$'}00
dg_sg_liv:
    TXA
    ASL A
    ASL A
    CLC
    ADC #${'$'}30
    TAY
    LDA #${'$'}08
    STA ${'$'}0200,Y
    LDA #${'$'}01
    STA ${'$'}0201,Y
    LDA #${'$'}00
    STA ${'$'}0202,Y
    LDA dg_liv_x,X
    STA ${'$'}0203,Y
    INX
    CPX #${'$'}03
    BNE dg_sg_liv
    RTS

; score digits -> HUD sprites 8..11 (tile 16 + digit), thousands leftmost.
; (DEX sits right before the BPL: INY clobbers N, so the branch must test
; the flags DEX just set — a real 6502 gotcha, not an emulator quirk.)
dg_update_hud:
    LDX #${'$'}03
    LDY #${'$'}00
dg_uh:
    LDA score0,X
    CLC
    ADC #${'$'}10
    STA ${'$'}0221,Y
    INY
    INY
    INY
    INY
    DEX
    BPL dg_uh
    RTS

dg_met_speed:
    .byte ${'$'}01,${'$'}02,${'$'}02,${'$'}03
dg_dig_x:
    .byte ${'$'}10,${'$'}18,${'$'}20,${'$'}28
dg_liv_x:
    .byte ${'$'}D8,${'$'}E0,${'$'}E8
dg_title1:
    .byte T_S,T_T,T_A,T_R,${'$'}00,T_D,T_O,T_D,T_G,T_E,T_R
dg_title2:
    .byte T_P,T_R,T_E,T_S,T_S,${'$'}00,T_S,T_T,T_A,T_R,T_T
dg_gameov1:
    .byte T_G,T_A,T_M,T_E,${'$'}00,T_O,T_V,T_E,T_R
""".trimIndent().lines().joinToString("\n") { "    $it" } + "\n"
}
