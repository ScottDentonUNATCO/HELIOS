import com.omni.gateway.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.math.abs

/**
 * STRESS 1 — SocketRegistry (REAL class) + harness-side Router (mirrors a hub router:
 * capability pick -> round-robin across instances -> 429 failover).
 *
 * Scenarios:
 *  A. 16 threads x 5000 routes = 80,000 routes through withCapability(CHAT)
 *     with fake providers (some throw Fake429 -> failover to next instance).
 *  B. Concurrent registration churn while routing (register/unregister).
 *  C. Mid-flight enabled/disabled toggling: OFF MUST MEAN OFF. A toggler thread
 *     disables a socket at recorded nano-times; any route that SELECTED the
 *     socket AFTER its disable timestamp (i.e. the registry handed out a
 *     disabled socket) is a violation. Uses-after-pick are excluded (TOCTOU is
 *     unavoidable without a registry lock, and that is documented).
 */
class Fake429(msg: String) : Exception(msg)
class FakeFail(msg: String) : Exception(msg)

class FakeProvider(val id: String, val rand: java.util.Random, val p429: Double, val pFail: Double) {
    val calls = AtomicLong(0)
    fun call(): String {
        calls.incrementAndGet()
        val r = rand.nextDouble()
        if (r < p429) throw Fake429("429 rate_limited on $id")
        if (r < p429 + pFail) throw FakeFail("500 on $id")
        return "ok:$id"
    }
}

/** Harness-side router (NOT part of the shipped registry). */
class Router(private val reg: SocketRegistry, private val providers: Map<String, FakeProvider>) {
    private val rr = AtomicInteger(0)
    val routes = AtomicLong(0)
    val failovers = AtomicLong(0)
    val disabledAvoided = AtomicLong(0)
    val violations = AtomicLong(0)

    /**
     * Returns socket id that served, or null if none available.
     * [disabledAtNs]: id -> nano time of last disable (for violation checks).
     */
    fun route(capability: Int, disabledAtNs: ConcurrentHashMap<String, Long>): String? {
        val t0 = System.nanoTime()
        val pool = reg.withCapability(capability) // live snapshot
        if (pool.isEmpty()) return null
        var idx = abs(rr.getAndIncrement()) % pool.size
        repeat(pool.size) {
            val s = pool[idx]
            // Re-confirm enabled at use time: OFF MUST MEAN OFF.
            val cur = reg.get(s.id)
            if (cur == null || !cur.enabled) {
                disabledAvoided.incrementAndGet()
                idx = (idx + 1) % pool.size
                return@repeat
            }
            routes.incrementAndGet()
            val pickNs = System.nanoTime()
            try {
                providers.getValue(s.id).call()
                val d = disabledAtNs[s.id]
                if (d != null && d < pickNs) {
                    // The socket was disabled BEFORE we picked it from the
                    // registry's own snapshot -> genuine violation.
                    // (Re-check state to rule out a re-enable race.)
                    val st = reg.get(s.id)
                    if (st != null && !st.enabled) violations.incrementAndGet()
                }
                return s.id
            } catch (e: Fake429) {
                failovers.incrementAndGet()
                idx = (idx + 1) % pool.size
            } catch (e: FakeFail) {
                return null
            }
        }
        return null
    }
}

fun mkSock(id: String, caps: Int = Caps.CHAT): SocketDef = SocketDef(
    id = id, displayName = "sock $id", kind = SocketKind.API_KEY,
    capabilities = caps, baseUrl = "https://fake.local/v1", apiKeyRef = "vault:$id",
)

fun main() {
    val out = StringBuilder()
    fun log(s: String) { out.appendLine(s); println(s) }

    // ---------- Scenario A: heavy concurrent routing + 429 failover ----------
    val reg = SocketRegistry()
    val providers = ConcurrentHashMap<String, FakeProvider>()
    val ids = listOf("chat-a1", "chat-a2", "chat-a3", "chat-b1", "chat-b2",
        "code-1", "vision-1", "tts-1")
    ids.forEachIndexed { i, id ->
        val caps = when {
            id.startsWith("chat") -> Caps.CHAT
            id.startsWith("code") -> Caps.CODE
            id.startsWith("vision") -> Caps.VISION
            else -> Caps.TTS
        }
        reg.register(mkSock(id, caps))
        providers[id] = FakeProvider(id, java.util.Random(1000L + i),
            p429 = if (id == "chat-a1") 0.30 else 0.05, pFail = 0.01)
    }
    val router = Router(reg, providers)
    val disabledAtNs = ConcurrentHashMap<String, Long>()
    val crashCount = AtomicLong(0)

    val t0 = System.nanoTime()
    val pool = Executors.newFixedThreadPool(16)
    val latch = CountDownLatch(16)
    repeat(16) {
        pool.submit {
            try {
                repeat(5000) {
                    router.route(Caps.CHAT, disabledAtNs)
                }
            } catch (t: Throwable) {
                crashCount.incrementAndGet()
                t.printStackTrace()
            } finally { latch.countDown() }
        }
    }
    latch.await(120, TimeUnit.SECONDS)
    val dtA = (System.nanoTime() - t0) / 1e9

    log("[A] routes=${router.routes.get()} in ${"%.2f".format(dtA)}s " +
        "= ${"%.0f".format(router.routes.get() / dtA)} routes/s")
    log("[A] 429-failovers=${router.failovers.get()} disabled-avoided=${router.disabledAvoided.get()} " +
        "thread-crashes=${crashCount.get()}")
    val totalCalls = providers.values.sumOf { it.calls.get() }
    log("[A] provider calls=$totalCalls (should ~ routes)")

    // ---------- Scenario B: registration churn under routing ----------
    val crashB = AtomicLong(0)
    val churnLatch = CountDownLatch(1)
    val routeLatch = CountDownLatch(8)
    repeat(8) {
        pool.submit {
            try {
                repeat(2000) { router.route(Caps.CHAT, disabledAtNs) }
            } catch (t: Throwable) { crashB.incrementAndGet() } finally { routeLatch.countDown() }
        }
    }
    pool.submit {
        try {
            repeat(400) { i ->
                val id = "churn-$i"
                reg.register(mkSock(id))
                providers[id] = FakeProvider(id, java.util.Random(i.toLong()), 0.0, 0.0)
                if (i % 2 == 0) reg.unregister(id)
            }
        } catch (t: Throwable) { crashB.incrementAndGet(); /* e.g. ConcurrentModificationException */ }
        finally { churnLatch.countDown() }
    }
    routeLatch.await(60, TimeUnit.SECONDS); churnLatch.await(60, TimeUnit.SECONDS)
    log("[B] registration churn under load: thread-crashes=${crashB.get()} " +
        "(any >0 = registry NOT thread-safe; LinkedHashMap is unsynchronized)")
    log("[B] registry size after churn=${reg.all().size}")

    // ---------- Scenario C: OFF MUST MEAN OFF under concurrency ----------
    val reg2 = SocketRegistry()
    val prov2 = ConcurrentHashMap<String, FakeProvider>()
    val ids2 = listOf("s1", "s2", "s3", "s4")
    ids2.forEach { id -> reg2.register(mkSock(id)); prov2[id] = FakeProvider(id, java.util.Random(7), 0.0, 0.0) }
    val router2 = Router(reg2, prov2)
    val dis2 = ConcurrentHashMap<String, Long>()
    val crashC = AtomicLong(0)
    val routesC = AtomicLong(0)
    val stop = AtomicBoolean(false)
    val rl = CountDownLatch(12)
    repeat(12) {
        pool.submit {
            try {
                while (!stop.get()) {
                    val r = router2.route(Caps.CHAT, dis2)
                    if (r != null) routesC.incrementAndGet()
                }
            } catch (t: Throwable) { crashC.incrementAndGet() } finally { rl.countDown() }
        }
    }
    // Toggler: randomly disable/re-enable; record disable nano-times.
    val toggler = Thread {
        val rnd = java.util.Random(42)
        repeat(3000) {
            val id = ids2[rnd.nextInt(ids2.size)]
            val en = rnd.nextBoolean()
            reg2.setEnabled(id, en)
            if (!en) dis2[id] = System.nanoTime() else dis2.remove(id)
            if (it % 500 == 0) Thread.sleep(1)
        }
        stop.set(true)
    }
    toggler.start(); toggler.join(60000); rl.await(60, TimeUnit.SECONDS)
    pool.shutdown()

    log("[C] routes=${routesC.get()} router-violations=${router2.violations.get()} " +
        "disabled-avoided=${router2.disabledAvoided.get()} crashes=${crashC.get()}")
    log("[C] final enabled states: " + ids2.joinToString { "$it=${reg2.get(it)?.enabled}" })

    log("SOCKET-STRESS DONE")
    java.io.File("/home/hatch/workspace/omni-app/v7-work/stress/socket-stress.txt").writeText(out.toString())
}
