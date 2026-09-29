package com.omni.app.safety

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Global kill switch for the Helios app.
 *
 * Owned by Track E. Tracks A and D read [halted] to gate new work:
 * - Track A (gateway): checks halted before starting a NEW request. In-flight
 *   requests finish their current response; no remote-cancel API exists, so
 *   that is the honest bound.
 * - Track D (agents/autonomy): checks halted before dispatching new agent
 *   actions.
 */
object KillSwitch {
    val halted = MutableStateFlow(false)

    fun engage() {
        halted.value = true
    }

    fun disengage() {
        halted.value = false
    }
}
