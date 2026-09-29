package com.omni.app.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * Studio: the creative tools hub. Small audio models (whisper.cpp STT, Piper TTS)
 * can be downloaded into app-private storage and registered as on-device
 * LOCAL_TOOL sockets; video/image/music generation entries (Runway, Luma,
 * Stability, Replicate, ElevenLabs) are cloud-routed API_KEY sockets — they get
 * no download button and are never labelled on-device.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StudioScreen(vm: StudioViewModel, onBack: () -> Unit, onOpenSockets: () -> Unit) {
    val urlInputs = remember { mutableStateMapOf<String, String>() }
    val theme = LocalZineTheme.current

    ZineScreenScaffold(title = "Studio", onBack = onBack) {
        Text(
            "Open creative tools. Small audio models can run on-device; video generation routes to cloud sockets.",
            style = MaterialTheme.typography.bodyMedium,
            color = theme.onPaper,
        )
        Text(
            "Storage used: ${formatBytes(vm.storageBytes)}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = theme.onPaper,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.onDeviceItems.isNotEmpty()) {
                item { ZineSectionDivider("ON-DEVICE") }
                itemsIndexed(vm.onDeviceItems, key = { _, item -> item.id }) { index, item ->
                    ZineCard(seed = index) {
                        DownloadableStudioCard(
                            item = item,
                            status = vm.statuses[item.id] ?: ToolStatus.NotDownloaded,
                            url = urlInputs[item.id] ?: "",
                            onUrlChange = {
                                urlInputs[item.id] = it
                                vm.setUrlError(item.id, null)
                            },
                            urlError = vm.urlErrors[item.id],
                            onDownload = { vm.startDownload(item.id, urlInputs[item.id] ?: "") },
                            onCancel = { vm.cancelDownload(item.id) },
                            onDelete = { vm.deleteTool(item.id) },
                        )
                    }
                }
            }
            if (vm.cloudItems.isNotEmpty()) {
                item { ZineSectionDivider("CLOUD-ROUTED") }
                itemsIndexed(vm.cloudItems, key = { _, item -> item.id }) { index, item ->
                    ZineCard(seed = 100 + index) {
                        CloudStudioCard(item = item, onOpenSockets = onOpenSockets)
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudioCapChips(item: StudioItem) {
    // N10: capability chips are plain labels, never decorative click targets.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item.capLabels.forEachIndexed { i, cap ->
            ZineBadge(cap, seed = i)
        }
    }
}

@Composable
private fun StudioNotes(item: StudioItem) {
    if (!item.notes.isNullOrBlank()) {
        Text(
            item.notes,
            style = MaterialTheme.typography.bodySmall,
            color = LocalZineTheme.current.onCard.copy(alpha = 0.75f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloudStudioCard(item: StudioItem, onOpenSockets: () -> Unit) {
    val theme = LocalZineTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            item.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = theme.onCard,
        )
        StudioCapChips(item)
        StudioNotes(item)
        Text(
            STUDIO_CLOUD_LABEL,
            style = MaterialTheme.typography.bodySmall,
            color = theme.onCard.copy(alpha = 0.75f),
        )
        ZineButton("OPEN SOCKETS", onClick = onOpenSockets)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadableStudioCard(
    item: StudioItem,
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
            item.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = theme.onCard,
        )
        StudioCapChips(item)
        StudioNotes(item)
        when (status) {
            is ToolStatus.NotDownloaded -> {
                ZineStamp("NOT DOWNLOADED")
                ZineTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = "Tool file URL",
                    placeholder = "https://\u2026/model.bin",
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
                    "No download link is bundled for this tool \u2014 paste the tool file URL yourself.",
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
                    ZineButton("DELETE TOOL", onClick = onDelete, style = ZineButtonStyle.DANGER)
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
