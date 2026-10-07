package omni.nes.gen

import omni.nes.asm.Assembler
import omni.nes.cpu.Cpu6502
import omni.nes.cpu.StubBus
import omni.nes.rom.INes
import omni.nes.rom.RomBuilder
import omni.nes.tools.Headless
import omni.nes.validate.NesValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unsigned byte read from emulated RAM. */
private fun peek(ram: ByteArray, a: Int): Int = ram[a].toInt() and 0xFF

/**
 * STAR DODGER dogfood: the first real game authored end-to-end through the
 * game-maker pipeline (brief -> template draft -> assemble -> iNES ROM ->
 * validator -> headless play).
 *
 * These tests execute the actual ROM on the repo's real 6502 + 2C02 — no
 * mocks of the game logic. Controller input is scripted through
 * [StubBus.pad1], so the tests genuinely *play*: steering into meteors,
 * chasing stars, pressing START.
 */
class DodgerGameTest {

    // NES controller bits, matching the game's read_pad order.
    private val PAD_RIGHT = 0x01
    private val PAD_LEFT = 0x02
    private val PAD_START = 0x10

    /** A scripted play session: the ROM booted on the real CPU+PPU. */
    private inner class Session(rom: ByteArray) {
        val bus: StubBus
        private val cpu: Cpu6502
        var frame = 0
            private set

        init {
            val r = INes.parse(rom)
            bus = StubBus(r.prg, r.prg.size == 16384, r.chr, r.mirroring)
            cpu = Cpu6502(bus)
            cpu.reset()
        }

        /** Advance [n] frames; [pad] maps (frame, bus) -> pad bits. */
        fun run(n: Int, pad: (Int, StubBus) -> Int = { _, _ -> 0 }) {
            repeat(n) {
                var guard = 0
                var framed = false
                while (!framed) {
                    if (++guard > 300_000) throw AssertionError("frame $frame never completed")
                    when (val s = cpu.step()) {
                        is Cpu6502.StepResult.Ok -> Unit
                        else -> throw AssertionError("CPU stopped at frame $frame: $s")
                    }
                    if (bus.ppu.takeFrame()) {
                        framed = true
                        frame++
                        // Latched by the next NMI's controller strobe.
                        bus.pad1 = pad(frame, bus)
                    }
                    if (bus.ppu.takeNmi()) cpu.nmi()
                }
            }
        }

        private fun ramAt(a: Int) = peek(bus.ram, a)
        val state get() = ramAt(0x06) // 0=title 1=play 2=gameover
        val lives get() = ramAt(0x05)
        val score get() = ramAt(0x00) + 10 * ramAt(0x01) + 100 * ramAt(0x02) + 1000 * ramAt(0x03)
        val px get() = ramAt(0x361)
        val py get() = ramAt(0x360)
        fun meteorX(i: Int) = ramAt(0x310 + i)
        fun starX(i: Int) = ramAt(0x340 + i)
    }

    /**
     * The exact brief -> ROM path the in-app one-tap build takes:
     * keyword-scanned spec, template draft, assemble, ROM wrapped with the
     * draft's own CHR (the NesGameMaker.CHR fix under test).
     */
    private fun oneTapRom(brief: String): Pair<GameSpec, ByteArray> {
        val spec = briefToSpec(brief)
        val gen = MockGenerator()
        val asm = gen.generate(spec)
        val prg = Assembler.assemble(asm).bytes
        return spec to RomBuilder.build(prg, gen.chr())
    }

    // ------------------------------------------------------------------
    // brief -> spec routing (the gap that used to swallow every brief)
    // ------------------------------------------------------------------

    @Test
    fun briefRoutesToDodgerMechanics() {
        val spec = briefToSpec("Dodge the meteors and collect stars for a high score!")
        assertEquals("Dodge the meteors and collect stars for a high score!", spec.title)
        assertTrue(spec.mechanics.contains("dodge"))
        assertTrue(spec.mechanics.contains("collect"))
        val asm = MockGenerator().generate(spec)
        assertTrue("dodger template not selected", asm.contains("dg_start_game"))
    }

    @Test
    fun emptyBriefIsUntitled() {
        assertEquals("UNTITLED", briefToSpec("   ").title)
    }

    @Test
    fun plainBriefStillFallsBackToDpadDemo() {
        val asm = MockGenerator().generate(briefToSpec("just a game"))
        assertFalse(asm.contains("dg_start_game"))
    }

    @Test
    fun dodgerGeneratorIsAChrProvider() {
        val gen = DodgerGenerator()
        val asm = gen.generate(GameSpec("X", mechanics = listOf("dodge")))
        assertTrue(asm.contains("dg_start_game"))
        assertEquals(8192, gen.chr().size)
    }

    // ------------------------------------------------------------------
    // pipeline: a GREEN, structurally valid ROM
    // ------------------------------------------------------------------

    @Test
    fun oneTapPipelineProducesGreenRom() {
        val (spec, rom) = oneTapRom("Dodge meteors, collect stars, 3 lives")
        assertTrue(spec.mechanics.contains("dodge"))

        // iNES structure: magic, 1x16KB PRG, 1x8KB CHR, mapper 0.
        assertEquals('N'.code.toByte(), rom[0])
        assertEquals('E'.code.toByte(), rom[1])
        assertEquals('S'.code.toByte(), rom[2])
        assertEquals(0x1A.toByte(), rom[3])
        assertEquals(1, rom[4].toInt())
        assertEquals(1, rom[5].toInt())
        assertEquals(0, rom[6].toInt() and 0xF0)
        assertEquals(16 + 16384 + 8192, rom.size)

        // The validator gate the app enforces: zero FAILs.
        val report = NesValidator().validate(rom)
        assertTrue("validator RED: ${report.fails()}", report.passed)

        // The same headless PPU path the app screenshots: 30 real frames.
        val run = Headless.run(rom, 30, keepFrames = true)
        assertEquals(30, run.hashes.size)
        assertEquals(30, run.frames.size)
        assertNull("headless run stopped early: ${run.stoppedEarly}", run.stoppedEarly)
    }

    @Test
    fun generationLoopSucceedsFirstTry() {
        val loop = GenerationLoop(
            MockGenerator(),
            RuleBasedRepair(),
            LoopConfig(writeAttemptLog = false),
        )
        val result = loop.run(briefToSpec("Star dodger: dodge meteors, collect stars"), "gen-dodger")
        assertTrue("loop note: ${result.note}", result.success)
        assertEquals(1, result.attempts.size)
        assertTrue(result.finalRom != null)
    }

    // ------------------------------------------------------------------
    // gameplay: actually playing the ROM
    // ------------------------------------------------------------------

    @Test
    fun bootsToTitleScreen() {
        val (_, rom) = oneTapRom("dodge meteors collect stars")
        val s = Session(rom)
        s.run(30)
        assertEquals(0, s.state)
        assertEquals(3, s.lives)
        assertEquals(0, s.score)
    }

    @Test
    fun startButtonBeginsPlay() {
        val (_, rom) = oneTapRom("dodge meteors collect stars")
        val s = Session(rom)
        s.run(40) { f, _ -> if (f in 10..12) PAD_START else 0 }
        assertEquals(1, s.state)
        assertEquals(3, s.lives)
        // Player ship parked at its start position.
        assertEquals(0x78, s.px)
        assertEquals(0xC8, s.py)
    }

    @Test
    fun dpadMovesPlayer() {
        val (_, rom) = oneTapRom("dodge meteors collect stars")
        val s = Session(rom)
        val startPad: (Int, StubBus) -> Int = { f, _ -> if (f in 10..12) PAD_START else 0 }
        s.run(20, startPad)
        val x0 = s.px
        s.run(30) { _, _ -> PAD_RIGHT }
        assertTrue("player did not move right: $x0 -> ${s.px}", s.px > x0 + 30)
        s.run(30) { _, _ -> PAD_LEFT }
        assertTrue("player did not move left back: ${s.px}", s.px < x0 + 30)
    }

    @Test
    fun steeringIntoMeteorCostsALife() {
        val (_, rom) = oneTapRom("dodge meteors collect stars")
        val s = Session(rom)
        s.run(600) { f, b ->
            if (f in 10..12) PAD_START
            else if (((b.ram[0x06].toInt() and 0xFF)) != 1) 0
            else {
                // Chase meteor 0's lane; an overlap is then inevitable.
                val px = (b.ram[0x361].toInt() and 0xFF)
                val mx = (b.ram[0x310].toInt() and 0xFF)
                when {
                    mx + 2 < px -> PAD_LEFT
                    mx - 2 > px -> PAD_RIGHT
                    else -> 0
                }
            }
        }
        assertTrue("no meteor hit in 600 frames (lives=${s.lives})", s.lives < 3)
        // (deliberately kamikaze: the run may even end in game over)
    }

    @Test
    fun catchingStarScoresPoints() {
        val (_, rom) = oneTapRom("dodge meteors collect stars")
        val s = Session(rom)
        // Park under star 0 and let it drift down onto the ship. No dodging:
        // the point is to prove the pickup works; 3 lives absorb strays.
        s.run(1200) { f, b ->
            if (f in 10..12) PAD_START
            else if (((b.ram[0x06].toInt() and 0xFF)) != 1) 0
            else {
                val px = (b.ram[0x361].toInt() and 0xFF)
                val sx = (b.ram[0x340].toInt() and 0xFF)
                when {
                    sx + 2 < px -> PAD_LEFT
                    sx - 2 > px -> PAD_RIGHT
                    else -> 0
                }
            }
        }
        assertTrue("no star collected in 1200 frames (score=${s.score})", s.score > 0)
        assertEquals(0, s.score % 10) // stars are worth exactly 10
    }

    @Test
    fun hudScoreDigitsReadThousandsFirst() {
        val (_, rom) = oneTapRom("dodge meteors collect stars")
        val s = Session(rom)
        // Same star-chasing pilot as catchingStarScoresPoints: the score must
        // come from real pickups, not poked RAM.
        s.run(1200) { f, b ->
            if (f in 10..12) PAD_START
            else if (((b.ram[0x06].toInt() and 0xFF)) != 1) 0
            else {
                val px = (b.ram[0x361].toInt() and 0xFF)
                val sx = (b.ram[0x340].toInt() and 0xFF)
                when {
                    sx + 2 < px -> PAD_LEFT
                    sx - 2 > px -> PAD_RIGHT
                    else -> 0
                }
            }
        }
        assertTrue("no star collected (score=${s.score})", s.score > 0)
        // HUD sprites 8..11 sit left-to-right at X=$10..$28; their tiles must
        // read thousands -> ones, matching the score in RAM.
        val digits = listOf(s.score / 1000, (s.score / 100) % 10, (s.score / 10) % 10, s.score % 10)
        val tiles = listOf(0x221, 0x225, 0x229, 0x22D).map { peek(s.bus.ram, it) }
        assertEquals(
            "HUD digits ${tiles.map { it - 16 }} do not read thousands-first for score ${s.score}",
            digits.map { 16 + it },
            tiles,
        )
    }

    @Test
    fun threeHitsEndsTheGameAndStartRestarts() {
        val (_, rom) = oneTapRom("dodge meteors collect stars")
        val s = Session(rom)
        // Kamikaze: chase the nearest meteor until the game ends.
        s.run(4000) { f, b ->
            when {
                f in 10..12 -> PAD_START
                ((b.ram[0x06].toInt() and 0xFF)) != 1 -> 0
                else -> {
                    val px = (b.ram[0x361].toInt() and 0xFF)
                    var best = 0
                    var bestDy = 255
                    for (i in 0 until 4) {
                        val my = (b.ram[0x300 + i].toInt() and 0xFF)
                        val dy = (200 - my + 256) % 256
                        if (dy < bestDy) {
                            bestDy = dy
                            best = i
                        }
                    }
                    val mx = (b.ram[0x310 + best].toInt() and 0xFF)
                    when {
                        mx + 2 < px -> PAD_LEFT
                        mx - 2 > px -> PAD_RIGHT
                        else -> 0
                    }
                }
            }
        }
        assertEquals("never reached game over (lives=${s.lives}, state=${s.state})", 0, s.lives)
        assertEquals(2, s.state)

        // START on the game-over screen restarts cleanly. Keep the window
        // tight: a fresh run is vulnerable, and a stray meteor must not
        // be allowed to muddy the restart assertions.
        val f0 = s.frame
        s.run(12) { f, _ -> if (f - f0 in 5..7) PAD_START else 0 }
        assertEquals(1, s.state)
        assertEquals(3, s.lives)
        assertEquals(0, s.score)
    }

    // ------------------------------------------------------------------
    // CHR: the game ships the tiles it was drawn against
    // ------------------------------------------------------------------

    @Test
    fun dodgerChrHasCustomTilesAndLetters() {
        val chr = DodgerChr.data()
        assertEquals(8192, chr.size)
        assertEquals(12, DodgerChr.letterTile.size)
        val def = ChrData.default()
        assertFalse(
            "tile 1 (ship) identical to default player block",
            chr.copyOfRange(16, 32).contentEquals(def.copyOfRange(16, 32)),
        )
        for ((ch, t) in DodgerChr.letterTile) {
            assertTrue(
                "letter '$ch' tile $t is blank",
                chr.copyOfRange(t * 16, t * 16 + 16).any { it != 0.toByte() },
            )
        }
        // Digits shared with the default set (HUD score tiles 16..25).
        for (d in 0..9) {
            assertTrue(
                "digit $d differs from default",
                chr.copyOfRange((16 + d) * 16, (17 + d) * 16)
                    .contentEquals(def.copyOfRange((16 + d) * 16, (17 + d) * 16)),
            )
        }
    }
}
