package com.omni.app.hub

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.app.safety.KillSwitch
import com.omni.app.sockets.SocketStore
import com.omni.gateway.AiGateway
import com.omni.gateway.Caps
import com.omni.gateway.ChatEvent
import com.omni.gateway.ChatMessage
import com.omni.gateway.actionableGatewayError
import com.omni.memory.HandoffPacket
import com.omni.memory.InMemoryStore
import com.omni.memory.MemoryRecord
import com.omni.memory.MemoryScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/**
 * TRACK D — Neutral hub task board.
 *
 * A local task queue: tasks run on-device through enabled sockets that carry
 * the CHAT capability. Completed tasks persist a [MemoryScope.TASK_HANDOFF]
 * [MemoryRecord] ("task:<id>") into a file-backed [InMemoryStore] living in
 * the app's own JSONL file ("helios_hub.jsonl" in filesDir — never shared with
 * any other track's file). Task history is reloaded from the same file on
 * init; line types are distinguished by a "type" field ("task" | "memory").
 *
 * Before running anything, [runTask] consults [KillSwitch.halted] (owned by
 * Track E): when engaged it refuses with "Kill switch engaged".
 */
class HubViewModel(
    appContext: Context,
    private var gateway: AiGateway,
    private val socketStore: SocketStore,
) : ViewModel() {

    data class HubTask(
        val id: String,
        val title: String,
        val instructions: String,
        val socketId: String,
        val status: String,
        val result: String,
        val createdAt: Long,
    )

    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_IMPORTED = "IMPORTED"

        private const val HUB_FILE = "helios_hub.jsonl"
        private const val DEFAULT_MODEL = "gpt-4o-mini"
        private const val RESULT_SUMMARY_CHARS = 600
    }

    val tasks = mutableStateListOf<HubTask>()
    var runningCount by mutableStateOf(0)
        private set

    /**
     * Last history-persist failure, surfaced so the UI can show it instead of
     * swallowing it. Cleared with [clearPersistError].
     */
    var persistError by mutableStateOf<String?>(null)
        private set

    fun clearPersistError() {
        persistError = null
    }

    private val filesDir: File = appContext.applicationContext.filesDir
    private val store = InMemoryStore()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Guards the check-then-claim in [runTask]: task ids with an in-flight
     * claim. Two workers racing on the same task can never both mark it
     * RUNNING. Entries are removed when the run settles (or the pre-launch
     * checks fail the task); ids stay valid because [tasks] is only ever
     * appended to or index-replaced, never reordered or removed.
     */
    private val claimLock = Any()
    private val claimedIds = mutableSetOf<String>()

    private fun releaseClaim(id: String) {
        synchronized(claimLock) { claimedIds.remove(id) }
    }

    init {
        loadHistory()
    }

    // ---- queue ------------------------------------------------------------

    /** Adds a task with status QUEUED and returns its id. */
    fun postTask(title: String, instructions: String, socketId: String): String {
        require(title.isNotBlank()) { "title must not be blank" }
        require(instructions.isNotBlank()) { "instructions must not be blank" }
        require(socketId.isNotBlank()) { "socketId must not be blank" }
        val task = HubTask(
            id = "task-" + System.currentTimeMillis(),
            title = title.trim(),
            instructions = instructions,
            socketId = socketId,
            status = STATUS_QUEUED,
            result = "",
            createdAt = System.currentTimeMillis(),
        )
        tasks.add(task)
        appendTaskLine(task)
        return task.id
    }

    /**
     * TRACK H — ids of enabled sockets carrying CHAT, excluding [excluding].
     * The claim ledger uses this to pick a verifier socket independent of the
     * producer. Off genuinely means off: [SocketRegistry.withCapability]
     * already filters disabled sockets.
     */
    fun chatSocketIds(excluding: String? = null): List<String> =
        socketStore.registry.withCapability(Caps.CHAT)
            .map { it.id }
            .filter { it != excluding }

    /**
     * Runs a task through its socket. Kill-switch is checked first.
     *
     * Socket routing: the task's [HubTask.socketId] is passed as
     * [AiGateway.generate]'s preferredProvider when the injected gateway was
     * built with one ProviderConfig per socket (provider id == socket id).
     * With the single-default-provider build the socket id is unknown to the
     * gateway and routing falls back to the default provider — see the
     * MainActivity.buildGateway note in the audit report.
     *
     * Claiming is atomic ([claimLock]): exactly one caller ever marks a given
     * task RUNNING. Pre-launch failures release the claim without running.
     */
    /**
     * Swaps the gateway after the host rebuilds it (socket added/removed,
     * key or base URL changed). Tasks already running keep the old one;
     * newly started tasks use the new gateway.
     */
    fun updateGateway(g: AiGateway) {
        gateway = g
    }

    fun runTask(id: String) {
        // Atomic claim — only one caller proceeds past here per task id.
        val idx = synchronized(claimLock) {
            val i = tasks.indexOfFirst { it.id == id }
            if (i < 0) return
            if (tasks[i].status == STATUS_RUNNING || !claimedIds.add(tasks[i].id)) return
            i
        }

        val task = tasks[idx]
        if (KillSwitch.halted.value) {
            releaseClaim(task.id)
            failTask(idx, "Kill switch engaged — task not started. Release it in Safety to run tasks.")
            return
        }

        val def = socketStore.registry.get(task.socketId)
        if (def == null) {
            releaseClaim(task.id)
            failTask(
                idx,
                "Socket '${task.socketId}' is not on the board anymore — " +
                    "pick another socket and re-queue the task.",
            )
            return
        }
        if (!def.enabled) {
            releaseClaim(task.id)
            failTask(
                idx,
                "Socket '${def.displayName}' is OFF — turn it on on the Sockets tab to run this task.",
            )
            return
        }
        if ((def.capabilities and Caps.CHAT) == 0) {
            releaseClaim(task.id)
            failTask(
                idx,
                "Socket '${def.displayName}' doesn't offer CHAT — pick a chat-capable socket.",
            )
            return
        }

        setStatus(idx, STATUS_RUNNING, result = "")
        runningCount++

        val model = def.model ?: DEFAULT_MODEL
        // Route to the task's own socket when the gateway knows it;
        // otherwise fall back to default routing (see KDoc).
        val preferred: String? =
            if (task.socketId in gateway.availableModels()) task.socketId else null
        viewModelScope.launch {
            try {
                val out = StringBuilder()
                gateway.generate(
                    model = model,
                    messages = listOf(ChatMessage(role = "user", content = task.instructions)),
                    preferredProvider = preferred,
                )
                    .flowOn(Dispatchers.IO)
                    .collect { event ->
                        when (event) {
                            is ChatEvent.Token -> out.append(event.text)
                            is ChatEvent.Done -> { /* completion handled below */ }
                            is ChatEvent.Error -> {
                                failTask(indexOf(task.id), actionableGatewayError(event.message))
                            }
                        }
                    }
                // Only reach COMPLETED if no Error was emitted mid-stream.
                val cur = indexOf(task.id)
                if (cur >= 0 && tasks[cur].status == STATUS_RUNNING) {
                    val completed = tasks[cur].copy(status = STATUS_COMPLETED, result = out.toString())
                    tasks[cur] = completed
                    appendTaskLine(completed)
                    persistTaskHandoff(completed)
                }
            } catch (e: Exception) {
                failTask(
                    indexOf(task.id),
                    "Task failed (${e.javaClass.simpleName}): ${e.message ?: "no details"} — " +
                        "check the Sockets tab (key saved, socket on) and retry.",
                )
            } finally {
                if (runningCount > 0) runningCount--
                releaseClaim(task.id)
            }
        }
    }

    // ---- export / import --------------------------------------------------

    /**
     * Serializes a task as a [HandoffPacket]. Mapping:
     * taskId <- HubTask.id, goal <- HubTask.title,
     * stateJson <- JSON object carrying ALL HubTask fields
     *   {id, title, instructions, socketId, status, result, createdAt},
     * memoryRefIds <- ["task:<id>"] when COMPLETED else empty,
     * createdBy <- "hub", budgetTokens <- 0, attempts <- 1.
     */
    fun exportTask(id: String): String {
        val task = tasks.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("no task with id '$id'")
        val state = buildJsonObject {
            put("id", task.id)
            put("title", task.title)
            put("instructions", task.instructions)
            put("socketId", task.socketId)
            put("status", task.status)
            put("result", task.result)
            put("createdAt", task.createdAt)
        }.toString()
        return HandoffPacket(
            taskId = task.id,
            goal = task.title,
            stateJson = state,
            constraints = emptyList(),
            budgetTokens = 0L,
            attempts = 1,
            memoryRefIds = if (task.status == STATUS_COMPLETED) listOf("task:${task.id}") else emptyList(),
            createdBy = "hub",
            createdAtMs = task.createdAt,
        ).toJson()
    }

    /**
     * Rebuilds a [HubTask] from a [HandoffPacket] JSON string.
     * [HandoffPacket.fromJson] gates on schemaVersion (throws on mismatch).
     * The rebuilt task lands with status IMPORTED so it is never mistaken
     * for a task that ran here.
     */
    fun importTask(handoffJson: String): HubTask {
        val packet = HandoffPacket.fromJson(handoffJson) // validates schemaVersion
        val s = json.parseToJsonElement(packet.stateJson).jsonObject
        val task = HubTask(
            id = s["id"]?.jsonPrimitive?.content ?: packet.taskId,
            title = s["title"]?.jsonPrimitive?.content ?: packet.goal,
            instructions = s["instructions"]?.jsonPrimitive?.content ?: "",
            socketId = s["socketId"]?.jsonPrimitive?.content ?: "",
            status = STATUS_IMPORTED,
            result = s["result"]?.jsonPrimitive?.content ?: "",
            createdAt = s["createdAt"]?.jsonPrimitive?.long ?: packet.createdAtMs,
        )
        tasks.add(task)
        appendTaskLine(task)
        return task
    }

    // ---- internals --------------------------------------------------------

    private fun indexOf(id: String): Int = tasks.indexOfFirst { it.id == id }

    private fun setStatus(idx: Int, status: String, result: String = tasks[idx].result) {
        if (idx < 0) return
        val updated = tasks[idx].copy(status = status, result = result)
        tasks[idx] = updated
        appendTaskLine(updated)
    }

    private fun failTask(idx: Int, message: String) {
        if (idx < 0) return
        val updated = tasks[idx].copy(status = STATUS_FAILED, result = message)
        tasks[idx] = updated
        appendTaskLine(updated)
    }

    /** Persists the completion summary as a TASK_HANDOFF memory. */
    private fun persistTaskHandoff(task: HubTask) {
        val now = System.currentTimeMillis()
        val summary = (task.title + "\n" + task.result.take(RESULT_SUMMARY_CHARS)).trim()
        val record = MemoryRecord(
            id = "task:${task.id}",
            scope = MemoryScope.TASK_HANDOFF,
            key = task.title,
            value = summary,
            salience = 0.7,
            createdAtMs = now,
            updatedAtMs = now,
            version = 1,
            provenance = "hub",
        )
        store.put(record)
        appendMemoryLine(record)
    }

    private fun hubFile(): File = File(filesDir, HUB_FILE)

    private fun appendTaskLine(task: HubTask) {
        val line = buildJsonObject {
            put("type", "task")
            put("id", task.id)
            put("title", task.title)
            put("instructions", task.instructions)
            put("socketId", task.socketId)
            put("status", task.status)
            put("result", task.result)
            put("createdAt", task.createdAt)
        }.toString()
        appendLine(line)
    }

    private fun appendMemoryLine(record: MemoryRecord) {
        val line = buildJsonObject {
            put("type", "memory")
            put("id", record.id)
            put("scope", record.scope.name)
            put("key", record.key)
            put("value", record.value)
            put("salience", record.salience)
            put("createdAtMs", record.createdAtMs)
            put("updatedAtMs", record.updatedAtMs)
            put("version", record.version)
            put("provenance", record.provenance)
            put("pinned", record.pinned)
        }.toString()
        appendLine(line)
    }

    private fun appendLine(line: String) {
        runCatching {
            hubFile().appendText(line + "\n", Charsets.UTF_8)
        }.onFailure { e ->
            persistError = "Hub history couldn't be saved (${e.javaClass.simpleName}) — " +
                "check device storage. Tasks still run, but history may be lost on restart."
        }
    }

    /** Rebuilds task history and the in-memory store from the JSONL file. */
    private fun loadHistory() {
        val file = hubFile()
        if (!file.exists()) return
        val seen = LinkedHashMap<String, HubTask>()
        runCatching {
            file.forEachLine(Charsets.UTF_8) { raw ->
                if (raw.isBlank()) return@forEachLine
                val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    ?: return@forEachLine
                when (obj["type"]?.jsonPrimitive?.content) {
                    "task" -> {
                        val task = taskFrom(obj)
                        seen[task.id] = task // later lines win: latest status
                    }
                    "memory" -> {
                        val record = memoryFrom(obj) ?: return@forEachLine
                        runCatching { store.put(record) }
                    }
                }
            }
        }
        tasks.addAll(seen.values.sortedBy { it.createdAt })
    }

    private fun taskFrom(o: JsonObject): HubTask = HubTask(
        id = o["id"]?.jsonPrimitive?.content ?: "task-${System.currentTimeMillis()}",
        title = o["title"]?.jsonPrimitive?.content ?: "",
        instructions = o["instructions"]?.jsonPrimitive?.content ?: "",
        socketId = o["socketId"]?.jsonPrimitive?.content ?: "",
        status = o["status"]?.jsonPrimitive?.content ?: STATUS_QUEUED,
        result = o["result"]?.jsonPrimitive?.content ?: "",
        createdAt = o["createdAt"]?.jsonPrimitive?.long ?: System.currentTimeMillis(),
    )

    private fun memoryFrom(o: JsonObject): MemoryRecord? = runCatching {
        MemoryRecord(
            id = o.getValue("id").jsonPrimitive.content,
            scope = MemoryScope.valueOf(o.getValue("scope").jsonPrimitive.content),
            key = o.getValue("key").jsonPrimitive.content,
            value = o.getValue("value").jsonPrimitive.content,
            salience = o.getValue("salience").jsonPrimitive.double,
            createdAtMs = o.getValue("createdAtMs").jsonPrimitive.long,
            updatedAtMs = o.getValue("updatedAtMs").jsonPrimitive.long,
            version = o.getValue("version").jsonPrimitive.long,
            provenance = o.getValue("provenance").jsonPrimitive.content,
            pinned = o["pinned"]?.jsonPrimitive?.content == "true",
        )
    }.getOrNull()
}
