package com.omni.app.hub

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.app.gamemaker.core.ZineButton
import com.omni.app.gamemaker.core.ZineButtonStyle
import com.omni.app.gamemaker.core.ZineCard
import com.omni.app.gamemaker.core.ZineRansomTitle
import com.omni.app.gamemaker.core.ZineScreenBackdrop
import com.omni.app.gamemaker.core.ZineSectionDivider
import com.omni.app.gamemaker.core.ZineStamp
import com.omni.app.gamemaker.core.LocalZineTheme

/**
 * TRACK H — claim ledger UI.
 */
@Composable
fun ClaimLedgerScreen(vm: ClaimLedgerViewModel, onBack: () -> Unit = {}) {
    val theme = LocalZineTheme.current
    var syncInfo by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // v8: shared backdrop — theme paper + ransom collage bed + ink corners.
        ZineScreenBackdrop()
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TextButton(onClick = onBack) { Text("‹ Back") }
                ZineRansomTitle("Claim ledger")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Checks and balances: every agent output starts as a claim. A *different* agent verifies or falsifies it. Falsified claims are kept with their evidence — nothing is silently deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.onPaper,
                )
            }

            item {
                ZineCard(seed = 7) {
                    Text(
                        text = "Synced claims: ${vm.countFor(ClaimFilter.ALL)} — " +
                            "${vm.countFor(ClaimFilter.PROPOSED)} proposed, " +
                            "${vm.countFor(ClaimFilter.VALIDATED)} validated, " +
                            "${vm.countFor(ClaimFilter.FALSIFIED)} falsified",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onCard,
                    )
                    ZineButton(
                        text = "SYNC FROM HUB",
                        onClick = {
                            // N14: transient feedback so a zero-new-claim sync
                            // doesn't look like a dead button.
                            val before = vm.countFor(ClaimFilter.ALL)
                            vm.syncFromHub()
                            val after = vm.countFor(ClaimFilter.ALL)
                            syncInfo = "Synced ${after - before} new claims ($after total)."
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    syncInfo?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.onCard.copy(alpha = 0.75f),
                        )
                    }
                }
            }

            item {
                // N13: filter chips scroll horizontally so long labels
                // ("VALIDATED (12)") never clip on narrow screens.
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ClaimFilter.entries.forEach { value ->
                        FilterChip(
                            selected = vm.filter == value,
                            onClick = { vm.updateFilter(value) },
                            label = { Text("${value.name} (${vm.countFor(value)})") },
                        )
                    }
                }
            }

            item {
                ZineSectionDivider("CLAIMS (${vm.filteredClaims.size})")
            }

            itemsIndexed(vm.filteredClaims, key = { _, claim -> claim.id }) { index, claim ->
                ZineCard(seed = 20 + index) {
                    ClaimCard(
                        claim = claim,
                        onVerify = { vm.verify(claim.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClaimCard(
    claim: Claim,
    onVerify: () -> Unit,
) {
    val theme = LocalZineTheme.current
    // N15: when no independent verifier exists, further taps just append
    // evidence — so the button goes dead and says so honestly.
    val noVerifier = claim.evidence.contains("no independent verifier available")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                claim.id.take(28),
                fontWeight = FontWeight.SemiBold,
                color = theme.onCard,
                modifier = Modifier.weight(1f),
            )
            ClaimStatusBadge(claim.status)
        }
        Text(
            text = "producer: ${claim.producerSocketId.ifBlank { "(unknown)" }}\n" +
                "verifier: ${claim.verifierSocketId ?: "— not yet verified —"}",
            style = MaterialTheme.typography.bodySmall,
            color = theme.onCard.copy(alpha = 0.7f),
        )
        Text(
            text = claim.content.ifBlank { "(empty claim)" },
            style = MaterialTheme.typography.bodySmall,
            color = theme.onCard,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
        )
        if (claim.evidence.isNotBlank()) {
            Text(
                "Evidence",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                color = theme.onCard,
            )
            Text(
                text = claim.evidence,
                style = MaterialTheme.typography.bodySmall,
                color = theme.onCard.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        if (claim.status == ClaimStatus.PROPOSED) {
            ZineButton(
                text = when {
                    noVerifier -> "NO INDEPENDENT VERIFIER — ADD A SOCKET"
                    claim.verifierSocketId == null -> "VERIFY WITH A DIFFERENT SOCKET"
                    else -> "VERIFICATION IN FLIGHT…"
                },
                onClick = onVerify,
                enabled = claim.verifierSocketId == null && !noVerifier,
                style = ZineButtonStyle.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ClaimStatusBadge(status: ClaimStatus) {
    // Honest proposed/validated/falsified semantics, stamped in zine ink.
    ZineStamp(
        text = status.name,
        color = when (status) {
            ClaimStatus.VALIDATED -> LocalZineTheme.current.accent1
            ClaimStatus.FALSIFIED -> LocalZineTheme.current.stamp
            ClaimStatus.PROPOSED -> LocalZineTheme.current.accent3
        },
    )
}
