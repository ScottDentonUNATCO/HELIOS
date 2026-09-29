package com.omni.vision

import org.junit.Assert.*
import org.junit.Test

class FramePipelineTest {
    private fun frame(w: Int, h: Int, fill: Int, ts: Long = 0L) =
        Frame(w, h, IntArray(w * h) { fill }, ts)

    @Test fun `frame rejects bad buffer size`() {
        try { Frame(2, 2, IntArray(3), 0L); fail("expected") }
        catch (e: IllegalArgumentException) {}
    }

    @Test fun `ring keeps latest and evicts oldest`() {
        val r = FrameRing(3)
        repeat(5) { r.push(frame(2, 2, it, it.toLong())) }
        assertEquals(3, r.size())
        assertEquals(4, r.latest()!!.pixels[0])
    }

    @Test fun `ring clear`() {
        val r = FrameRing(2); r.push(frame(1, 1, 0)); r.clear()
        assertEquals(0, r.size()); assertNull(r.latest())
    }

    @Test fun `identical frames diff zero`() {
        val a = frame(4, 4, 0xFF112233.toInt())
        assertEquals(0.0, FrameDiff.changedFraction(a, a.copy()), 1e-9)
    }

    @Test fun `fully inverted frames diff one`() {
        val a = frame(4, 4, 0xFF000000.toInt())
        val b = frame(4, 4, 0xFFFFFFFF.toInt())
        assertEquals(1.0, FrameDiff.changedFraction(a, b), 1e-9)
    }

    @Test fun `single pixel change is exact fraction`() {
        val a = frame(10, 10, 0xFF000000.toInt())
        val px = a.pixels.copyOf(); px[42] = 0xFFFFFFFF.toInt()
        val b = Frame(10, 10, px, 0L)
        assertEquals(0.01, FrameDiff.changedFraction(a, b), 1e-9)
    }

    @Test fun `sub-threshold noise ignored`() {
        val a = frame(4, 4, 0xFF808080.toInt())
        val px = a.pixels.copyOf()
        for (i in px.indices) px[i] = 0xFF858585.toInt() // +5 per channel
        val b = Frame(4, 4, px, 0L)
        assertEquals(0.0, FrameDiff.changedFraction(a, b, threshold = 16), 1e-9)
        assertEquals(1.0, FrameDiff.changedFraction(a, b, threshold = 2), 1e-9)
    }

    @Test fun `size mismatch throws`() {
        try { FrameDiff.changedFraction(frame(2, 2, 0), frame(3, 3, 0)); fail("expected") }
        catch (e: IllegalArgumentException) {}
    }

    @Test fun `fps meter reads 60fps`() {
        val m = FpsMeter()
        var t = 0L
        repeat(61) { m.tick(t); t += 16_666_667L }
        assertEquals(60.0, m.fps(), 0.5)
    }

    @Test fun `fps meter empty is zero`() {
        assertEquals(0.0, FpsMeter().fps(), 1e-9)
    }

    @Test fun `crop extracts exact 1to1 region`() {
        val w = 6; val h = 6
        val px = IntArray(w * h) { it }
        val f = Frame(w, h, px, 0L)
        val c = f.crop(Roi(2, 1, 3, 2))
        assertEquals(3, c.width); assertEquals(2, c.height)
        assertEquals(f.pixel(2, 1), c.pixel(0, 0))
        assertEquals(f.pixel(4, 2), c.pixel(2, 1))
    }

    @Test fun `crop clamps to bounds`() {
        val f = frame(4, 4, 7)
        val c = f.crop(Roi(-10, -10, 100, 100))
        assertEquals(4, c.width); assertEquals(4, c.height)
    }

    @Test fun `vision hub starts idle`() {
        assertFalse(VisionHub.capturing)
    }
}
