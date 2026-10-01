package com.omni.app.offline

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.omni.gateway.ModelLoadGate
import java.io.File

/**
 * On-device inference runtime seam.
 *
 * The runtime is NOT yet built (no JNI in the app, verified). When a real
 * runtime lands (llama.cpp via JNI, MediaPipe, …), it implements this
 * interface and the manager below needs no changes — drop-in by construction.
 */
interface OfflineRuntime {
    /** Stable id, e.g. "llamacpp-jni". */
    val id: String
    /** Load [modelFile] exclusively. Returns failure when it cannot. */
    fun load(modelFile: File): Result<Unit>
    /** Unload whatever is loaded, if anything. Never throws. */
    fun unload()
    /** Currently loaded model file, or null. */
    val loadedModel: File?
}

/** Honest placeholder: every load fails with "not yet built". */
class NotBuiltRuntime : OfflineRuntime {
    override val id: String = "not-built"
    override fun load(modelFile: File): Result<Unit> =
        Result.failure(UnsupportedOperationException("on-device inference runtime is not yet built"))
    override fun unload() = Unit
    override val loadedModel: File? = null
}

/** Outcome of a load attempt, shown verbatim in the UI. */
sealed interface ModelLoadOutcome {
    data class Loaded(val path: String, val gateReason: String) : ModelLoadOutcome
    data class Denied(val reason: String) : ModelLoadOutcome
    data class RuntimeFailed(val reason: String) : ModelLoadOutcome
}

/**
 * One-at-a-time model manager.
 *
 * Policy (enforced here, tested via [ModelLoadGate] on the build machine):
 * - Only ONE model is ever loaded: [loadModel] unloads the current one first.
 * - The [ModelLoadGate] RAM policy decides before the runtime is touched:
 *   a model may claim at most 50% of total RAM and must leave 512 MB free.
 * - The runtime itself sits behind [OfflineRuntime]; today that is
 *   [NotBuiltRuntime], so loads are denied honestly at the runtime step with
 *   the gate decision still reported.
 */
class OfflineModelManager(
    appContext: Context,
    var runtime: OfflineRuntime = NotBuiltRuntime(),
) {
    private val ctx: Context = appContext.applicationContext

    var loadedPath by mutableStateOf<String?>(null)
        private set
    var lastOutcome by mutableStateOf<ModelLoadOutcome?>(null)
        private set

    /** Current device memory picture for the gate + UI. */
    fun memoryInfo(): Pair<Long, Long> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem to mi.availMem
    }

    /**
     * Load [modelFile]: unload current first (one-at-a-time), run the RAM
     * gate, then hand to the runtime. Never throws — the outcome is returned
     * and stored for the UI.
     */
    fun loadModel(modelFile: File): ModelLoadOutcome {
        if (!modelFile.isFile) {
            return deny("not a file: ${modelFile.absolutePath}")
        }
        // One-at-a-time: unload before even asking the gate.
        if (loadedPath != null) unloadModel()

        val (totalMem, availMem) = memoryInfo()
        val gate = ModelLoadGate.decide(modelFile.length(), totalMem, availMem)
        if (!gate.allowed) return deny("RAM gate: ${gate.reason}")

        val result = runCatching { runtime.load(modelFile) }
        val failure = result.exceptionOrNull()
        return if (failure == null) {
            loadedPath = modelFile.absolutePath
            ModelLoadOutcome.Loaded(modelFile.absolutePath, gate.reason).also { lastOutcome = it }
        } else {
            ModelLoadOutcome.RuntimeFailed(
                "runtime '${runtime.id}' failed: ${failure.message}",
            ).also { lastOutcome = it }
        }
    }

    /** Unload the current model, if any. Never throws. */
    fun unloadModel() {
        runCatching { runtime.unload() }
        loadedPath = null
    }

    private fun deny(reason: String): ModelLoadOutcome.Denied =
        ModelLoadOutcome.Denied(reason).also { lastOutcome = it }
}
