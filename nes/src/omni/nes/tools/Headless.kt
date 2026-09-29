package omni.nes.tools

import omni.nes.cpu.Cpu6502
import omni.nes.cpu.StubBus
import omni.nes.img.Png
import omni.nes.rom.INes
import java.io.File
import java.util.zip.CRC32

/**
 * Headless NES driver: runs a ROM's real CPU against the real PPU and
 * captures rendered frames. The NMI is driven by the PPU's own vblank edge
 * (not a fixed countdown), so this is the PPU-timed path — the validator
 * keeps its own countdown-driven path.
 */
object Headless {
    data class RunResult(
        /** CRC32 of the 256x240 framebuffer, one per rendered frame. */
        val hashes: List<Long>,
        /** Copies of framebuffers, only when [keepFrames] was true. */
        val frames: List<IntArray>,
        val cpuCycles: Long,
        val stoppedEarly: String?,
    )

    fun hash(pixels: IntArray): Long {
        val crc = CRC32()
        for (c in pixels) {
            crc.update((c ushr 24) and 0xFF)
            crc.update((c ushr 16) and 0xFF)
            crc.update((c ushr 8) and 0xFF)
            crc.update(c and 0xFF)
        }
        return crc.value
    }

    fun run(nesBytes: ByteArray, frameCount: Int, keepFrames: Boolean = false): RunResult {
        val rom = INes.parse(nesBytes)
        val bus = StubBus(rom.prg, rom.prg.size == 16384, rom.chr, rom.mirroring)
        val cpu = Cpu6502(bus)
        cpu.reset()
        val hashes = mutableListOf<Long>()
        val frames = mutableListOf<IntArray>()
        var framesDone = 0
        var stopped: String? = null
        val cycleCap = frameCount * 29780L + 1_000_000L
        while (framesDone < frameCount && cpu.cycles < cycleCap) {
            when (val r = cpu.step()) {
                is Cpu6502.StepResult.Ok -> Unit
                else -> {
                    stopped = "cpu stopped: $r"
                    break
                }
            }
            if (bus.ppu.takeFrame()) {
                hashes.add(hash(bus.ppu.framebuffer))
                if (keepFrames) frames.add(bus.ppu.framebuffer.copyOf())
                framesDone++
            }
            if (bus.ppu.takeNmi()) cpu.nmi()
        }
        if (stopped == null && framesDone < frameCount) stopped = "cycle cap hit"
        return RunResult(hashes, frames, cpu.cycles, stopped)
    }
}

/**
 * CLI: runs roms/proof.nes headless for 60 frames, dumps PNG evidence to
 * nes/evidence/ppu-frames/, and prints per-frame checksums.
 */
fun main() {
    val nesDir = File("/home/hatch/workspace/omni-app/nes")
    val nes = File(nesDir, "roms/proof.nes").readBytes()
    val outDir = File(nesDir, "evidence/ppu-frames").also { it.mkdirs() }

    val result = Headless.run(nes, frameCount = 60, keepFrames = true)
    println("frames=${result.hashes.size} cycles=${result.cpuCycles} stoppedEarly=${result.stoppedEarly}")

    val dumpAt = setOf(0, 2, 5, 10, 20, 30, 40, 50, 59)
    for ((i, h) in result.hashes.withIndex()) {
        println("frame $i hash=${h.toString(16).padStart(8, '0')}")
        if (i in dumpAt && i < result.frames.size) {
            val png = Png.encode(256, 240, result.frames[i])
            File(outDir, "proof-frame-%02d.png".format(i)).writeBytes(png)
            println("  wrote proof-frame-%02d.png".format(i))
        }
    }
}
