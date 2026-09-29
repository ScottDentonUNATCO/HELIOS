package omni.nes.ppu

import omni.nes.tools.Headless
import omni.nes.validate.NesValidator
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Frame-golden tests: proof.nes renders deterministically headless (same
 * frame -> identical hash across runs), and its sprite genuinely moves
 * (hash changes across frames, sprite pixels shift right 1px per frame).
 */
class FrameGoldenTest {
    private val nesBytes: ByteArray by lazy {
        File("/home/hatch/workspace/omni-app/nes/roms/proof.nes").readBytes()
    }

    @Test fun proofRendersDeterministicallyAcrossRuns() {
        val a = Headless.run(nesBytes, frameCount = 30)
        val b = Headless.run(nesBytes, frameCount = 30)
        assertNull(a.stoppedEarly)
        assertNull(b.stoppedEarly)
        assertEquals(30, a.hashes.size)
        assertEquals(a.hashes, b.hashes)
    }

    @Test fun spriteMovesSoHashesChangeAcrossFrames() {
        val r = Headless.run(nesBytes, frameCount = 40)
        assertNull(r.stoppedEarly)
        // Not a static image: the moving sprite changes pixels every frame.
        val distinct = r.hashes.toSet().size
        assertTrue("expected many distinct frames, got $distinct", distinct > 30)
        // Consecutive frames differ once the sprite is on screen.
        assertNotEquals(r.hashes[10], r.hashes[11])
        assertNotEquals(r.hashes[20], r.hashes[21])
    }

    @Test fun spritePixelShiftsRightOnePixelPerFrame() {
        val r = Headless.run(nesBytes, frameCount = 12, keepFrames = true)
        assertNull(r.stoppedEarly)
        val spriteRgb = Ppu2C02.MASTER_PALETTE[0x16] // proof sprite color
        fun leftEdge(frame: IntArray): Int {
            // Scanline 132 crosses the sprite (Y=128 -> scanlines 129..136).
            for (x in 0 until 256) if (frame[132 * 256 + x] == spriteRgb) return x
            return -1
        }
        val e10 = leftEdge(r.frames[10])
        val e11 = leftEdge(r.frames[11])
        assertTrue("sprite visible in frame 10 (edge=$e10)", e10 > 0)
        assertTrue("sprite visible in frame 11 (edge=$e11)", e11 > 0)
        assertEquals("sprite must move exactly 1px right per frame", e10 + 1, e11)
        // And the sprite is a solid 8px-wide block of the palette color.
        for (dx in 0 until 8) {
            assertEquals(spriteRgb, r.frames[10][132 * 256 + e10 + dx])
        }
    }

    @Test fun validatorStaysGreenOnProofNes() {
        val report = NesValidator().validate(nesBytes)
        assertTrue(
            "validator regressed: ${report.findings}",
            report.passed,
        )
    }
}
