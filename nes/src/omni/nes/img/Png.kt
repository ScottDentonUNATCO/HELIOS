package omni.nes.img

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Minimal PNG encoder: 8-bit RGB, no interlace, filter type 0 on every
 * scanline. Pure Kotlin + java.util.zip (Deflater/CRC32) — no java.awt,
 * no image libraries. Written from the PNG spec's chunk layout, which is
 * long-published.
 *
 * [pixels] is width*height entries of 0xRRGGBB, row-major, top row first.
 */
object Png {
    private val SIGNATURE = byteArrayOf(
        137.toByte(), 80, 78, 71, 13, 10, 26, 10,
    )

    fun encode(width: Int, height: Int, pixels: IntArray): ByteArray {
        require(pixels.size == width * height) {
            "pixel count ${pixels.size} != ${width}x$height"
        }
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)

        // IHDR: width, height, bit depth 8, color type 2 (RGB).
        val ihdr = ByteArrayOutputStream()
        writeInt(ihdr, width)
        writeInt(ihdr, height)
        ihdr.write(byteArrayOf(8, 2, 0, 0, 0))
        writeChunk(out, "IHDR", ihdr.toByteArray())

        // IDAT: zlib stream of filter-byte-0 + raw RGB rows.
        val raw = ByteArrayOutputStream(height * (1 + width * 3))
        var p = 0
        for (y in 0 until height) {
            raw.write(0) // filter type 0: none
            for (x in 0 until width) {
                val c = pixels[p++]
                raw.write((c ushr 16) and 0xFF)
                raw.write((c ushr 8) and 0xFF)
                raw.write(c and 0xFF)
            }
        }
        val deflater = Deflater(6)
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val comp = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            comp.write(buf, 0, n)
        }
        deflater.end()
        writeChunk(out, "IDAT", comp.toByteArray())

        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        writeInt(out, data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeInt(out, crc.value.toInt())
    }
}
