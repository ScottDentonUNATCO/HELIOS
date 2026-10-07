package omni.nes.gen

import omni.nes.asm.Assembler
import omni.nes.cpu.Cpu6502
import omni.nes.cpu.StubBus
import omni.nes.img.Png
import omni.nes.rom.INes
import omni.nes.rom.RomBuilder
import omni.nes.validate.NesValidator
import java.io.File
import java.util.Base64

/** Unsigned byte read from emulated RAM. */
private fun peek(ram: ByteArray, a: Int): Int = ram[a].toInt() and 0xFF

/**
 * CLI: runs STAR DODGER through the exact one-tap pipeline the app uses
 * (brief -> [briefToSpec] -> template draft -> assemble -> [RomBuilder]
 * with the draft's own CHR -> [NesValidator]) and writes the evidence:
 *
 * - nes/roms/gen-dodger.asm            the drafted 6502 source
 * - nes/roms/gen-dodger.nes            the playable ROM
 * - nes/roms/gen-dodger.nes.b64        base64 twin (repo convention)
 * - nes/roms/gen-dodger-validation.txt validator findings
 * - nes/roms/gen-dodger-attempts.log   generation attempt log
 * - nes/evidence/dodger/ (PNGs)   real PPU frames: title, gameplay,
 *                                      game over (scripted play sessions)
 *
 * Run from the repo root: the ROM this writes is byte-identical to what
 * the in-app ONE-TAP BUILD produces for the same brief.
 */
fun main() {
    val brief = "Star Dodger: dodge the meteors, collect the stars, 3 lives"
    val spec = briefToSpec(brief)
    println("spec: title='${spec.title}' mechanics=${spec.mechanics}")

    // --- draft -> assemble -> ROM (the NesGameMaker.makeGame core) ---
    val gen = MockGenerator()
    val asm = gen.generate(spec)
    check("dg_start_game" in asm) { "dodger template was not selected" }
    val prg = Assembler.assemble(asm).bytes
    val rom = RomBuilder.build(prg, gen.chr())
    println("assembled: prg=${prg.size} bytes, rom=${rom.size} bytes, asm_sha=${GenerationLoop.sha256(asm).take(16)}")

    // --- validator gate ---
    val report = NesValidator().validate(rom)
    println("validator passed=${report.passed}")
    for (f in report.findings) println("  ${f.severity} ${f.ruleId}: ${f.message}")
    check(report.passed) { "ROM failed validation" }

    // --- artifacts ---
    val roms = File("nes/roms").also { it.mkdirs() }
    File(roms, "gen-dodger.asm").writeText(asm)
    File(roms, "gen-dodger.nes").writeBytes(rom)
    File(roms, "gen-dodger.nes.b64").writeText(Base64.getEncoder().encodeToString(rom))
    File(roms, "gen-dodger-validation.txt").writeText(
        buildString {
            appendLine("gen-dodger.nes validation evidence")
            appendLine("passed=${report.passed}")
            for (f in report.findings) appendLine("${f.severity} ${f.ruleId}: ${f.message}")
        },
    )
    val loop = GenerationLoop(
        MockGenerator(),
        RuleBasedRepair(),
        LoopConfig(logDir = roms),
    )
    val result = loop.run(spec, "gen-dodger")
    println("loop: success=${result.success} attempts=${result.attempts.size} note=${result.note}")

    // --- scripted play sessions, captured from the real PPU ---
    val ev = File("nes/evidence/dodger").also { it.mkdirs() }
    showcase(rom, ev)
    gameOverShot(rom, ev)
    println("evidence in ${ev.path}")
}

private fun openSession(rom: ByteArray): Pair<StubBus, Cpu6502> {
    val r = INes.parse(rom)
    val bus = StubBus(r.prg, r.prg.size == 16384, r.chr, r.mirroring)
    val cpu = Cpu6502(bus)
    cpu.reset()
    return bus to cpu
}

/** Step until [frames] vblanks pass; [pad] supplies controller bits per frame. */
private fun playFrames(
    bus: StubBus,
    cpu: Cpu6502,
    frames: Int,
    pad: (Int) -> Int,
    onFrame: ((Int) -> Unit)? = null,
) {
    var done = 0
    var guard = 0
    while (done < frames) {
        if (++guard > frames * 300_000) error("frame $done never completed")
        when (val s = cpu.step()) {
            is Cpu6502.StepResult.Ok -> Unit
            else -> error("CPU stopped: $s")
        }
        if (bus.ppu.takeFrame()) {
            done++
            bus.pad1 = pad(done)
            onFrame?.invoke(done)
        }
        if (bus.ppu.takeNmi()) cpu.nmi()
    }
}

/** A competent-enough pilot: starts the game, dodges close meteors, chases stars. */
private fun pilotAi(f: Int, bus: StubBus): Int {
    if (f in 10..12) return 0x10 // START
    val ram = bus.ram
    if ((peek(ram, 0x06)) != 1) return 0
    val px = peek(ram, 0x361)
    // Dodge: nearest meteor in the 80px band above the ship and close in X.
    var threatX = -1
    var threatDy = 999
    for (i in 0 until 4) {
        val my = peek(ram, 0x300 + i)
        val mx = peek(ram, 0x310 + i)
        val dy = 200 - my
        val dx = kotlin.math.abs(mx - px)
        if (dy in 1..80 && dx < 20 && dy < threatDy) {
            threatDy = dy
            threatX = mx
        }
    }
    if (threatX >= 0) return if (threatX < px) 0x01 else 0x02
    // Chase: nearest star's lane.
    var sx = px
    var best = 999
    for (i in 0 until 3) {
        val dx = kotlin.math.abs((peek(ram, 0x340 + i)) - px)
        if (dx < best) {
            best = dx
            sx = peek(ram, 0x340 + i)
        }
    }
    return when {
        sx + 3 < px -> 0x02
        sx - 3 > px -> 0x01
        else -> 0
    }
}

/** Title + gameplay frames from a real play session. */
private fun showcase(rom: ByteArray, ev: File) {
    val (bus, cpu) = openSession(rom)
    val shots = mutableMapOf<Int, IntArray>()
    playFrames(bus, cpu, 400, { f -> pilotAi(f, bus) }) { f ->
        if (f in setOf(5, 60, 200, 399)) shots[f] = bus.ppu.framebuffer.copyOf()
    }
    val names = mapOf(5 to "dodger-title.png", 60 to "dodger-play-1.png", 200 to "dodger-play-2.png", 399 to "dodger-play-3.png")
    for ((f, name) in names) {
        val px = shots[f] ?: error("missing frame $f")
        File(ev, name).writeBytes(Png.encode(256, 240, px))
        println("  frame $f -> $name")
    }
    val ram = bus.ram
    val fullScore = peek(ram, 0x00) + 10 * peek(ram, 0x01) + 100 * peek(ram, 0x02) + 1000 * peek(ram, 0x03)
    println("  after 400 frames: state=${peek(ram, 0x06)} lives=${peek(ram, 0x05)} score=$fullScore")
}

/** Kamikaze run into meteors until GAME OVER, then capture the screen. */
private fun gameOverShot(rom: ByteArray, ev: File) {
    val (bus, cpu) = openSession(rom)
    var frames = 0
    var guard = 0
    var shot: IntArray? = null
    var shotAt = 0
    while (shot == null) {
        if (++guard > 6000 * 300_000) error("never reached game over")
        when (val s = cpu.step()) {
            is Cpu6502.StepResult.Ok -> Unit
            else -> error("CPU stopped: $s")
        }
        if (bus.ppu.takeFrame()) {
            frames++
            val ram = bus.ram
            bus.pad1 = when {
                frames in 10..12 -> 0x10
                (peek(ram, 0x06)) != 1 -> 0
                else -> {
                    // Chase the lowest meteor: get hit on purpose.
                    var bi = 0
                    var bd = -1
                    for (i in 0 until 4) {
                        val my = peek(ram, 0x300 + i)
                        if (my > bd) {
                            bd = my
                            bi = i
                        }
                    }
                    val px = peek(ram, 0x361)
                    val mx = peek(ram, 0x310 + bi)
                    when {
                        mx + 2 < px -> 0x02
                        mx - 2 > px -> 0x01
                        else -> 0
                    }
                }
            }
            if ((peek(bus.ram, 0x06)) == 2 && frames > shotAt + 10 && shotAt == 0) {
                shotAt = frames // let the GAME OVER text draw (next NMI)
            }
            if (shotAt != 0 && frames >= shotAt + 12) {
                shot = bus.ppu.framebuffer.copyOf()
            }
        }
        if (bus.ppu.takeNmi()) cpu.nmi()
    }
    File(ev, "dodger-gameover.png").writeBytes(Png.encode(256, 240, shot))
    println("  game over at frame $shotAt -> dodger-gameover.png")
}
