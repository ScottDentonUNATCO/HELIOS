import omni.nes.asm.Assembler
import omni.nes.gen.*
import omni.nes.ppu.Ppu2C02
import omni.nes.rom.Mirroring
import omni.nes.rom.RomBuilder
import omni.nes.tools.Headless
import omni.nes.validate.NesValidator
import omni.nes.validate.Severity
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * STRESS 3 — NES pipeline (REAL classes throughout).
 *
 *  A. Validator fuzz: malformed inputs (truncated ROMs, bad headers, garbage
 *     bytes, bit-flips) — validator must REJECT, never throw uncaught.
 *     Crashes (uncaught Throwable escaping validate) = FAIL.
 *  B. GenerationLoop batch: MockGenerator + all 7 SlopVariants through
 *     RuleBasedRepair and RepromptRepair + adversarial generators.
 *     Asserts: attempts NEVER exceed 5; success IFF final ROM passes validator.
 *  C. PPU frame-rendering throughput via Headless.run (full CPU+PPU emulation),
 *     plus raw Ppu2C02.advanceDots throughput.
 */
fun buildValidRom(): ByteArray {
    val mock = MockGenerator()
    val spec = GameSpec("stress", mechanics = listOf("move"))
    val prg = Assembler.assemble(mock.generate(spec)).bytes
    return RomBuilder.build(prg, mock.chr())
}

fun main() {
    val out = StringBuilder()
    fun log(s: String) { out.appendLine(s); println(s) }
    val validator = NesValidator()

    // ---------- A. validator fuzz on malformed inputs ----------
    val valid = buildValidRom()
    val baseReport = validator.validate(valid)
    log("[A0] sanity: valid MockGenerator ROM passes=${baseReport.passed} fails=${baseReport.fails().size}")

    val cases = mutableListOf<Pair<String, ByteArray>>()
    val rnd = java.util.Random(31337)
    cases += "empty" to ByteArray(0)
    for (n in listOf(1, 5, 15, 16, 17, 100, 1024)) {
        val b = ByteArray(n); rnd.nextBytes(b); cases += "garbage-$n" to b
    }
    // truncated valid ROM at many cut points
    for (cut in listOf(1, 10, 15, 16, 100, 1000, 16384, 16 + 16384, 16 + 16384 + 4096, valid.size - 1, valid.size - 100)) {
        if (cut < valid.size) cases += "truncated-$cut" to valid.copyOf(cut)
    }
    // bad headers: wrong magic, zero PRG banks, huge bank counts
    for (h in listOf("XES\u001A", "NES\u001B", "NES\u001A")) {
        val b = valid.copyOf(); b[0] = h[0].code.toByte(); b[1] = h[1].code.toByte()
        b[2] = h[2].code.toByte(); b[3] = h[3].code.toByte(); cases += "badmagic-${h[3].code}" to b
    }
    val zeroPrg = valid.copyOf(); zeroPrg[4] = 0; cases += "zero-prg-banks" to zeroPrg
    val bigPrg = valid.copyOf(); bigPrg[4] = 255.toByte(); cases += "huge-prg-banks" to bigPrg
    val bigChr = valid.copyOf(); bigChr[5] = 100.toByte(); cases += "huge-chr-banks" to bigChr
    // bit flips in header and PRG
    for (i in 0 until 40) {
        val b = valid.copyOf()
        val pos = rnd.nextInt(valid.size)
        b[pos] = (b[pos].toInt() xor (1 shl rnd.nextInt(8))).toByte()
        cases += "bitflip-$i" to b
    }
    // oversized random buffers
    for (n in listOf(16 + 32768 + 8192, 65536, 131072)) {
        val b = ByteArray(n); rnd.nextBytes(b); cases += "random-$n" to b
    }
    // valid header, wrong body length
    val shortBody = valid.copyOf(16 + 100); cases += "header-ok-body-short" to shortBody

    var crashed = 0; var rejected = 0; var passedCount = 0; var other = 0
    val crashNames = mutableListOf<String>()
    for ((name, bytes) in cases) {
        try {
            val r = validator.validate(bytes)
            when {
                !r.passed -> rejected++
                else -> { passedCount++; log("[A] NOTE: malformed case '$name' PASSED validation (${r.findings.size} findings)") }
            }
        } catch (t: Throwable) {
            crashed++
            if (crashNames.size < 10) crashNames += "$name -> ${t.javaClass.simpleName}: ${t.message?.take(100)}"
        }
    }
    log("[A] malformed cases: total=${cases.size} rejected=$rejected passed-unexpected=$passedCount crashed=$crashed")
    crashNames.forEach { log("[A] CRASH: $it") }

    // ---------- B. generation-loop batch ----------
    var loopsRun = 0; var loopsOk = 0; var attemptsOver5 = 0; var successMismatch = 0
    var maxAttemptsSeen = 0; var totalWallMs = 0L
    val loopStart = System.currentTimeMillis()
    val slopVariants = SlopVariant.values().toList()
    val batch = mutableListOf<Triple<String, Generator, Repairer>>()
    val mockGen = MockGenerator()
    val spec = GameSpec("stress batch", mechanics = listOf("move"))
    repeat(10) { batch += Triple("mock-clean-$it", mockGen, RuleBasedRepair()) }
    for (v in slopVariants) {
        batch += Triple("slop-$v-rulebased", SlopGenerator(v), RuleBasedRepair())
        batch += Triple("slop-$v-reprompt", SlopGenerator(v), RepromptRepair(spec, SlopGenerator(v)))
    }
    // adversarial generators
    batch += Triple("adv-throw", object : Generator {
        override fun generate(spec: GameSpec): String = throw RuntimeException("boom")
    }, RuleBasedRepair())
    batch += Triple("adv-garbage", object : Generator {
        override fun generate(spec: GameSpec): String = "%%% not asm at all %%%\n".repeat(50)
    }, RuleBasedRepair())
    batch += Triple("adv-empty", object : Generator {
        override fun generate(spec: GameSpec): String = ""
    }, RuleBasedRepair())
    batch += Triple("adv-identical-bad", object : Generator, ChrProvider {
        val bad = SlopGenerator(SlopVariant.ZERO_VECTORS).generate(GameSpec("x", mechanics = listOf("move")))
        override fun chr(): ByteArray = ChrData.default()
        override fun generate(spec: GameSpec): String = bad
    }, RuleBasedRepair())
    // repairer that throws
    batch += Triple("adv-repairer-throws", SlopGenerator(SlopVariant.ZERO_VECTORS), object : Repairer {
        override val name = "ThrowingRepair"
        override fun repair(failed: FailedAttempt): RepairOutcome = throw RuntimeException("repair boom")
    })

    for ((name, gen, rep) in batch) {
        val cfg = LoopConfig(maxAttempts = 5, enableRepair = true, writeAttemptLog = false)
        val t = System.currentTimeMillis()
        val result = try {
            GenerationLoop(gen, rep, cfg).run(GameSpec(name, mechanics = listOf("move")))
        } catch (e: Throwable) {
            log("[B] $name THREW OUT OF LOOP: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            null
        }
        totalWallMs += System.currentTimeMillis() - t
        loopsRun++
        if (result == null) continue
        loopsOk++
        val n = result.attempts.size
        if (n > maxAttemptsSeen) maxAttemptsSeen = n
        if (n > 5) { attemptsOver5++; log("[B] $name EXCEEDED 5 attempts: $n") }
        // success must mean final ROM passes the validator with zero FAILs
        if (result.success) {
            val rom = result.finalRom
            if (rom == null) { successMismatch++; log("[B] $name claims success with null finalRom") }
            else {
                val vr = validator.validate(rom)
                if (!vr.passed) { successMismatch++; log("[B] $name claims success but validator FAILs: ${vr.fails().map { it.ruleId }}") }
            }
        }
    }
    log("[B] loops run=$loopsRun completed-without-throw=$loopsOk max-attempts-seen=$maxAttemptsSeen " +
        "over-5=$attemptsOver5 success-mismatch=$successMismatch avg-wall=${totalWallMs / loopsRun}ms")

    // ---------- C. PPU throughput ----------
    val proofNes = try { java.io.File("/home/hatch/workspace/omni-app/nes/roms/proof.nes").readBytes() } catch (e: Exception) { null }
    if (proofNes != null) {
        val frames = 600
        val t = System.nanoTime()
        val r = Headless.run(proofNes, frameCount = frames)
        val dt = (System.nanoTime() - t) / 1e9
        log("[C] Headless full-emulation: $frames frames in ${"%.2f".format(dt)}s = " +
            "${"%.1f".format(frames / dt)} fps (framesRendered=${r.hashes.size} stoppedEarly=${r.stoppedEarly})")
    } else log("[C] proof.nes missing, skipped headless throughput")
    // raw PPU: 1000 frames of advanceDots with rendering enabled
    val ppu = Ppu2C02(ByteArray(8192), false, Mirroring.VERTICAL)
    ppu.writeReg(1, 0x1E) // bg+sprites on
    ppu.writeReg(0, 0x80) // NMI on
    var framesRendered = 0
    val t2 = System.nanoTime()
    repeat(1000) {
        ppu.advanceDots(Ppu2C02.FRAME_DOTS)
        if (ppu.takeFrame()) framesRendered++
    }
    val dt2 = (System.nanoTime() - t2) / 1e9
    log("[C] raw PPU advanceDots: 1000 frames in ${"%.2f".format(dt2)}s = " +
        "${"%.1f".format(1000 / dt2)} frames/s (takeFrame fired $framesRendered/1000)")

    log("NES-STRESS DONE")
    java.io.File("/home/hatch/workspace/omni-app/v7-work/stress/nes-stress.txt").writeText(out.toString())
}
