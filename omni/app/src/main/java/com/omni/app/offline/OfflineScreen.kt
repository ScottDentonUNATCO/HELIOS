package com.omni.app.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.app.gamemaker.core.ZineBadge
import com.omni.app.gamemaker.core.ZineButton
import com.omni.app.gamemaker.core.ZineButtonStyle
import com.omni.app.gamemaker.core.ZineCard
import com.omni.app.gamemaker.core.ZineScreenScaffold
import com.omni.app.gamemaker.core.ZineSectionDivider
import com.omni.app.gamemaker.core.ZineStamp
import com.omni.app.gamemaker.core.ZineTextField
import com.omni.app.gamemaker.core.LocalZineTheme

/**
 * Offline models download manager. Lists the LOCAL_TOOL sockets from the shared
 * catalog, lets the user paste a model file URL per tool (no URLs are invented —
 * the catalog carries none), streams the download into app-private storage with
 * progress and cancellation, and registers the downloaded file as a working
 * socket in the app's registry.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OfflineScreen(vm: OfflineViewModel, onBack: () -> Unit) {
    val urlInputs = remember { mutableStateMapOf<String, String>() }
    val theme = LocalZineTheme.current

    ZineScreenScaffold(title = "Offline models", onBack = onBack) {
        Text(
            "Models run from app-private storage. Large models may be slow on-device \u2014 start small.",
            style = MaterialTheme.typography.bodyMedium,
            color = theme.onPaper,
        )
        Text(
            "Storage used: ${formatBytes(vm.storageBytes)}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = theme.onPaper,
        )
        ZineSectionDivider("MODELS")
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(vm.tools, key = { _, tool -> tool.id }) { index, tool ->
                ZineCard(seed = index) {
                    ToolCard(
                        tool = tool,
                        status = vm.statuses[tool.id] ?: ToolStatus.NotDownloaded,
                        url = urlInputs[tool.id] ?: "",
                        onUrlChange = {
                            urlInputs[tool.id] = it
                            vm.setUrlError(tool.id, null)
                        },
                        urlError = vm.urlErrors[tool.id],
                        onDownload = { vm.startDownload(tool.id, urlInputs[tool.id] ?: "") },
                        onCancel = { vm.cancelDownload(tool.id) },
                        onDelete = { vm.deleteModel(tool.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolCard(
    tool: OfflineTool,
    status: ToolStatus,
    url: String,
    onUrlChange: (String) -> Unit,
    urlError: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val theme = LocalZineTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            tool.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = theme.onCard,
        )
        // N10: capability chips are plain labels, never decorative click targets.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tool.capLabels.forEachIndexed { i, cap ->
                ZineBadge(cap, seed = i)
            }
        }
        if (!tool.notes.isNullOrBlank()) {
            Text(
                tool.notes,
                style = MaterialTheme.typography.bodySmall,
                color = theme.onCard.copy(alpha = 0.75f),
            )
        }
        when (status) {
            is ToolStatus.NotDownloaded -> {
                ZineStamp("NOT DOWNLOADED")
                ZineTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = "Model file URL",
                    placeholder = "https://\u2026/model.gguf",
                    modifier = Modifier.fillMaxWidth(),
                )
                urlError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.stamp,
                    )
                }
                ZineButton("DOWNLOAD", onClick = onDownload, enabled = url.isNotBlank())
                Text(
                    "No download link is bundled for this tool \u2014 paste the model file URL yourself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onCard.copy(alpha = 0.75f),
                )
            }
            is ToolStatus.Downloading -> {
                val pct = if (status.totalBytes > 0) {
                    (status.bytesRead * 100 / status.totalBytes).coerceIn(0, 100)
                } else null
                Text(
                    if (pct != null) "Status: DOWNLOADING \u2014 $pct%"
                    else "Status: DOWNLOADING \u2014 ${formatBytes(status.bytesRead)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = theme.onCard,
                )
                if (pct != null) {
                    LinearProgressIndicator(
                        progress = pct / 100f,
                        modifier = Modifier.fillMaxWidth(),
                        color = theme.accent1,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = theme.accent1,
                    )
                }
                Text(
                    "${formatBytes(status.bytesRead)}" +
                        (if (status.totalBytes > 0) " of ${formatBytes(status.totalBytes)}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onCard.copy(alpha = 0.75f),
                )
                ZineButton("CANCEL", onClick = onCancel, style = ZineButtonStyle.SECONDARY)
            }
            is ToolStatus.Ready -> {
                ZineStamp("READY", color = theme.accent1)
                Text(
                    "${formatBytes(status.bytes)} on device",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onCard,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZineButton("DELETE MODEL", onClick = onDelete, style = ZineButtonStyle.DANGER)
                }
                Text(
                    "Frees storage and unregisters the socket.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onCard.copy(alpha = 0.75f),
                )
            }
            is ToolStatus.Failed -> {
                ZineStamp("FAILED")
                Text(
                    status.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.stamp,
                )
                ZineButton("RETRY", onClick = onDownload, style = ZineButtonStyle.SECONDARY)
            }
        }
    }
}
