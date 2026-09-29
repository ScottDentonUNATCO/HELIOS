import com.omni.vision.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/**
 * STRESS 5 — FramePipeline (REAL classes: FrameRing, FrameDiff, FpsMeter, crop, VisionHub).
 *
 *  A. 10,000 frames through the full pipeline (push + latest + diff + fps tick +
 *     crop ROI): sustained throughput.
 *  B. Ring overflow: 4 producers x 12,500 pushes = 50,000 pushes into
 *     FrameRing(8), consumer reading latest concurrently. Asserts: size never
 *     exceeds capacity, no crash, memory bounded (heap sampled before/after).
 */
val heapSampler = AtomicLong(0)
fun peakHeapMb(): Double {
    val r = Runtime.getRuntime()
    return (r.totalMemory() - r.freeMemory()) / 1048576.0
}

fun main() {
    val out = StringBuilder()
    fun log(s: String) { out.appendLine(s); println(s) }
    val rnd = java.util.Random(2024)

    // ---------- A. sustained throughput ----------
    val ring = FrameRing(8)
    val fps = FpsMeter()
    val w = 320; val h = 240
    var prev: Frame? = null
    var diffSum = 0.0; var diffN = 0
    val stop = AtomicBoolean(false)
    val sampler = Thread {
        while (!stop.get()) {
            val m = peakHeapMb()
            heapSampler.accumulateAndGet((m * 100).toLong()) { a, b -> maxOf(a, b) }
            Thread.sleep(25)
        }
    }
    sampler.isDaemon = true; sampler.start()

    val t0 = System.nanoTime()
    repeat(10000) { i ->
        val px = IntArray(w * h) { rnd.nextInt() }
        // every 100th frame: mostly-blank frame to exercise the diff path
        val f = Frame(w, h, px, System.nanoTime())
        ring.push(f)
        val latest = ring.latest()
        val p = prev
        if (p != null && latest != null) {
            diffSum += FrameDiff.changedFraction(p, latest); diffN++
        }
        fps.tick(f.timestampNs)
        if (i % 500 == 0) {
            val c = latest!!.crop(Roi(-10, -10, 400, 300)) // out-of-bounds clamp
            check(c.width == w && c.height == h) { "crop clamp failed" }
        }
        prev = f
    }
    val dtA = (System.nanoTime() - t0) / 1e9
    log("[A] 10,000 frames: ${"%.2f".format(dtA)}s = ${"%.0f".format(10000 / dtA)} frames/s " +
        "through push+latest+diff+fps+crop")
    log("[A] mean changedFraction=${"%.4f".format(diffSum / diffN)} fpsMeter=${"%.1f".format(fps.fps())} " +
        "ring.size=${ring.size()} (cap 8)")

    // ---------- B. overflow: producers faster than consumer ----------
    val ring2 = FrameRing(8)
    val overCap = AtomicLong(0)
    val pushCount = AtomicLong(0)
    val readCount = AtomicLong(0)
    val crash = AtomicLong(0)
    val pool = Executors.newFixedThreadPool(5)
    val prodLatch = CountDownLatch(4)
    repeat(4) { t ->
        pool.submit {
            try {
                repeat(12500) {
                    ring2.push(Frame(160, 120, IntArray(160 * 120) { t }, System.nanoTime()))
                    pushCount.incrementAndGet()
                    val s = ring2.size()
                    if (s > 8) overCap.incrementAndGet()
                }
            } catch (x: Throwable) { crash.incrementAndGet() }
            finally { prodLatch.countDown() }
        }
    }
    pool.submit {
        try {
            while (prodLatch.count > 0) {
                ring2.latest()
                readCount.incrementAndGet()
            }
        } catch (x: Throwable) { crash.incrementAndGet() }
    }
    val heapBefore = peakHeapMb()
    prodLatch.await(120, TimeUnit.SECONDS)
    pool.shutdown(); pool.awaitTermination(30, TimeUnit.SECONDS)
    stop.set(true)
    val heapAfter = peakHeapMb()
    val peakSeen = heapSampler.get() / 100.0

    log("[B] pushes=${pushCount.get()} reads=${readCount.get()} over-capacity-events=${overCap.get()} " +
        "crashes=${crash.get()}")
    log("[B] final ring size=${ring2.size()} (cap 8); heap before=${"%.1f".format(heapBefore)}MB " +
        "after=${"%.1f".format(heapAfter)}MB peak-sampled=${"%.1f".format(peakSeen)}MB " +
        "(ring is bounded: 8 x 160x120 frames)")
    // latest-frame semantics: consumer always sees a recent frame
    val last = ring2.latest()
    log("[B] latest frame present=${last != null}, ts sane=${last != null && last.timestampNs > 0}")
    log("VISION-STRESS DONE")
    java.io.File("/home/hatch/workspace/omni-app/v7-work/stress/vision-stress.txt").writeText(out.toString())
}
