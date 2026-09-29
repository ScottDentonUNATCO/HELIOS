package com.omni.app.selfimprove

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.unit.dp
import com.omni.app.gamemaker.core.ZineButton
import com.omni.app.gamemaker.core.ZineButtonStyle
import com.omni.app.gamemaker.core.ZineCard
import com.omni.app.gamemaker.core.ZineScreenScaffold
import com.omni.app.gamemaker.core.ZineSectionDivider
import com.omni.app.gamemaker.core.ZineStamp
import com.omni.app.gamemaker.core.ZineTextField
import com.omni.app.gamemaker.core.LocalZineTheme

/**
 * TRACK F — Self-improvement screen. Draft-and-falsify cycle for procedures,
 * run on-device through hub tasks and persisted to PROCEDURE memory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfImproveScreen(vm: SelfImproveViewModel, onBack: () -> Unit) {
    val sockets = remember(vm) { vm.chatSockets() }
    var expanded by remember { mutableStateOf(false) }
    var selectedId by remember(sockets) {
        mutableStateOf(sockets.firstOrNull()?.id ?: "")
    }
    val selectedName = sockets.firstOrNull { it.id == selectedId }?.displayName
        ?: if (sockets.isEmpty()) "No enabled CHAT sockets" else ""
    val theme = LocalZineTheme.current

    ZineScreenScaffold(title = "Self-improvement", onBack = onBack) {
        Text(
            "This improves behaviors and procedures on-device. It cannot rewrite or re-sign " +
                "its own APK — Android forbids it. Code-level changes ship in the next build.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.onPaper,
        )

        ZineTextField(
            value = vm.goalText,
            onValueChange = { vm.onGoalChange(it) },
            label = "Goal",
            placeholder = "e.g. make router prefer cheap sockets for drafts",
            modifier = Modifier.fillMaxWidth(),
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (sockets.isNotEmpty()) expanded = it },
        ) {
            OutlinedTextField(
                value = selectedName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Producer socket") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                enabled = sockets.isNotEmpty(),
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                sockets.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.displayName) },
                        onClick = {
                            selectedId = s.id
                            expanded = false
                        },
                    )
                }
            }
        }

        ZineButton(
            text = "RUN DRAFT-AND-FALSIFY CYCLE",
            onClick = { vm.draftCycle(vm.goalText, selectedId) },
            enabled = sockets.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(vm.status, style = MaterialTheme.typography.bodySmall, color = theme.onPaper)

        // M3: export feedback lives ABOVE the list (the old layout buried it
        // below a fillMaxSize() LazyColumn where it got zero space).
        vm.exportInfo?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = theme.onPaper,
            )
        }

        ZineSectionDivider("PROCEDURES")

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(vm.procedures, key = { _, record -> record.id }) { index, record ->
                ZineCard(seed = index) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            record.key,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = theme.onCard,
                            modifier = Modifier.weight(1f),
                        )
                        ZineStamp(if (record.pinned) "VALIDATED" else "FALSIFIED")
                    }
                    // N20: long procedure values expand on tap instead of
                    // silently clipping at 4 lines.
                    var valueExpanded by remember(record.id) { mutableStateOf(false) }
                    Text(
                        record.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onCard,
                        maxLines = if (valueExpanded) Int.MAX_VALUE else 4,
                        modifier = Modifier.clickable { valueExpanded = !valueExpanded },
                    )
                    if (record.value.length > 220) {
                        Text(
                            if (valueExpanded) "show less" else "tap to expand",
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.onCard.copy(alpha = 0.6f),
                            modifier = Modifier.clickable { valueExpanded = !valueExpanded },
                        )
                    }
                    if (record.pinned) {
                        Spacer(Modifier.height(4.dp))
                        ZineButton(
                            text = "EXPORT",
                            onClick = {
                                runCatching { vm.exportProcedure(record.id) }
                            },
                            style = ZineButtonStyle.SECONDARY,
                        )
                    }
                }
            }
        }
    }
}
