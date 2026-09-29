package com.omni.app.gamemaker.nes

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.app.sockets.SocketStore
import com.omni.app.vault.AndroidVault
import com.omni.gateway.SocketDef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import omni.nes.validate.Finding

/**
 * State for the NES game-maker screen. Survives rotation (it's a ViewModel).
 *
 * Draft source selection: the user picks one of their sockets; if the socket
 * draft fails (or no socket is picked) and [useTemplateFallback] is on, the
 * run continues with the deterministic offline template generator — and the
 * UI labels the output as template-generated. Off genuinely means off: with
 * the fallback disabled and no working socket, the run fails honestly.
 */
class NesMakerViewModel(
    private val store: SocketStore,
    private val vault: AndroidVault,
) : ViewModel() {

    enum class StageState { PENDING, ACTIVE, DONE, FAILED }

    var brief by mutableStateOf("")
    var socketId by mutableStateOf<String?>(null)
    var useTemplateFallback by mutableStateOf(true)

    var running by mutableStateOf(false)
        private set

    /** Live timeline: every stage's current state + detail line. */
    val stages = mutableStateMapOf<MakerStage, Pair<StageState, String?>>()

    /** Per-attempt validator summaries, in order. */
    val attempts = mutableStateListOf<AttemptSummary>()

    /** Fired validator findings from the latest (or final) report. */
    val findings = mutableStateListOf<Finding>()

    /** PPU-rendered screenshots from the successful run. */
    val frames = mutableStateListOf<Bitmap>()

    var result: MakerResult? by mutableStateOf(null)
        private set
    var failedReason by mutableStateOf<String?>(null)
        private set
    var exportPath by mutableStateOf<String?>(null)
        private set
    var draftSourceLabel by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null
    private val maker = NesGameMaker()

    fun sockets(): List<SocketDef> = store.all()

    fun clearNotice() { notice = null }

    fun cancel() {
        job?.cancel()
        job = null
        running = false
        markStagesFailed("cancelled")
    }

    fun start() {
        if (running) return
        if (brief.isBlank()) {
            notice = "Describe the game first."
            return
        }
        reset()
        running = true

        val id = socketId
        val draft: LlmDraft
        val label: String
        if (id != null) {
            draft = SocketLlmDraft(id, store, vault)
            label = "socket: ${store.registry.get(id)?.displayName ?: id}"
        } else if (useTemplateFallback) {
            draft = maker.templateDraftFor(brief)
            label = "offline template generator"
            notice = "No socket picked — using the offline template generator. " +
                "The ROM is still real and validator-checked."
        } else {
            failedReason = "No socket picked and the template fallback is OFF. " +
                "Pick a socket (or turn the fallback on)."
            markStagesFailed("no draft source")
            running = false
            return
        }
        draftSourceLabel = label

        job = viewModelScope.launch {
            try {
                maker.makeGame(brief, draft).collect { event ->
                    onEvent(event, draft, brief)
                }
            } catch (e: CancellationException) {
                // Cancel is user action, not a crash: cancel() already marked
                // the stages; let the cancellation propagate normally.
                throw e
            } catch (e: Exception) {
                failedReason = "Run crashed: ${e.javaClass.simpleName}: ${e.message}"
                markStagesFailed("crashed")
            } finally {
                running = false
            }
        }
    }

    private suspend fun onEvent(event: MakerEvent, draft: LlmDraft, briefText: String) {
        when (event) {
            is MakerEvent.Stage -> {
                stages[event.stage] = StageState.ACTIVE to event.detail
            }
            is MakerEvent.AttemptReport -> {
                val s = event.summary
                val idx = attempts.indexOfFirst { it.attempt == s.attempt }
                if (idx >= 0) attempts[idx] = s else attempts.add(s)
                if (s.buildError == null) {
                    stages[MakerStage.VALIDATING] = StageState.DONE to
                        if (s.firedRules.isEmpty()) "attempt ${s.attempt}: GREEN"
                        else "attempt ${s.attempt} RED: ${s.firedRules.joinToString(", ")}"
                } else {
                    stages[MakerStage.ASSEMBLING] = StageState.DONE to
                        "attempt ${s.attempt}: ${s.buildError}"
                }
            }
            is MakerEvent.Done -> {
                // Only stages that actually ran (ACTIVE) become DONE;
                // stages that never fired stay PENDING.
                for (s in MakerStage.entries) {
                    if (stages[s]?.first == StageState.ACTIVE) {
                        stages[s] = StageState.DONE to stages[s]?.second
                    }
                }
                result = event.result
                frames.addAll(event.result.frames)
                findings.clear()
                findings.addAll(event.result.report.findings)
            }
            is MakerEvent.Failed -> {
                failedReason = event.reason
                attempts.clear()
                attempts.addAll(event.attempts)
                markStagesFailed(event.reason)
                // If the socket draft was the problem and fallback is allowed,
                // retry once with the template generator — labeled honestly.
                // The notice names which stage actually failed: a dead draft
                // vs. a live draft whose ROM stayed RED through every attempt.
                if (draft is SocketLlmDraft && useTemplateFallback && result == null) {
                    notice = if (event.reason.startsWith("Drafting failed")) {
                        "Socket draft failed — retrying with the offline template generator."
                    } else {
                        "Socket draft succeeded but the ROM stayed RED after " +
                            "${event.attempts.size} attempts — " +
                            "retrying with the offline template generator."
                    }
                    draftSourceLabel = "offline template generator (fallback)"
                    attempts.clear()
                    findings.clear()
                    failedReason = null
                    resetStagesOnly()
                    val template = maker.templateDraftFor(briefText)
                    maker.makeGame(briefText, template).collect { e2 ->
                        onEvent(e2, template, briefText)
                    }
                }
            }
        }
    }

    private fun reset() {
        stages.clear()
        for (s in MakerStage.entries) stages[s] = StageState.PENDING to null
        attempts.clear()
        findings.clear()
        frames.clear()
        result = null
        failedReason = null
        exportPath = null
        notice = null
    }

    private fun resetStagesOnly() {
        stages.clear()
        for (s in MakerStage.entries) stages[s] = StageState.PENDING to null
        failedReason = null
    }

    private fun markStagesFailed(reason: String) {
        for (stage in stages.keys.toList()) {
            val state = stages[stage]?.first
            if (state == StageState.ACTIVE || state == StageState.PENDING) {
                stages[stage] = StageState.FAILED to reason
            }
        }
    }

    /** Exports the GREEN ROM to app-private storage; shows the saved path. */
    fun export(context: Context) {
        val r = result ?: run {
            notice = "Nothing to export — no GREEN ROM yet."
            return
        }
        exportPath = try {
            maker.exportRom(context, r.rom, brief).absolutePath
        } catch (e: Exception) {
            notice = "Export failed: ${e.message}"
            null
        }
    }

    // end of NesMakerViewModel
}
