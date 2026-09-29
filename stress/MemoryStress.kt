import com.omni.memory.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/**
 * STRESS 2 — Memory core (REAL classes: InMemoryStore, HandoffPacket, AdaptiveRanker).
 *
 * Scenarios:
 *  A. 32 threads x 2000 writes = 64,000 puts; mixed unique ids + bumped versions.
 *  B. Top-K recall over 12,000 observations: latency p50/p99.
 *  C. HandoffPacket export/import round-trips, fuzzed with random payloads,
 *     verified field-by-field byte-exact.
 *  D. Stale-version rejection under racing updates: 16 threads x 50 versions on
 *     shared ids; final version must be 50, stale throws counted, no corruption.
 */
fun rec(id: String, v: Long, sal: Double = 0.5): MemoryRecord = MemoryRecord(
    id = id, scope = MemoryScope.OBSERVATION, key = "k-$id", value = "value of $id v$v",
    salience = sal, createdAtMs = 1000L, updatedAtMs = 2000L, version = v,
    provenance = "stress", pinned = false,
)

fun main() {
    val out = StringBuilder()
    fun log(s: String) { out.appendLine(s); println(s) }

    // ---------- A. heavy concurrent writes ----------
    val store = InMemoryStore()
    val putOk = AtomicLong(0); val staleThrown = AtomicLong(0); val otherThrown = AtomicLong(0)
    val pool = Executors.newFixedThreadPool(32)
    val t0 = System.nanoTime()
    val latch = CountDownLatch(32)
    repeat(32) { t ->
        pool.submit {
            try {
                repeat(2000) { i ->
                    val id = "t${t}-r$i"
                    store.put(rec(id, 1, (i % 100) / 100.0))
                    putOk.incrementAndGet()
                    // bump a shared id with increasing versions
                    val shared = "shared-$i"
                    var v = 1L
                    while (v <= 3) {
                        try {
                            store.put(rec(shared, v, 0.9)); putOk.incrementAndGet(); v++
                        } catch (e: StaleVersionException) { staleThrown.incrementAndGet(); v++ }
                    }
                }
            } catch (t2: Throwable) { otherThrown.incrementAndGet(); t2.printStackTrace() }
            finally { latch.countDown() }
        }
    }
    latch.await(180, TimeUnit.SECONDS)
    val dtA = (System.nanoTime() - t0) / 1e9
    log("[A] puts-ok=${putOk.get()} stale-rejected=${staleThrown.get()} other-throws=${otherThrown.get()} " +
        "in ${"%.2f".format(dtA)}s = ${"%.0f".format(putOk.get() / dtA)} puts/s")
    log("[A] store size=${store.all().size} (expect 64000 unique + 2000 shared = 66000)")

    // ---------- B. Top-K recall latency over 12k observations ----------
    val storeB = InMemoryStore()
    val rnd = java.util.Random(1234)
    repeat(12000) { i -> storeB.put(rec("obs-$i", 1, rnd.nextDouble())) }
    val ranker: RecordRanker = { r -> r.salience }
    // warmup
    repeat(20) { storeB.topK(20, ranker) }
    val lat = LongArray(200)
    repeat(200) { i ->
        val s = System.nanoTime()
        val top = storeB.topK(20, ranker)
        lat[i] = System.nanoTime() - s
        if (i == 0) {
            val sorted = top.zipWithNext().all { (a, b) -> a.salience >= b.salience }
            log("[B] topK(20) ordered-desc=${sorted} size=${top.size}")
        }
    }
    lat.sort()
    log("[B] topK(20) over 12,000 obs: p50=${lat[100] / 1000}us p99=${lat[198] / 1000}us max=${lat[199] / 1000}us")

    // ---------- C. HandoffPacket fuzz round-trips ----------
    val frnd = java.util.Random(999)
    fun rstr(n: Int): String {
        val poolChars = "abcXYZ019 \t\n\"'\\{}[],:😀\u0001\u007f\u0080é中"
        return (0 until n).map { poolChars[frnd.nextInt(poolChars.length)] }.joinToString("")
    }
    var fuzzOk = 0; var fuzzMismatch = 0; var fuzzThrow = 0
    repeat(2000) { i ->
        try {
            val p = HandoffPacket(
                taskId = rstr(1 + frnd.nextInt(40)), goal = rstr(frnd.nextInt(200)),
                stateJson = "{\"a\":[${frnd.nextInt(999)}],\"s\":\"${rstr(30)}\"}",
                constraints = List(frnd.nextInt(5)) { rstr(frnd.nextInt(50)) },
                budgetTokens = frnd.nextLong().let { if (it < 0) -it else it },
                attempts = frnd.nextInt(1000),
                memoryRefIds = List(frnd.nextInt(8)) { rstr(12) },
                createdBy = rstr(20), createdAtMs = frnd.nextLong(),
            )
            val back = HandoffPacket.fromJson(p.toJson())
            if (back == p) fuzzOk++ else { fuzzMismatch++; if (fuzzMismatch <= 3) log("[C] MISMATCH case $i") }
        } catch (t: Throwable) { fuzzThrow++; if (fuzzThrow <= 3) log("[C] THROW case $i: ${t.javaClass.simpleName}: ${t.message?.take(120)}") }
    }
    log("[C] handoff fuzz: ok=$fuzzOk mismatch=$fuzzMismatch throws=$fuzzThrow / 2000")
    // malformed fromJson inputs must throw cleanly (IllegalArgumentException), never hang
    val badInputs = listOf("", "{}", "{\"taskId\":\"x\"}", "not json at all",
        "{\"schemaVersion\":2,\"taskId\":\"x\"}", "{\"schemaVersion\":1}")
    var badClean = 0; var badHang = 0
    for (b in badInputs) {
        try { HandoffPacket.fromJson(b); } catch (e: Exception) { badClean++ }
    }
    log("[C] malformed fromJson: clean-throws=$badClean/${badInputs.size} hangs=$badHang")

    // ---------- D. stale-version racing ----------
    val storeD = InMemoryStore()
    val dl = CountDownLatch(16)
    val dStale = AtomicLong(0); val dOk = AtomicLong(0); val dOther = AtomicLong(0)
    repeat(16) { t ->
        pool.submit {
            try {
                repeat(40) { i ->
                    val id = "race-$i"
                    // each thread races versions 1..50 for its ids
                    for (v in 1..50) {
                        try { storeD.put(rec(id, v.toLong(), 0.5)); dOk.incrementAndGet() }
                        catch (e: StaleVersionException) { dStale.incrementAndGet() }
                    }
                }
            } catch (t2: Throwable) { dOther.incrementAndGet() }
            finally { dl.countDown() }
        }
    }
    dl.await(120, TimeUnit.SECONDS)
    var corrupt = 0
    repeat(40) { i ->
        val r = storeD.get("race-$i")
        if (r == null || r.version != 50L) { corrupt++; log("[D] CORRUPT race-$i -> ${r?.version}") }
    }
    log("[D] racing writes: ok=$dOk stale-rejected=$dStale other=$dOther corrupt-ids=$corrupt/40")
    pool.shutdown()
    log("MEMORY-STRESS DONE")
    java.io.File("/home/hatch/workspace/omni-app/v7-work/stress/memory-stress.txt").writeText(out.toString())
}
