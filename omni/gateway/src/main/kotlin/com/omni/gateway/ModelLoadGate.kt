package com.omni.gateway

/**
 * Pure, JVM-testable RAM gate for on-device model loading.
 *
 * Policy (documented in the Offline tab UI):
 * - ONE model loaded at a time (enforced by OfflineModelManager, which calls
 *   this gate after unloading the previous model).
 * - A single model may claim at most [MAX_MODEL_FRACTION] of total RAM.
 * - Loading must leave at least [MIN_FREE_HEADROOM_BYTES] free afterwards —
 *   the OS, the app, and the runtime itself need breathing room.
 *
 * All sizes in bytes. The Android manager feeds this from
 * ActivityManager.MemoryInfo; the logic itself has no Android dependency so
 * the policy is unit-tested on the build machine.
 */
object ModelLoadGate {

    /** Max share of total device RAM one model file may claim. */
    const val MAX_MODEL_FRACTION = 0.5

    /** Free RAM that must remain AFTER the model is loaded. */
    const val MIN_FREE_HEADROOM_BYTES = 512L * 1024 * 1024

    data class Decision(val allowed: Boolean, val reason: String)

    fun decide(modelBytes: Long, totalMemBytes: Long, availMemBytes: Long): Decision {
        require(modelBytes > 0) { "modelBytes must be positive" }
        require(totalMemBytes > 0) { "totalMemBytes must be positive" }
        require(availMemBytes >= 0) { "availMemBytes must not be negative" }

        val maxClaim = (totalMemBytes * MAX_MODEL_FRACTION).toLong()
        if (modelBytes > maxClaim) {
            return Decision(
                allowed = false,
                reason = "model is ${gb(modelBytes)} — over the single-model limit of " +
                    "${gb(maxClaim)} (50% of ${gb(totalMemBytes)} total RAM)",
            )
        }
        val freeAfter = availMemBytes - modelBytes
        if (freeAfter < MIN_FREE_HEADROOM_BYTES) {
            return Decision(
                allowed = false,
                reason = "loading would leave only ${gb(freeAfter.coerceAtLeast(0))} free — " +
                    "need at least ${gb(MIN_FREE_HEADROOM_BYTES)} headroom after load",
            )
        }
        return Decision(
            allowed = true,
            reason = "ok: ${gb(modelBytes)} model, ${gb(freeAfter)} free afterwards " +
                "(${gb(availMemBytes)} free now, ${gb(totalMemBytes)} total)",
        )
    }

    private fun gb(bytes: Long): String {
        val v = bytes / 1024.0 / 1024.0 / 1024.0
        return if (v < 0.05) "${bytes / 1024 / 1024} MB" else "%.2f GB".format(v)
    }
}
