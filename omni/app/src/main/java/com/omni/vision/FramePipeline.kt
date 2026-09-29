package com.omni.vision

/**
 * Pure-Kotlin agent-vision pipeline. No Android dependencies, so the logic is
 * unit-tested on the build machine: 1:1 pixel truth, no downscaling anywhere
 * in the decision path. The Android service feeds it native-resolution frames.
 */
data class Frame(
    val width: Int,
    val height: Int,
    /** ARGB pixels, row-major, exactly width*height entries. */
    val pixels: IntArray,
    val timestampNs: Long,
) {
    init { require(pixels.size == width * height) { "pixel buffer size mismatch" } }
    fun pixel(x: Int, y: Int): Int = pixels[y * width + x]
}

data class Roi(val x: Int, val y: Int, val w: Int, val h: Int)

/** Fixed-capacity ring buffer; agents always see the latest frame. */
class FrameRing(val capacity: Int) {
    init { require(capacity > 0) }
    private val buf = ArrayDeque<Frame>(capacity)
    @Synchronized fun push(f: Frame) {
        if (buf.size == capacity) buf.removeFirst()
        buf.addLast(f)
    }
    @Synchronized fun latest(): Frame? = buf.lastOrNull()
    @Synchronized fun size(): Int = buf.size
    @Synchronized fun clear() = buf.clear()
}

/** 1:1 frame differencing — fraction of pixels changed beyond a per-channel threshold. */
object FrameDiff {
    fun changedFraction(a: Frame, b: Frame, threshold: Int = 16): Double {
        require(a.width == b.width && a.height == b.height) { "frame size mismatch" }
        var changed = 0
        for (i in a.pixels.indices) {
            val p1 = a.pixels[i]; val p2 = b.pixels[i]
            val dr = ((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF)
            val dg = ((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF)
            val db = (p1 and 0xFF) - (p2 and 0xFF)
            if (dr > threshold || dr < -threshold ||
                dg > threshold || dg < -threshold ||
                db > threshold || db < -threshold) changed++
        }
        return changed.toDouble() / a.pixels.size
    }
}

/** Rolling FPS meter over capture timestamps. */
class FpsMeter(private val window: Int = 60) {
    private val stamps = ArrayDeque<Long>()
    @Synchronized fun tick(tsNs: Long) {
        stamps.addLast(tsNs)
        while (stamps.size > window) stamps.removeFirst()
    }
    @Synchronized fun fps(): Double {
        if (stamps.size < 2) return 0.0
        val dtNs = stamps.last() - stamps.first()
        return if (dtNs <= 0) 0.0 else (stamps.size - 1) * 1e9 / dtNs
    }
}

/** Crop a 1:1 region of interest (clamped to frame bounds). */
fun Frame.crop(roi: Roi): Frame {
    val x0 = roi.x.coerceIn(0, width - 1)
    val y0 = roi.y.coerceIn(0, height - 1)
    val x1 = (roi.x + roi.w).coerceIn(x0 + 1, width)
    val y1 = (roi.y + roi.h).coerceIn(y0 + 1, height)
    val w = x1 - x0; val h = y1 - y0
    val out = IntArray(w * h)
    for (row in 0 until h)
        pixels.copyInto(out, row * w, (y0 + row) * width + x0, (y0 + row) * width + x0 + w)
    return Frame(w, h, out, timestampNs)
}

/**
 * Shared hub: the capture service writes, agents read. Latest-frame semantics —
 * a slow VLM never blocks the capture loop, it just reads a slightly older 1:1 frame.
 */
object VisionHub {
    val ring = FrameRing(8)
    val fps = FpsMeter()
    @Volatile var capturing: Boolean = false
}
