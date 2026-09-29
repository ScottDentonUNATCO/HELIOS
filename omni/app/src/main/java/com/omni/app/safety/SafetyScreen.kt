package com.omni.app.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.omni.app.gamemaker.core.ZineScreenScaffold
import com.omni.app.gamemaker.core.ZineSectionDivider
import com.omni.app.gamemaker.core.ZineStamp
import com.omni.app.gamemaker.core.LocalZineTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(millis))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyScreen(vm: SafetyViewModel, onBack: () -> Unit) {
    val halted by vm.halted.collectAsState()
    val sockets by vm.sockets.collectAsState()
    val essentialId by vm.essentialSocketId.collectAsState()
    val engagedAt by vm.engagedAt.collectAsState()
    val disabledNames by vm.disabledSocketNames.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val spendVersion by vm.spendVersion.collectAsState()

    // Two-tap confirm for the big red button.
    var armed by remember { mutableStateOf(false) }

    val spent = vm.spentUsd()
    val cap = vm.budgetCap()
    val overBudget = vm.isOverBudget()
    val theme = LocalZineTheme.current

    ZineScreenScaffold(title = "Safety", onBack = onBack) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Status card ----
            if (halted) {
                ZineCard(seed = 1) {
                    ZineStamp("KILL SWITCH ENGAGED")
                    Text(
                        "Capture stopped, ${disabledNames.size} sockets disabled, new sends blocked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.onCard,
                    )
                    engagedAt?.let {
                        Text(
                            "Engaged at ${formatTime(it)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.onCard.copy(alpha = 0.75f),
                        )
                    }
                    if (disabledNames.isNotEmpty()) {
                        Text(
                            "Disabled: ${disabledNames.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.onCard.copy(alpha = 0.75f),
                        )
                    }
                }
            } else {
                ZineCard(seed = 2) {
                    Text(
                        "No alarms. Everything's live.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = theme.onCard,
                    )
                    Text(
                        "Capture and sockets are under your control.",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onCard.copy(alpha = 0.75f),
                    )
                }
            }

            // ---- Engage / release ----
            if (!halted) {
                ZineButton(
                    text = if (armed) "TAP AGAIN TO CONFIRM" else "ENGAGE KILL SWITCH",
                    onClick = {
                        if (armed) {
                            vm.engage()
                            armed = false
                        } else {
                            armed = true
                        }
                    },
                    style = ZineButtonStyle.DANGER,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                )
            } else {
                ZineButton(
                    text = "RELEASE KILL SWITCH",
                    onClick = { vm.release() },
                    style = ZineButtonStyle.SECONDARY,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                )
                Text(
                    "Releasing does NOT re-enable sockets — off genuinely means off. " +
                        "Re-enable each socket yourself on the Sockets screen; recovery is deliberate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onPaper.copy(alpha = 0.75f),
                )
            }

            Text(
                "Stops screen capture and darks your sockets. In-flight requests finish " +
                    "what they're doing — no NEW requests start while engaged. (No " +
                    "remote-cancel API exists — that's the real limit.)",
                style = MaterialTheme.typography.bodySmall,
                color = theme.onPaper.copy(alpha = 0.75f),
            )

            ZineSectionDivider("CONTROLS")

            // ---- Essential socket picker ----
            ZineCard(seed = 3) {
                Text(
                    "Essential socket",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = theme.onCard,
                )
                Text(
                    "One socket can stay live when the kill switch engages — everything else " +
                        "goes dark. Leave unpinned to disable ALL sockets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onCard.copy(alpha = 0.75f),
                )
                var expanded by remember { mutableStateOf(false) }
                val essentialDef = sockets.firstOrNull { it.id == essentialId }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                ) {
                    OutlinedTextField(
                        value = essentialDef?.let { "${it.displayName} (${it.id})" }
                            ?: "None — disable all",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Pinned as essential") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("None — disable all") },
                            onClick = {
                                vm.setEssentialSocket(null)
                                expanded = false
                            },
                        )
                        for (def in sockets) {
                            DropdownMenuItem(
                                text = { Text("${def.displayName} (${def.id})") },
                                onClick = {
                                    vm.setEssentialSocket(def.id)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }

            // ---- Spend card ----
            ZineCard(seed = 4) {
                Text(
                    "Spend",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = theme.onCard,
                )
                Text(
                    "Spent $${"%.2f".format(spent)} / $${"%.2f".format(cap)} budget",
                    style = MaterialTheme.typography.titleSmall,
                    color = theme.onCard,
                )
                if (overBudget) {
                    // M5: honest copy — the cap is a fixed build constant; there is no
                    // UI to raise it, so the banner must not promise that recovery path.
                    Text(
                        "OVER BUDGET — new sends are held until spend is reset.",
                        color = theme.stamp,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZineButton(
                        "RESET SPEND",
                        onClick = { vm.resetSpend() },
                        style = ZineButtonStyle.SECONDARY,
                    )
                    Spacer(Modifier.width(8.dp))
                    ZineButton(
                        "REFRESH",
                        onClick = { vm.refreshSockets() },
                        style = ZineButtonStyle.SECONDARY,
                    )
                }
            }
        }
    }
}
