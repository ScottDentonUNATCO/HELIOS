package com.omni.app.memoryui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.app.gamemaker.core.ZineBadge
import com.omni.app.gamemaker.core.ZineButton
import com.omni.app.gamemaker.core.ZineButtonStyle
import com.omni.app.gamemaker.core.ZineCard
import com.omni.app.gamemaker.core.ZineEmptyState
import com.omni.app.gamemaker.core.ZineScreenScaffold
import com.omni.app.gamemaker.core.ZineTextField
import com.omni.app.gamemaker.core.LocalZineTheme
import com.omni.memory.MemoryRecord
import com.omni.memory.MemoryScope
import java.text.DateFormat
import java.util.Date

/**
 * Anything that still looks like a raw secret at display time (long token
 * runs; put() normally scrubs these first) is shown masked to its last 4
 * characters.
 */
private val TOKEN_RUN = Regex("[A-Za-z0-9_\\-+=/]{16,}")

internal fun maskSecretsForDisplay(text: String): String =
    TOKEN_RUN.replace(text) { m ->
        val v = m.value
        "\u2022\u2022\u2022\u2022${v.takeLast(4)}"
    }

private fun formatTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

private val ALL_SCOPES: List<MemoryScope> = MemoryScope.values().toList()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryScreen(vm: MemoryViewModel, onBack: () -> Unit) {
    val theme = LocalZineTheme.current
    // N7: the record awaiting delete confirmation (null = no dialog).
    var pendingDelete by remember { mutableStateOf<MemoryRecord?>(null) }

    ZineScreenScaffold(title = "Memory", onBack = onBack) {
        ZineTextField(
            value = vm.searchText,
            onValueChange = { vm.searchText = it },
            label = "Search memories",
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ScopeChip(
                selected = vm.scopeFilter == null,
                label = "All",
                onClick = { vm.scopeFilter = null },
            )
            for (scope in ALL_SCOPES) {
                ScopeChip(
                    selected = vm.scopeFilter == scope,
                    label = scope.name,
                    onClick = {
                        vm.scopeFilter = if (vm.scopeFilter == scope) null else scope
                    },
                )
            }
        }
        // N8: the compression preview used to run a full MemoryCompressor
        // pass on EVERY recomposition — now memoized on the record list.
        val preview = remember(vm.records) { vm.compressionPreview() }
        Text(
            buildString {
                append("${vm.records.size} records")
                for (scope in ALL_SCOPES) {
                    append(" · ${scope.name}: ${vm.countByScope(scope)}")
                }
                append("\nCompressor pass (2000-token budget): keeps ${preview.kept}, " +
                    "folds ${preview.summaries} scope summaries, drops ${preview.dropped} — " +
                    "nothing is ever deleted")
            },
            style = MaterialTheme.typography.labelSmall,
            color = theme.onPaper.copy(alpha = 0.75f),
        )
        if (vm.loading) {
            Text(
                "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.onPaper,
            )
        } else {
            val shown = vm.filteredRecords()
            if (shown.isEmpty()) {
                ZineEmptyState("No memories here yet. Chat turns are saved automatically.")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(shown, key = { _, record -> record.id }) { index, record ->
                        RecordCard(
                            record = record,
                            vm = vm,
                            seed = index,
                            onDeleteRequest = { pendingDelete = it },
                        )
                    }
                }
            }
        }
    }

    // N7: one-tap Delete gets a confirmation on a screen whose whole
    // philosophy is "nothing is ever deleted".
    pendingDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this memory?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                ZineButton(
                    text = "DELETE",
                    onClick = { vm.delete(rec); pendingDelete = null },
                    style = ZineButtonStyle.DANGER,
                )
            },
            dismissButton = {
                ZineButton(
                    text = "KEEP",
                    onClick = { pendingDelete = null },
                    style = ZineButtonStyle.SECONDARY,
                )
            },
        )
    }
}

/** Cut-out scope chip: stamped ON when selected, hollow ink outline when not. */
@Composable
private fun ScopeChip(selected: Boolean, label: String, onClick: () -> Unit) {
    val theme = LocalZineTheme.current
    Box(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .background(if (selected) theme.accent1 else Color.Transparent)
            .border(2.dp, theme.ink)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            color = if (selected) theme.onAccent else theme.ink,
        )
    }
}

@Composable
private fun RecordCard(
    record: MemoryRecord,
    vm: MemoryViewModel,
    seed: Int,
    onDeleteRequest: (MemoryRecord) -> Unit,
) {
    val theme = LocalZineTheme.current
    ZineCard(seed = seed) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZineBadge(record.scope.name, seed = seed)
            if (record.pinned) {
                ZineBadge("PINNED", seed = seed + 1)
            }
            Spacer(Modifier.weight(1f))
            Text(
                formatTime(record.updatedAtMs),
                style = MaterialTheme.typography.labelSmall,
                color = theme.onCard.copy(alpha = 0.7f),
            )
        }
        Text(
            record.key,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = theme.onCard,
        )
        Text(
            maskSecretsForDisplay(record.value),
            style = MaterialTheme.typography.bodyMedium,
            color = theme.onCard,
        )
        Text(
            "from ${record.provenance} · v${record.version}",
            style = MaterialTheme.typography.labelSmall,
            color = theme.onCard.copy(alpha = 0.7f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            ZineButton(
                text = if (record.pinned) "UNPIN" else "PIN",
                onClick = { vm.togglePin(record) },
                style = ZineButtonStyle.SECONDARY,
            )
            Spacer(Modifier.width(8.dp))
            ZineButton(
                text = "DELETE",
                onClick = { onDeleteRequest(record) },
                style = ZineButtonStyle.DANGER,
            )
        }
    }
}
