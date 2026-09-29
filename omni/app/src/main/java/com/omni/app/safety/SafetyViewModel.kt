package com.omni.app.safety

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.omni.app.sockets.SocketStore
import com.omni.gateway.SocketDef
import com.omni.gateway.SpendTracker
import com.omni.vision.ScreenCaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Safety / kill-switch logic.
 *
 * - [engage]: trips [KillSwitch], stops screen capture, and disables every
 *   socket except the pinned essential one. Records when it happened and what
 *   was disabled so the confirmation card can show it.
 * - [release]: trips the switch back off. It does NOT re-enable sockets —
 *   recovery is deliberate: the user re-enables sockets one by one on the
 *   Sockets screen.
 */
class SafetyViewModel(
    appContext: Context,
    private val socketStore: SocketStore,
    private val spendTracker: SpendTracker,
    private val budgetCapUsd: Double,
) : ViewModel() {

    private val context: Context = appContext.applicationContext

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_SAFETY, Context.MODE_PRIVATE)

    private val _sockets = MutableStateFlow<List<SocketDef>>(emptyList())
    val sockets: StateFlow<List<SocketDef>> = _sockets.asStateFlow()

    private val _essentialSocketId = MutableStateFlow<String?>(loadEssential())
    val essentialSocketId: StateFlow<String?> = _essentialSocketId.asStateFlow()

    private val _halted = MutableStateFlow(KillSwitch.halted.value)
    val halted: StateFlow<Boolean> = _halted.asStateFlow()

    private val _engagedAt = MutableStateFlow<Long?>(loadEngagedAt())
    val engagedAt: StateFlow<Long?> = _engagedAt.asStateFlow()

    private val _disabledSocketNames = MutableStateFlow<List<String>>(emptyList())
    val disabledSocketNames: StateFlow<List<String>> = _disabledSocketNames.asStateFlow()

    private val _spendVersion = MutableStateFlow(0)
    val spendVersion: StateFlow<Int> = _spendVersion.asStateFlow()

    init {
        // A halted switch stays halted across process death: an engaged kill
        // switch that silently disarmed on restart would be a safety hole.
        // Sockets stay disabled independently (persisted in SocketStore).
        if (prefs.getBoolean(KEY_HALTED, false)) KillSwitch.engage()
        refreshSockets()
        viewModelScope.launch {
            KillSwitch.halted.collect { _halted.value = it }
        }
    }

    fun refreshSockets() {
        _sockets.value = socketStore.all()
    }

    /** Persists the pinned essential socket. null = none pinned (engage disables ALL). */
    fun setEssentialSocket(id: String?) {
        val clean = id?.ifBlank { null }
        if (clean == null) {
            prefs.edit().remove(KEY_ESSENTIAL_SOCKET_ID).apply()
        } else {
            prefs.edit().putString(KEY_ESSENTIAL_SOCKET_ID, clean).apply()
        }
        _essentialSocketId.value = clean
    }

    /** Big red button. Trips the switch, stops capture, disables sockets (except pinned). */
    fun engage() {
        KillSwitch.engage()
        ScreenCaptureService.stop(context)

        val engagedAtMs = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(KEY_HALTED, true)
            .putLong(KEY_ENGAGED_AT, engagedAtMs)
            .apply()

        val essential = _essentialSocketId.value
        val disabledNames = mutableListOf<String>()
        refreshSockets()
        for (def in _sockets.value) {
            if (essential != null && def.id == essential) continue
            socketStore.setEnabled(def.id, false)
            disabledNames.add(def.displayName)
        }
        _disabledSocketNames.value = disabledNames
        _engagedAt.value = engagedAtMs
        refreshSockets()
        _halted.value = KillSwitch.halted.value
    }

    /**
     * Releases the kill switch. Does NOT re-enable sockets — the user
     * re-enables them manually on the Sockets screen. Recovery is deliberate.
     */
    fun release() {
        KillSwitch.disengage()
        prefs.edit()
            .putBoolean(KEY_HALTED, false)
            .remove(KEY_ENGAGED_AT)
            .apply()
        _engagedAt.value = null
        _halted.value = false
        refreshSockets()
    }

    fun spentUsd(): Double = spendTracker.totalUsd()

    fun budgetCap(): Double = budgetCapUsd

    fun isOverBudget(): Boolean = !spendTracker.checkBudget(budgetCapUsd)

    fun resetSpend() {
        spendTracker.reset()
        _spendVersion.value = _spendVersion.value + 1
    }

    private fun loadEssential(): String? =
        prefs.getString(KEY_ESSENTIAL_SOCKET_ID, null)?.ifBlank { null }

    private fun loadEngagedAt(): Long? =
        prefs.getLong(KEY_ENGAGED_AT, -1L).takeIf { it >= 0 }

    companion object {
        const val PREFS_SAFETY = "helios_safety"
        const val KEY_ESSENTIAL_SOCKET_ID = "essential_socket_id"
        const val KEY_HALTED = "killswitch_halted"
        const val KEY_ENGAGED_AT = "killswitch_engaged_at"
    }
}

class SafetyViewModelFactory(
    private val appContext: Context,
    private val socketStore: SocketStore,
    private val spendTracker: SpendTracker,
    private val budgetCapUsd: Double,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SafetyViewModel(appContext, socketStore, spendTracker, budgetCapUsd) as T
}
