package com.omni.app.hub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.app.gamemaker.core.ZineBadge
import com.omni.app.gamemaker.core.ZineButton
import com.omni.app.gamemaker.core.ZineButtonStyle
import com.omni.app.gamemaker.core.ZineCard
import com.omni.app.gamemaker.core.ZineRansomTitle
import com.omni.app.gamemaker.core.ZineScreenBackdrop
import com.omni.app.gamemaker.core.ZineSectionDivider
import com.omni.app.gamemaker.core.ZineTextField
import com.omni.app.gamemaker.core.LocalZineTheme
import com.omni.app.hub.HubViewModel.HubTask
import com.omni.app.sockets.SocketStore
import com.omni.gateway.Caps

/**
 * TRACK D — neutral hub task board UI.
 *
 * Honest framing: this is a local task queue, not a cloud agent fleet —
 * every task runs on-device through an enabled socket carrying CHAT.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(viewModel: HubViewModel, socketStore: SocketStore, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val theme = LocalZineTheme.current

    var title by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var selectedSocketId by remember { mutableStateOf<String?>(null) }
    var exportJson by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }

    // Enabled sockets offering CHAT — off genuinely means off.
    val chatSockets = remember(socketStore) {
        socketStore.registry.withCapability(Caps.CHAT)
    }

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
                ZineRansomTitle("Task hub")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Local task queue \u2014 tasks run on-device through enabled sockets. No cloud agent fleet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.onPaper,
                )
            }

            // ---- new task form ----
            item {
                ZineCard(seed = 3) {
                    Text(
                        "New task",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        color = theme.onCard,
                    )

                    ZineTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = "Title",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ZineTextField(
                        value = instructions,
                        onValueChange = { instructions = it },
                        label = "Instructions",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded },
                    ) {
                        val selected = chatSockets.firstOrNull { it.id == selectedSocketId }
                        OutlinedTextField(
                            value = selected?.displayName ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Socket") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            if (chatSockets.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No enabled CHAT sockets") },
                                    onClick = { expanded = false },
                                )
                            } else {
                                chatSockets.forEach { def ->
                                    DropdownMenuItem(
                                        text = { Text(def.displayName) },
                                        onClick = {
                                            selectedSocketId = def.id
                                            expanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    formError?.let {
                        Text(it, color = theme.stamp)
                    }

                    ZineButton(
                        text = "QUEUE TASK",
                        onClick = {
                            formError = null
                            val sid = selectedSocketId
                            when {
                                title.isBlank() -> formError = "Give the task a title."
                                instructions.isBlank() -> formError = "Tell it what to do."
                                sid == null -> formError = "Pick a socket to run it on."
                                else -> runCatching {
                                    viewModel.postTask(title, instructions, sid)
                                }.onSuccess {
                                    title = ""
                                    instructions = ""
                                }.onFailure {
                                    formError = it.message ?: "Could not queue task."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ---- import ----
            item {
                ZineCard(seed = 5) {
                    Text(
                        "Import handoff",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        color = theme.onCard,
                    )
                    ZineTextField(
                        value = importText,
                        onValueChange = {
                            importText = it
                            importError = null
                        },
                        label = "Drop the handoff JSON here.",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    importError?.let {
                        Text(it, color = theme.stamp)
                    }
                    ZineButton(
                        text = "IMPORT",
                        onClick = {
                            importError = null
                            runCatching { viewModel.importTask(importText) }
                                .onSuccess { importText = "" }
                                .onFailure { importError = it.message ?: "That JSON won't parse — double-check it." }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = importText.isNotBlank(),
                    )
                }
            }

            // ---- history ----
            item {
                ZineSectionDivider("HISTORY (${viewModel.tasks.size})")
            }

            itemsIndexed(viewModel.tasks, key = { _, task -> task.id }) { index, task ->
                ZineCard(seed = 10 + index) {
                    TaskCard(
                        task = task,
                        onRun = { viewModel.runTask(task.id) },
                        onExport = { exportJson = viewModel.exportTask(task.id) },
                    )
                }
            }
        }
    }

    exportJson?.let { json ->
        AlertDialog(
            onDismissRequest = { exportJson = null },
            containerColor = theme.card,
            titleContentColor = theme.onCard,
            textContentColor = theme.onCard,
            title = { Text("Handoff JSON", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = json,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                ZineButton(
                    text = "COPY TO CLIPBOARD",
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("handoff", json)
                        )
                        exportJson = null
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { exportJson = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun TaskCard(
    task: HubTask,
    onRun: () -> Unit,
    onExport: () -> Unit,
) {
    val theme = LocalZineTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                task.title.ifBlank { "(untitled)" },
                fontWeight = FontWeight.SemiBold,
                color = theme.onCard,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(task.status)
        }
        Text(
            "socket: ${task.socketId}",
            style = MaterialTheme.typography.bodySmall,
            color = theme.onCard.copy(alpha = 0.7f),
        )
        if (task.result.isNotBlank()) {
            Text(
                text = task.result,
                style = MaterialTheme.typography.bodySmall,
                color = theme.onCard,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZineButton(
                text = if (task.status == HubViewModel.STATUS_RUNNING) "RUNNING\u2026" else "RUN",
                onClick = onRun,
                enabled = task.status != HubViewModel.STATUS_RUNNING,
            )
            ZineButton(
                text = "EXPORT",
                onClick = onExport,
                style = ZineButtonStyle.SECONDARY,
            )
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val label = when (status) {
        HubViewModel.STATUS_COMPLETED -> "DONE"
        HubViewModel.STATUS_FAILED -> "BUSTED"
        HubViewModel.STATUS_RUNNING -> "RUNNING"
        else -> status.uppercase()
    }
    ZineBadge(label, seed = status.hashCode())
}
