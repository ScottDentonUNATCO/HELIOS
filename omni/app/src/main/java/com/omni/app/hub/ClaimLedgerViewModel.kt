package com.omni.app.hub

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Filter for the claim ledger list. */
enum class ClaimFilter { ALL, PROPOSED, VALIDATED, FALSIFIED }

/**
 * TRACK H — owns a [ClaimLedger] and exposes it to Compose.
 *
 * The ledger screen drives two operations:
 * - [syncFromHub]: record claims for completed hub tasks + settle finished
 *   verifications.
 * - [verify]: start a different-socket verification of a PROPOSED claim.
 */
class ClaimLedgerViewModel(
    appContext: Context,
    hub: HubViewModel,
) : ViewModel() {

    private val ledger = ClaimLedger(appContext, hub)
    private val hubRef = hub

    val claims get() = ledger.claims

    var filter by mutableStateOf(ClaimFilter.ALL)
        private set

    fun updateFilter(value: ClaimFilter) {
        filter = value
    }

    val filteredClaims: List<Claim>
        get() = when (filter) {
            ClaimFilter.ALL -> claims.toList()
            ClaimFilter.PROPOSED -> claims.filter { it.status == ClaimStatus.PROPOSED }
            ClaimFilter.VALIDATED -> claims.filter { it.status == ClaimStatus.VALIDATED }
            ClaimFilter.FALSIFIED -> claims.filter { it.status == ClaimStatus.FALSIFIED }
        }

    fun countFor(value: ClaimFilter): Int = when (value) {
        ClaimFilter.ALL -> claims.size
        ClaimFilter.PROPOSED -> claims.count { it.status == ClaimStatus.PROPOSED }
        ClaimFilter.VALIDATED -> claims.count { it.status == ClaimStatus.VALIDATED }
        ClaimFilter.FALSIFIED -> claims.count { it.status == ClaimStatus.FALSIFIED }
    }

    /**
     * Sync pass: records a PROPOSED claim for every COMPLETED hub task not
     * yet recorded (idempotent), then settles verification tasks that
     * finished since the last pass.
     */
    fun syncFromHub() {
        hubRef.tasks
            .filter { it.status == HubViewModel.STATUS_COMPLETED }
            .forEach { ledger.recordFromTask(it) }
        ledger.syncVerifications()
    }

    /** Starts verification of a PROPOSED claim by a different socket. */
    fun verify(id: String) = ledger.verify(id)
}
