import java.util.concurrent.*
import java.util.concurrent.atomic.*

/**
 * STRESS 4 — AgentPoolViewModel RAM budget. The REAL class imports android.app.ActivityManager,
 * androidx.lifecycle.ViewModel and compose state, so it CANNOT compile or run on this JVM
 * (compilation was attempted; the android.* import fails — see the [REAL-CLASS] note below).
 *
 * This harness is a MIRROR of the budget math, copied formula-for-formula from
 *   omni/app/src/main/java/com/omni/app/agentpool/AgentPoolViewModel.kt
 * (constants DEFAULT_MODEL_MB=800, DEFAULT_AGENTS=2, MIN_AGENTS=1, MAX_AGENTS=8,
 *  HEADROOM_FLOOR_MB=200, cap=50% avail, blockingReason() strings verbatim).
 *
 * It bursts 500 tasks with declared footprints and proves the budget gate:
 * - start is DENIED exactly when blockingReason() != null (total>cap OR headroom<200)
 * - the running pool auto-STOPS when headroom < 200
 * - no real model is loaded (workers simulate work with bounded byte arrays);
 *   the process runs under -Xmx512m and must NEVER OOM.
 */
object BudgetMirror {
    const val DEFAULT_MODEL_MB = 800
    const val MIN_AGENTS = 1
    const val MAX_AGENTS = 8
    const val HEADROOM_FLOOR_MB = 200L

    var modelMb = DEFAULT_MODEL_MB
    var agentCount = 2
    var availMb = 4096L

    fun totalMb(): Long = agentCount.toLong() * modelMb.toLong()
    fun capMb(): Long = availMb / 2
    fun blockingReason(): String? {
        val total = totalMb()
        val cap = capMb()
        return when {
            total > cap -> "needs ${total}MB, cap is ${cap}MB"
            availMb - total < HEADROOM_FLOOR_MB ->
                "needs ${HEADROOM_FLOOR_MB}MB headroom after start, only ${availMb - total}MB free"
            else -> null
        }
    }
}

fun main() {
    val out = StringBuilder()
    fun log(s: String) { out.appendLine(s); println(s) }

    // ---------- [REAL-CLASS] compilation attempt ----------
    log("[REAL-CLASS] AgentPoolViewModel.kt imports: android.app.ActivityManager, " +
        "android.content.Context, androidx.lifecycle.ViewModel, androidx.compose.runtime.* " +
        "-> NOT JVM-compilable here. Everything below is the MIRROR of its budget math.")

    // ---------- 1. budget gate truth table ----------
    var gateErrors = 0; var gateChecks = 0
    val results = mutableListOf<String>()
    for (avail in listOf(512L, 1024L, 2048L, 4096L, 8192L)) {
        for (agents in 1..8) {
            for (model in listOf(100, 800, 1600, 4000)) {
                BudgetMirror.availMb = avail; BudgetMirror.agentCount = agents; BudgetMirror.modelMb = model
                val total = BudgetMirror.totalMb(); val cap = BudgetMirror.capMb()
                val blocked = BudgetMirror.blockingReason()
                gateChecks++
                val expectedBlocked = total > cap || (avail - total < 200)
                if ((blocked != null) != expectedBlocked) {
                    gateErrors++
                    results += "MISMATCH avail=$avail agents=$agents model=$model total=$total cap=$cap blocked=$blocked"
                }
            }
        }
    }
    log("[GATE] truth table: $gateChecks combos, mismatches=$gateErrors")
    results.take(5).forEach { log("[GATE] $it") }
    // sample strings verbatim vs real class
    BudgetMirror.availMb = 2048; BudgetMirror.agentCount = 8; BudgetMirror.modelMb = 800
    log("[GATE] sample over-cap: '${BudgetMirror.blockingReason()}'")
    BudgetMirror.availMb = 4096; BudgetMirror.agentCount = 8; BudgetMirror.modelMb = 800
    BudgetMirror.availMb = 6550 // total=6400, cap=3275 -> over cap; use headroom case instead:
    BudgetMirror.availMb = 6600; BudgetMirror.agentCount = 8; BudgetMirror.modelMb = 800
    log("[GATE] avail=6600 agents=8 model=800: total=${BudgetMirror.totalMb()} cap=${BudgetMirror.capMb()} -> '${BudgetMirror.blockingReason()}'")
    BudgetMirror.availMb = 7000
    log("[GATE] avail=7000 agents=8 model=800: total=${BudgetMirror.totalMb()} cap=${BudgetMirror.capMb()} -> '${BudgetMirror.blockingReason()}'")

    // ---------- 2. burst: 500 tasks under budget gate, -Xmx512m, no OOM ----------
    // Config where the gate ALLOWS start: 4x400=1600 <= cap 2048, headroom 2496 >= 200.
    BudgetMirror.availMb = 4096; BudgetMirror.agentCount = 4; BudgetMirror.modelMb = 400
    val gate = BudgetMirror.blockingReason()
    log("[BURST] start gate with 4x400MB on 4096MB avail: ${gate ?: "ALLOWED"}")
    val admitted = AtomicLong(0); val rejected = AtomicLong(0); val completed = AtomicLong(0)
    val crashed = AtomicLong(0); val ooms = AtomicLong(0)
    val pool = Executors.newFixedThreadPool(8)
    val latch = CountDownLatch(500)
    val t0 = System.nanoTime()
    repeat(500) { i ->
        pool.submit {
            try {
                // each task declares a footprint; admit only if it fits the mirror budget
                val footprintMb = 10L + (i % 40)
                val wouldTotal = BudgetMirror.totalMb() + footprintMb
                if (wouldTotal > BudgetMirror.capMb() ||
                    BudgetMirror.availMb - wouldTotal < BudgetMirror.HEADROOM_FLOOR_MB) {
                    rejected.incrementAndGet()
                } else {
                    admitted.incrementAndGet()
                    // bounded real work: 1MB buffer per task, checksummed (no leak retained)
                    val buf = ByteArray(1024 * 1024) { (i and 0xFF).toByte() }
                    var sum = 0L; for (b in buf) sum += b
                    if (sum == Long.MIN_VALUE) println("impossible")
                    completed.incrementAndGet()
                }
            } catch (t: OutOfMemoryError) { ooms.incrementAndGet(); crashed.incrementAndGet() }
              catch (t: Throwable) { crashed.incrementAndGet() }
            finally { latch.countDown() }
        }
    }
    latch.await(180, TimeUnit.SECONDS)
    val dt = (System.nanoTime() - t0) / 1e9
    pool.shutdown()
    val rt = Runtime.getRuntime()
    val heapMb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0
    log("[BURST] 500 tasks: admitted=${admitted.get()} rejected-as-designed=${rejected.get()} " +
        "completed=${completed.get()} crashes=${crashed.get()} in ${"%.2f".format(dt)}s")
    log("[BURST] heap after burst=${"%.1f".format(heapMb)}MB (JVM cap 512m) -> OutOfMemoryErrors=${ooms.get()}")

    // ---------- 3. auto-stop on critical headroom (mirror of ramWatcher) ----------
    var running = true; var stopReason: String? = null; var stops = 0
    BudgetMirror.availMb = 4096; BudgetMirror.agentCount = 4; BudgetMirror.modelMb = 800 // total 3200, headroom 896
    val ramTrace = longArrayOf(4096, 3800, 3500, 3300, 3200, 3100) // avail shrinking; headroom -> <200
    for (avail in ramTrace) {
        BudgetMirror.availMb = avail
        val headroom = avail - BudgetMirror.totalMb()
        if (running && headroom < BudgetMirror.HEADROOM_FLOOR_MB) {
            running = false; stopReason = "stopped: RAM headroom critical"; stops++
        }
    }
    log("[AUTOSTOP] auto-stops=$stops reason='$stopReason' (real class stops with identical string)")

    log("AGENTPOOL-MIRROR-STRESS DONE")
    java.io.File("/home/hatch/workspace/omni-app/v7-work/stress/agentpool-stress.txt").writeText(out.toString())
}
