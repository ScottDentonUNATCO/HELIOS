package com.omni.app.agentpool

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.omni.app.hub.HubViewModel
import com.omni.app.memoryui.ChatMemory
import com.omni.app.safety.KillSwitch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * TRACK G — RAM-budgeted offline agent pool.
 *
 * A pool of N worker coroutines ("agents") that share ONE [HubViewModel]
 * queue and ONE [ChatMemory] store. They are not separate processes and do
 * not load separate models — the "per-model footprint" setting is the user's
 * declared RAM budget per agent, used only for the start/stop budget math.
 *
 * Budget rule: totalMb = agents x modelMb; cap = 50% of current availMem.
 * Start is allowed only when totalMb <= cap AND availMb - totalMb >= 200MB.
 * While running, availMem is re-queried every 5s; if headroom drops under
 * 200MB the pool auto-stops with "stopped: RAM headroom critical".
 *
 * Every worker consults [KillSwitch.halted] before claiming work and pauses
 * 2s while it is engaged (in addition to [HubViewModel.runTask]'s own check).
 *
 * Dependencies: stdlib Android (ActivityManager), SharedPreferences,
 * lifecycle ViewModel, coroutines, Compose state. No new libraries, no new
 * permissions.
 */
class AgentPoolViewModel(
    appContext: Context,
    private val hub: HubViewModel,
    private val memory: ChatMemory,
) : ViewModel() {

    companion object {
        private const val PREFS_NAME = "helios_pool"
        private const val KEY_MODEL_MB = "model_mb"
        private const val KEY_AGENTS = "agent_count"
        private const val DEFAULT_MODEL_MB = 800
        private const val DEFAULT_AGENTS = 2
        private const val MIN_AGENTS = 1
        private const val MAX_AGENTS = 8
        private const val HEADROOM_FLOOR_MB = 200L
        private const val KILL_SWITCH_PAUSE_MS = 2_000L
        private const val QUEUE_POLL_MS = 3_000L
        private const val RAM_REQUERY_MS = 5_000L
        private const val TASK_SETTLE_MS = 1_000L
        private const val MB = 1024L * 1024L
    }

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // ---- settings / live state --------------------------------------------

    var modelMb by mutableStateOf(prefs.getInt(KEY_MODEL_MB, DEFAULT_MODEL_MB).coerceAtLeast(1))
        private set

    var agentCount by mutableStateOf(
        prefs.getInt(KEY_AGENTS, DEFAULT_AGENTS).coerceIn(MIN_AGENTS, MAX_AGENTS),
    )
        private set

    var running by mutableStateOf(false)
        private set

    var availMb by mutableStateOf(queryAvailMb())
        private set

    var headroomMb by mutableStateOf(availMb - totalMb())
        private set

    var status by mutableStateOf("pool idle")
        private set

    /** Per-agent completed-task counts; index == agent id. */
    var agentDone by mutableStateOf(List(agentCount) { 0 })
        private set

    val completedTotal: Int get() = agentDone.sum()

    private var poolJob: Job? = null

    // ---- budget -------------------------------------------------------------

    /** Budget formula: totalMb = agents x modelMb. */
    fun totalMb(): Long = agentCount.toLong() * modelMb.toLong()

    /** Cap formula: 50% of current available RAM. */
    fun capMb(): Long = availMb / 2

    /**
     * Null when start is allowed; otherwise the exact blocking reason shown
     * to the user, e.g. "needs 2400MB, cap is 1800MB".
     */
    fun blockingReason(): String? {
        val total = totalMb()
        val cap = capMb()
        return when {
            total > cap ->
                "needs ${total}MB, cap is ${cap}MB"
            availMb - total < HEADROOM_FLOOR_MB ->
                "needs ${HEADROOM_FLOOR_MB}MB headroom after start, only ${availMb - total}MB free"
            else -> null
        }
    }

    fun updateModelMb(mb: Int) {
        if (running) return
        val v = mb.coerceIn(1, 65_536)
        modelMb = v
        prefs.edit().putInt(KEY_MODEL_MB, v).apply()
    }

    fun incAgents() {
        if (!running && agentCount < MAX_AGENTS) {
            agentCount++
            prefs.edit().putInt(KEY_AGENTS, agentCount).apply()
        }
    }

    fun decAgents() {
        if (!running && agentCount > MIN_AGENTS) {
            agentCount--
            prefs.edit().putInt(KEY_AGENTS, agentCount).apply()
        }
    }

    // ---- pool lifecycle -----------------------------------------------------

    fun start() {
        if (running) return
        availMb = queryAvailMb()
        headroomMb = availMb - totalMb()
        val blocked = blockingReason()
        if (blocked != null) {
            status = blocked
            return
        }
        running = true
        agentDone = List(agentCount) { 0 }
        status = "pool running: $agentCount agents"
        poolJob = viewModelScope.launch {
            repeat(agentCount) { i -> launch { agentLoop(i) } }
            launch { ramWatcher() }
        }
    }

    fun stop(reason: String = "pool stopped") {
        poolJob?.cancel()
        poolJob = null
        running = false
        status = reason
    }

    // ---- workers ------------------------------------------------------------

    /**
     * One agent: claim the first QUEUED hub task, run it through the hub,
     * count it only if it settles to COMPLETED. runTask() claims the task
     * synchronously (marks it RUNNING before returning), so the claimed check
     * right after the call attributes the task to this agent.
     */
    private suspend fun agentLoop(index: Int) {
        while (currentCoroutineContext().isActive) {
            if (KillSwitch.halted.value) {
                delay(KILL_SWITCH_PAUSE_MS)
                continue
            }
            val next = hub.tasks.firstOrNull { it.status == HubViewModel.STATUS_QUEUED }
            if (next == null) {
                delay(QUEUE_POLL_MS)
                continue
            }
            hub.runTask(next.id)
            val claimed =
                hub.tasks.firstOrNull { it.id == next.id }?.status == HubViewModel.STATUS_RUNNING
            if (!claimed) {
                // Someone else claimed it, or it failed synchronously (e.g.
                // disabled socket). Move on; the queue poll will skip it.
                delay(TASK_SETTLE_MS)
                continue
            }
            var final = hub.tasks.firstOrNull { it.id == next.id }?.status
            while (currentCoroutineContext().isActive && final == HubViewModel.STATUS_RUNNING) {
                delay(TASK_SETTLE_MS)
                final = hub.tasks.firstOrNull { it.id == next.id }?.status
            }
            if (final == HubViewModel.STATUS_COMPLETED) {
                agentDone = agentDone.toMutableList().also { it[index] = it[index] + 1 }
            }
            delay(QUEUE_POLL_MS)
        }
    }

    /** Re-queries RAM every 5s while running; auto-stops on critical headroom. */
    private suspend fun ramWatcher() {
        while (currentCoroutineContext().isActive) {
            delay(RAM_REQUERY_MS)
            val avail = queryAvailMb()
            availMb = avail
            headroomMb = avail - totalMb()
            if (headroomMb < HEADROOM_FLOOR_MB) {
                stop("stopped: RAM headroom critical")
                return
            }
        }
    }

    private fun queryAvailMb(): Long {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.availMem / MB
    }

    override fun onCleared() {
        poolJob?.cancel()
        super.onCleared()
    }
}

class AgentPoolViewModelFactory(
    private val appContext: Context,
    private val hub: HubViewModel,
    private val memory: ChatMemory,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AgentPoolViewModel(appContext, hub, memory) as T
}
