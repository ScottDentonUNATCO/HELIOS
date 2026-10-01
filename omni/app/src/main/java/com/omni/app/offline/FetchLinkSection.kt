package com.omni.app.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.app.gamemaker.core.LocalZineTheme
import com.omni.app.gamemaker.core.ZineButton
import com.omni.app.gamemaker.core.ZineButtonStyle
import com.omni.app.gamemaker.core.ZineCard
import com.omni.app.gamemaker.core.ZineStamp
import com.omni.app.gamemaker.core.ZineTextField
import com.omni.gateway.FetchLinkKind
import com.omni.gateway.LinkInfo

/**
 * FetchLink MVP section (lives inside the Offline tab — no new nav needed).
 *
 * Flow: paste link -> INSPECT -> the card shows kind, license (code AND
 * weights), size, and requirements BEFORE anything downloads -> the user
 * acknowledges the license -> DOWNLOAD (resumable, progress, publisher
 * checksum when one exists) -> the file is registered as a LOCAL_TOOL socket.
 */
@Composable
fun FetchLinkSection(vm: FetchLinkViewModel) {
    val theme = LocalZineTheme.current
    ZineCard(seed = 700) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "FetchLink — paste a link, get a socket",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = theme.onCard,
            )
            Text(
                "GitHub repo, Hugging Face model, or direct file. License, size, and " +
                    "requirements are shown BEFORE anything downloads. Credentialed " +
                    "URLs are rejected; links on pages are never auto-followed.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.onCard.copy(alpha = 0.75f),
            )
            ZineTextField(
                value = vm.linkInput,
                onValueChange = vm::updateLinkInput,
                label = "Link",
                placeholder = "https://github.com/…  or  https://huggingface.co/…",
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZineButton(
                    "INSPECT",
                    onClick = vm::inspectLink,
                    enabled = vm.phase != FetchPhase.INSPECTING && vm.phase != FetchPhase.DOWNLOADING,
                )
                if (vm.phase == FetchPhase.INSPECTED || vm.phase == FetchPhase.FAILED || vm.phase == FetchPhase.DONE) {
                    ZineButton("RESET", onClick = vm::reset, style = ZineButtonStyle.SECONDARY)
                }
            }
            when (vm.phase) {
                FetchPhase.IDLE -> Unit
                FetchPhase.INSPECTING -> {
                    Text("Inspecting link…", style = MaterialTheme.typography.bodyMedium, color = theme.onCard)
                }
                FetchPhase.INSPECTED -> vm.info?.let { InspectCard(it, vm) }
                FetchPhase.DOWNLOADING -> {
                    val pct = if (vm.progressTotal > 0) {
                        (vm.progressBytes * 100 / vm.progressTotal).coerceIn(0, 100)
                    } else null
                    Text(
                        if (pct != null) "DOWNLOADING — $pct% (resumable)"
                        else "DOWNLOADING — ${formatBytes(vm.progressBytes)} (resumable)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = theme.onCard,
                    )
                    LinearProgressIndicator(
                        progress = if (pct != null) pct / 100f else 0f,
                        modifier = Modifier.fillMaxWidth(),
                        color = theme.accent1,
                    )
                    Text(
                        "${formatBytes(vm.progressBytes)}" +
                            (if (vm.progressTotal > 0) " of ${formatBytes(vm.progressTotal)}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onCard.copy(alpha = 0.75f),
                    )
                    ZineButton("CANCEL", onClick = vm::cancelDownload, style = ZineButtonStyle.SECONDARY)
                }
                FetchPhase.DONE -> {
                    ZineStamp("FETCHED", color = theme.accent1)
                    Text(
                        "Saved: ${vm.resultPath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onCard,
                    )
                    Text(
                        "Registered as LOCAL_TOOL socket: ${vm.resultSocketId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onCard,
                    )
                    Text(
                        when (vm.checksumStatus) {
                            "VERIFIED" -> "Checksum: VERIFIED against the publisher's sha256."
                            else -> "Checksum: no checksum published — file kept as downloaded."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onCard.copy(alpha = 0.75f),
                    )
                }
                FetchPhase.FAILED -> {
                    ZineStamp("FAILED")
                    vm.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = theme.stamp)
                    }
                }
            }
            if (vm.phase != FetchPhase.FAILED) {
                vm.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = theme.stamp)
                }
            }
        }
    }
}

@Composable
private fun InspectCard(info: LinkInfo, vm: FetchLinkViewModel) {
    val theme = LocalZineTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZineStamp(
                when (info.kind) {
                    FetchLinkKind.GITHUB_REPO -> "GITHUB REPO"
                    FetchLinkKind.HUGGINGFACE_MODEL -> "HF MODEL"
                    FetchLinkKind.DIRECT_FILE -> "DIRECT FILE"
                    FetchLinkKind.DOCS_PAGE -> "DOCS PAGE"
                    FetchLinkKind.UNKNOWN -> "UNKNOWN"
                },
            )
        }
        Text(
            info.displayName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = theme.onCard,
        )
        LicenseLine("Code license", info.codeLicense)
        LicenseLine("Weights license", info.weightsLicense)
        Text(
            "Size: ${info.sizeBytes?.let { formatBytes(it) } ?: "unknown"}",
            style = MaterialTheme.typography.bodySmall,
            color = theme.onCard,
        )
        info.requirements?.let {
            Text("Requirements: $it", style = MaterialTheme.typography.bodySmall, color = theme.onCard)
        }
        Text(
            info.notes,
            style = MaterialTheme.typography.bodySmall,
            color = theme.onCard.copy(alpha = 0.8f),
        )
        if (info.downloadUrl != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = vm.licenseAcknowledged,
                    onCheckedChange = vm::acknowledgeLicense,
                )
                Text(
                    "I have read the license terms above and accept them for my use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onCard,
                )
            }
            ZineButton(
                "DOWNLOAD",
                onClick = vm::confirmAndDownload,
                enabled = vm.licenseAcknowledged,
            )
        } else {
            Text(
                "Nothing downloadable here — paste the actual file or repo link.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.stamp,
            )
        }
    }
}

@Composable
private fun LicenseLine(label: String, value: String?) {
    val theme = LocalZineTheme.current
    Text(
        "$label: ${value ?: "UNKNOWN — verify before use"}",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (value == null) FontWeight.Bold else FontWeight.Normal,
        color = theme.onCard,
    )
}
