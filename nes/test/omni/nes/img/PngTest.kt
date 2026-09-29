package omni.nes.img

import org.junit.Assert.*
import org.junit.Test
import java.util.zip.CRC32

/** Structural tests for the minimal PNG encoder (signature, IHDR, CRCs). */
class PngTest {
    private fun chunkAt(png: ByteArray, offset: Int): Triple<String, Int, Int> {
        val len = ((png[offset].toInt() and 0xFF) shl 24) or
            ((png[offset + 1].toInt() and 0xFF) shl 16) or
            ((png[offset + 2].toInt() and 0xFF) shl 8) or
            (png[offset + 3].toInt() and 0xFF)
        val type = String(png, offset + 4, 4, Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(png, offset + 4, 4 + len)
        val stored = ((png[offset + 8 + len].toInt() and 0xFF) shl 24) or
            ((png[offset + 9 + len].toInt() and 0xFF) shl 16) or
            ((png[offset + 10 + len].toInt() and 0xFF) shl 8) or
            (png[offset + 11 + len].toInt() and 0xFF)
        assertEquals("CRC mismatch in $type chunk", crc.value.toInt(), stored)
        return Triple(type, len, offset + 12 + len)
    }

    @Test fun encodesValidPngStructure() {
        val pixels = IntArray(2 * 2) { intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF)[it] }
        val png = Png.encode(2, 2, pixels)
        val sig = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        assertArrayEquals(sig, png.copyOfRange(0, 8))

        var off = 8
        val (t1, l1, n1) = chunkAt(png, off); off = n1
        assertEquals("IHDR", t1)
        assertEquals(13, l1)
        val w = ((png[16].toInt() and 0xFF) shl 24) or ((png[17].toInt() and 0xFF) shl 16) or
            ((png[18].toInt() and 0xFF) shl 8) or (png[19].toInt() and 0xFF)
        assertEquals(2, w) // width 2 in IHDR
        val (t2, _, n2) = chunkAt(png, off); off = n2
        assertEquals("IDAT", t2)
        val (t3, l3, n3) = chunkAt(png, off)
        assertEquals("IEND", t3)
        assertEquals(0, l3)
        assertEquals(png.size, n3)
    }

    @Test fun encodingIsDeterministic() {
        val pixels = IntArray(256 * 240) { it }
        assertArrayEquals(Png.encode(256, 240, pixels), Png.encode(256, 240, pixels))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedPixelCount() {
        Png.encode(4, 4, IntArray(3))
    }
}
