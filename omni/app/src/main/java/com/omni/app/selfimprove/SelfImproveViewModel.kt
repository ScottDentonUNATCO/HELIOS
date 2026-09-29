package com.omni.app.selfimprove

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.app.hub.HubViewModel
import com.omni.app.memoryui.ChatMemory
import com.omni.app.safety.KillSwitch
import com.omni.app.sockets.SocketStore
import com.omni.gateway.Caps
import com.omni.gateway.SocketDef
import com.omni.memory.MemoryRecord
import com.omni.memory.MemoryScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * TRACK F — Self-improvement loop.
 *
 * Draft-and-falsify cycle for on-device behaviors and procedures:
 *
 * 1. An enabled CHAT socket *drafts* one PROCEDURE candidate (prompt recipe,
 *    router rule, or validation check) for a goal, as JSON.
 * 2. A DIFFERENT enabled CHAT socket *falsifies* it: either it cites the
 *    specific expectation the candidate breaks, or it replies HOLDS.
 * 3. The verdict is persisted into [MemoryScope.PROCEDURE]:
 *    - falsified  -> kept forever, salience 0.3, unpinned (never deleted);
 *    - validated  -> salience 0.85, pinned (recall/router prefer pins).
 *
 * Reuses [HubViewModel.postTask]/[runTask] for all gateway work and
 * [ChatMemory.updateRecord]/[allRecords] for persistence. Before firing any
 * gateway work the [KillSwitch] is consulted, mirroring HubViewModel.
 */
class SelfImproveViewModel(
    appContext: Context,
    private val hub: HubViewModel,
    private val memory: ChatMemory,
) : ViewModel() {

    private val filesDir: File = appContext.applicationContext.filesDir

    /**
     * HubViewModel keeps its own SocketStore private; the store is built from
     * the same Context and reads the same persisted enable/disable flags from
     * SharedPreferences, so this list agrees with what HubViewModel routes on.
     */
    private val socketStore = SocketStore(appContext.applicationContext)

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Current cycle status, shown verbatim in the UI. */
    var status by mutableStateOf("Idle — pick a goal and a producer socket, then run a cycle.")
        private set

    /** Goal text entered on the screen. */
    var goalText by mutableStateOf("")
        private set

    /** Validated and falsified PROCEDURE records, newest first. */
    var procedures by mutableStateOf<List<MemoryRecord>>(emptyList())
        private set

    /** Last export outcome (absolute path on success, error message on failure). */
    var exportInfo by mutableStateOf<String?>(null)
        private set

    init {
        refreshProcedures()
    }

    /** Enabled sockets that can carry chat work. */
    fun chatSockets(): List<SocketDef> =
        socketStore.all().filter { it.enabled && (it.capabilities and Caps.CHAT) != 0 }

    fun onGoalChange(text: String) {
        goalText = text
    }

    fun refreshProcedures() {
        procedures = memory.allRecords().filter { it.scope == MemoryScope.PROCEDURE }
    }

    /**
     * Runs one draft-and-falsify cycle:
     * (a) kill-switch check; (b) draft task on [producerSocketId], parsed as
     * JSON {kind, title, body}; (c) falsify task on a DIFFERENT enabled CHAT
     * socket; (d) persist the PROCEDURE record with the verdict.
     */
    fun draftCycle(goal: String, producerSocketId: String) {
        if (KillSwitch.halted.value) {
            status = "Refused: kill switch is engaged."
            return
        }
        val cleanGoal = goal.trim()
        if (cleanGoal.isBlank()) {
            status = "Enter a goal first."
            return
        }
        val producers = chatSockets()
        val producer = producers.firstOrNull { it.id == producerSocketId }
        if (producer == null) {
            status = "Producer socket is not an enabled CHAT socket."
            return
        }
        val falsifier = producers.firstOrNull { it.id != producerSocketId }
        if (falsifier == null) {
            status = "Need at least two distinct enabled CHAT sockets " +
                "(one drafts, a different one falsifies)."
            return
        }

        viewModelScope.launch {
            try {
                // (b) Draft on the producer socket.
                status = "Drafting candidate on ${producer.displayName}…"
                val draftInstructions =
                    "Draft one PROCEDURE candidate (a prompt recipe, router rule, or validation check) " +
                        "for this goal: $cleanGoal. Reply as JSON {kind, title, body}."
                val draftId = hub.postTask("Self-improve draft", draftInstructions, producer.id)
                hub.runTask(draftId)
                val draftTask = awaitTask(draftId)
                val draft = parseDraft(draftTask.result)
                if (draft == null) {
                    status = "Draft failed: the producer did not return parseable JSON " +
                        "{kind, title, body}. No record was written."
                    return@launch
                }

                // (c) Falsify on a DIFFERENT socket.
                status = "Falsifying candidate on ${falsifier.displayName}…"
                val falsifyInstructions =
                    "Try to falsify this procedure candidate: ${draft.toCompactJson()}. " +
                        "Either cite the specific expectation it breaks, or reply HOLDS."
                val falsifyId =
                    hub.postTask("Self-improve falsify", falsifyInstructions, falsifier.id)
                hub.runTask(falsifyId)
                val falsifyTask = awaitTask(falsifyId)

                // (d) Parse the verdict and persist.
                val verdict = falsifyTask.result.trim()
                val holds = verdict.startsWith("HOLDS", ignoreCase = true)
                val now = System.currentTimeMillis()
                val record = if (holds) {
                    MemoryRecord(
                        id = "selfimprove-" + UUID.randomUUID(),
                        scope = MemoryScope.PROCEDURE,
                        key = draft.title.ifBlank { "Untitled procedure" },
                        value = draft.body +
                            "\n---\nVALIDATED by ${falsifier.displayName} on ${dateFmt.format(Date(now))}",
                        salience = 0.85,
                        createdAtMs = now,
                        updatedAtMs = now,
                        version = 1L,
                        provenance = "selfimprove",
                        pinned = true,
                    )
                } else {
                    MemoryRecord(
                        id = "selfimprove-" + UUID.randomUUID(),
                        scope = MemoryScope.PROCEDURE,
                        key = draft.title.ifBlank { "Untitled procedure" },
                        value = draft.body +
                            "\n---\nFALSIFIED by ${falsifier.displayName}: ${maskSecretsLast4(verdict)}",
                        salience = 0.3,
                        createdAtMs = now,
                        updatedAtMs = now,
                        version = 1L,
                        provenance = "selfimprove",
                        pinned = false,
                    )
                }
                // Falsified procedures are KEPT, never deleted. store.put()
                // auto-scrubs secrets from values before they reach storage.
                memory.updateRecord(record)
                refreshProcedures()
                status = if (holds) {
                    "VALIDATED by ${falsifier.displayName} — pinned procedure stored."
                } else {
                    "FALSIFIED by ${falsifier.displayName} — kept as a low-salience record."
                }
            } catch (e: TimeoutCancellationException) {
                status = "Cycle timed out waiting for the hub task — no record was written."
            } catch (e: Exception) {
                status = "Cycle failed (${e.javaClass.simpleName}): ${e.message ?: "no details"} — " +
                    "check the socket keys on the Sockets tab and your connection, then run again."
            }
        }
    }

    /**
     * Writes the PROCEDURE record with [recordId] as JSON to
     * filesDir/improvements/<id>.json and returns the absolute path.
     */
    fun exportProcedure(recordId: String): String {
        val record = memory.allRecords()
            .firstOrNull { it.id == recordId && it.scope == MemoryScope.PROCEDURE }
            ?: run {
                val msg = "No PROCEDURE record '$recordId'."
                exportInfo = "Export failed: $msg"
                throw IllegalArgumentException(msg)
            }
        val dir = File(filesDir, EXPORT_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            val msg = "Could not create $EXPORT_DIR."
            exportInfo = "Export failed: $msg"
            throw IllegalStateException(msg)
        }
        val safeName = record.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val out = File(dir, "$safeName.json")
        runCatching { out.writeText(ChatMemory.recordToJson(record)) }
            .onFailure { e ->
                exportInfo = "Export failed: ${e.message}"
                throw e
            }
        exportInfo = "Exported: ${out.absolutePath}"
        return out.absolutePath
    }

    // ---- internals --------------------------------------------------------

    /** Polls hub.tasks until the task COMPLETES or FAILS (withTimeout raises). */
    private suspend fun awaitTask(id: String): HubViewModel.HubTask =
        withTimeout(TASK_TIMEOUT_MS) {
            var result: HubViewModel.HubTask? = null
            while (result == null) {
                val task = hub.tasks.firstOrNull { it.id == id }
                    ?: throw IllegalStateException("hub task disappeared mid-run")
                when (task.status) {
                    HubViewModel.STATUS_COMPLETED -> result = task
                    HubViewModel.STATUS_FAILED ->
                        throw IllegalStateException("hub task failed: ${task.result}")
                    else -> delay(POLL_MS)
                }
            }
            result
        }

    /**
     * Parses the draft result into a [DraftCandidate].
     * - Strips markdown code fences (```json … ```) if the model wrapped it.
     * - If the text isn't a bare object, extracts the first {...} substring.
     * - Reads kind/title/body via contentOrNull so missing keys yield "".
     * Returns null when no JSON object can be found at all (parse failure).
     */
    private fun parseDraft(raw: String): DraftCandidate? {
        val fenced = stripFences(raw)
        val obj = runCatching { json.parseToJsonElement(fenced).jsonObject }.getOrNull()
            ?: runCatching {
                val start = fenced.indexOf('{')
                val end = fenced.lastIndexOf('}')
                if (start < 0 || end <= start) return null
                json.parseToJsonElement(fenced.substring(start, end + 1)).jsonObject
            }.getOrNull()
            ?: return null
        return DraftCandidate(
            kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: "",
            title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
            body = obj["body"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    private fun stripFences(s: String): String {
        val t = s.trim()
        if (!t.startsWith("```")) return t
        val firstNl = t.indexOf('\n')
        if (firstNl < 0) return t
        var body = t.substring(firstNl + 1)
        val end = body.lastIndexOf("```")
        if (end >= 0) body = body.substring(0, end)
        return body.trim()
    }

    /** Shows only the last 4 characters of key-like tokens; everything else passes through. */
    private fun maskSecretsLast4(s: String): String =
        s.replace(Regex("(?i)(api[_-]?key|secret|token|password|bearer)[\"'\\s:=-]*([A-Za-z0-9_.\\-/+]{8,})")) {
            it.groupValues[1] + ":…" + it.groupValues[2].takeLast(4)
        }.replace(Regex("sk-[A-Za-z0-9]{8,}")) {
            "sk-…${it.value.takeLast(4)}"
        }

    private data class DraftCandidate(
        val kind: String,
        val title: String,
        val body: String,
    ) {
        fun toCompactJson(): String = buildJsonObject {
            put("kind", kind)
            put("title", title)
            put("body", body)
        }.toString()
    }

    companion object {
        private const val EXPORT_DIR = "improvements"
        private const val POLL_MS = 500L
        private const val TASK_TIMEOUT_MS = 120_000L
    }
}
