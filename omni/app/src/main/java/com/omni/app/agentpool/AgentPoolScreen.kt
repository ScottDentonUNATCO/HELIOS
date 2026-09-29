package com.omni.app.agentpool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.omni.app.gamemaker.core.ZineButton
import com.omni.app.gamemaker.core.ZineButtonStyle
import com.omni.app.gamemaker.core.ZineCard
import com.omni.app.gamemaker.core.ZineScreenScaffold
import com.omni.app.gamemaker.core.ZineSectionDivider
import com.omni.app.gamemaker.core.LocalZineTheme

/**
 * TRACK G — agent pool controls.
 *
 * RAM card, per-model MB field, agent stepper (1..8), budget line, Start/Stop,
 * live stats, and the honest one-line description of what the pool is.
 */
@Composable
fun AgentPoolScreen(vm: AgentPoolViewModel, onBack: () -> Unit) {
    var modelText by remember(vm.modelMb) { mutableStateOf(vm.modelMb.toString()) }
    val blocked = if (vm.running) null else vm.blockingReason()
    val theme = LocalZineTheme.current
    // N12: invalid input is an error state, not silently ignored text.
    val modelInvalid = modelText.toIntOrNull()?.let { it <= 0 } ?: modelText.isNotBlank()

    ZineScreenScaffold(title = "Agent pool", onBack = onBack) {
        // N11: scroll so the RAM card + field + stepper + buttons + stats
        // never overflow on small screens.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ZineCard(seed = 3) {
                Text(
                    "Device RAM",
                    style = MaterialTheme.typography.titleSmall,
                    color = theme.onCard,
                )
                Text(
                    "Available: ${vm.availMb} MB",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = theme.onCard,
                )
                Text(
                    "Headroom after pool: ${vm.headroomMb} MB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.onCard,
                )
            }

            OutlinedTextField(
                value = modelText,
                onValueChange = {
                    modelText = it
                    it.toIntOrNull()?.let { mb -> if (mb > 0) vm.updateModelMb(mb) }
                },
                label = { Text("Per-model footprint (MB)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !vm.running,
                isError = modelInvalid,
                supportingText = {
                    if (modelInvalid) Text("Enter a positive number.")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Agents", style = MaterialTheme.typography.titleSmall, color = theme.onPaper)
                Spacer(Modifier.width(12.dp))
                ZineButton(
                    text = "\u2212",
                    onClick = vm::decAgents,
                    enabled = !vm.running && vm.agentCount > 1,
                    style = ZineButtonStyle.SECONDARY,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${vm.agentCount}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = theme.onPaper,
                )
                Spacer(Modifier.width(12.dp))
                ZineButton(
                    text = "+",
                    onClick = vm::incAgents,
                    enabled = !vm.running && vm.agentCount < 8,
                    style = ZineButtonStyle.SECONDARY,
                )
            }

            Text(
                "${vm.agentCount} agents \u00d7 ${vm.modelMb}MB = ${vm.totalMb()}MB / cap ${vm.capMb()}MB",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.onPaper,
            )

            if (blocked != null) {
                Text(blocked, color = theme.stamp)
            }

            Row {
                ZineButton(
                    text = "START",
                    onClick = vm::start,
                    enabled = !vm.running && blocked == null,
                )
                Spacer(Modifier.width(8.dp))
                ZineButton(
                    text = "STOP",
                    onClick = { vm.stop() },
                    enabled = vm.running,
                    style = ZineButtonStyle.DANGER,
                )
            }

            ZineSectionDivider("LIVE")

            Text(
                "Agents: ${vm.agentCount} \u00b7 Completed: ${vm.completedTotal} \u00b7 " +
                    "Headroom: ${vm.headroomMb} MB",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.onPaper,
            )
            Text("Status: ${vm.status}", style = MaterialTheme.typography.bodySmall, color = theme.onPaper)

            Text(
                "Local task runners sharing one hub queue and one memory store " +
                    "\u2014 not separate processes. They pause while the kill switch is engaged.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.onPaper.copy(alpha = 0.75f),
            )
        }
    }
}
