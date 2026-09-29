package com.omni.app.gamemaker.nes

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omni.app.gamemaker.core.ZineTape
import com.omni.app.gamemaker.core.ZineTitle
import omni.nes.validate.Finding
import omni.nes.validate.Severity

/**
 * The NES game-maker screen. Wired by another track (this file only declares
 * the composable + its ViewModel; MainActivity is untouched).
 *
 * Flow: brief -> pick a socket -> draft via LLM -> validator-gated
 * repair loop -> real .nes ROM -> PPU-rendered screenshots -> export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NesMakerScreen(vm: NesMakerViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var socketMenuOpen by remember { mutableStateOf(false) }
    // N16: OAuth sockets (no base URL) and any socket without a base URL can
    // never draft — SocketLlmDraft throws "has no base URL" for all of them.
    // Show them disabled with an honest suffix instead of letting the user
    // pick one and fail into the fallback.
    val socketDefs = vm.sockets()
    val (usableSockets, unusableSockets) = remember(socketDefs) {
        socketDefs.partition { !it.baseUrl.isNullOrBlank() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ZineTitle("NES Game Maker") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- brief ----
            item {
                ZineTape("MISSION BRIEF")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = vm.brief,
                    onValueChange = { vm.brief = it },
                    label = { Text("Describe your game") },
                    placeholder = { Text("e.g. a bouncing-ball score attack") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    enabled = !vm.running,
                )
            }

            // ---- draft source ----
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Who's dreaming it up?", fontWeight = FontWeight.Bold)
                        Box {
                            OutlinedButton(
                                onClick = { socketMenuOpen = true },
                                enabled = !vm.running,
                            ) {
                                Text(
                                    vm.socketId?.let { id ->
                                        vm.sockets().find { it.id == id }?.displayName ?: id
                                    } ?: "Pick a socket…",
                                )
                            }
                            DropdownMenu(
                                expanded = socketMenuOpen,
                                onDismissRequest = { socketMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("None (template only)") },
                                    onClick = {
                                        vm.socketId = null
                                        socketMenuOpen = false
                                    },
                                )
                                for (s in usableSockets) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${s.displayName}" +
                                                    if (!s.enabled) " (OFF)" else "",
                                            )
                                        },
                                        onClick = {
                                            vm.socketId = s.id
                                            socketMenuOpen = false
                                        },
                                    )
                                }
                                for (s in unusableSockets) {
                                    DropdownMenuItem(
                                        text = {
                                            Text("${s.displayName} (no base URL — can't draft)")
                                        },
                                        enabled = false,
                                        onClick = { socketMenuOpen = false },
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = vm.useTemplateFallback,
                                onCheckedChange = { vm.useTemplateFallback = it },
                                enabled = !vm.running,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Plan B: offline template (deterministic, " +
                                    "validator-proven) if the socket draft dies",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        vm.draftSourceLabel?.let {
                            Text("Drafting via: $it", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // ---- run controls ----
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = vm::start,
                        enabled = !vm.running,
                    ) { Text("MAKE THE GAME") }
                    if (vm.running) {
                        OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                }
            }

            vm.notice?.let { n ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(n, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---- progress timeline ----
            if (vm.stages.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Progress", fontWeight = FontWeight.Bold)
                            for (stage in MakerStage.entries) {
                                val (state, detail) = vm.stages[stage]
                                    ?: (NesMakerViewModel.StageState.PENDING to null)
                                StageRow(stage, state, detail)
                            }
                        }
                    }
                }
            }

            // ---- screenshots ----
            if (vm.frames.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Rendered frames", fontWeight = FontWeight.Bold)
                            Text(
                                "Captured from the real 2C02 PPU core running your ROM's actual CPU — " +
                                    "not mockups.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(vm.frames.withIndex().toList()) { (i, bmp) ->
                                    Column {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = "PPU frame $i",
                                            modifier = Modifier
                                                .width(160.dp)
                                                .aspectRatio(256f / 240f),
                                        )
                                        Text(
                                            "frame $i",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- validator report ----
            if (vm.attempts.isNotEmpty() || vm.findings.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Validator report", fontWeight = FontWeight.Bold)
                            for (a in vm.attempts) {
                                val verdict = when {
                                    a.passed -> "GREEN"
                                    a.buildError != null -> "BUILD FAIL"
                                    else -> "RED"
                                }
                                Text(
                                    "Attempt ${a.attempt}: $verdict" +
                                        (a.firedRules.takeIf { it.isNotEmpty() }
                                            ?.let { " — ${it.joinToString(", ")}" } ?: "") +
                                        (a.buildError?.let { " — $it" } ?: "") +
                                        (a.repairAction?.let { "\n  via: $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (vm.findings.isNotEmpty()) {
                                HorizontalDivider()
                                Text("Findings:", fontWeight = FontWeight.Bold)
                                for (f in vm.findings) {
                                    FindingRow(f)
                                }
                            }
                        }
                    }
                }
            }

            // ---- failure ----
            vm.failedReason?.let { reason ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("THE RUN BLEW UP", fontWeight = FontWeight.Bold)
                            Text(reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // ---- export ----
            if (vm.result != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("GAME BAKED", fontWeight = FontWeight.Bold)
                            Text(
                                "${vm.result!!.rom.size} bytes, iNES NROM-128. " +
                                    "${vm.result!!.frames.size} frames rendered " +
                                    "(${vm.result!!.cpuCycles} CPU cycles)." +
                                    (vm.result!!.renderNote?.let { " Note: $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = { vm.export(context) }) { Text("Export ROM") }
                            vm.exportPath?.let {
                                Text(
                                    "Saved to app-private storage:\n$it\n" +
                                        "(no FileProvider in the manifest yet, so it isn't " +
                                        "shareable to other apps — pull it via USB file transfer)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            // ---- mandatory honest copy ----
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        HONEST_COPY,
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private const val HONEST_COPY =
    "Interactive on-device emulation is future work. What this proves is genuine: " +
        "valid ROMs with real rendered frames from the hardware-truth PPU."

@Composable
private fun StageRow(
    stage: MakerStage,
    state: NesMakerViewModel.StageState,
    detail: String?,
) {
    val label = when (stage) {
        MakerStage.DRAFTING -> "Drafting"
        MakerStage.ASSEMBLING -> "Assembling"
        MakerStage.VALIDATING -> "Validating"
        MakerStage.REPAIRING -> "Repairing"
        MakerStage.RENDERING -> "Rendering"
    }
    val marker = when (state) {
        NesMakerViewModel.StageState.PENDING -> "○"
        NesMakerViewModel.StageState.ACTIVE -> "●"
        NesMakerViewModel.StageState.DONE -> "✓"
        NesMakerViewModel.StageState.FAILED -> "✗"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state == NesMakerViewModel.StageState.ACTIVE) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Text(marker, Modifier.padding(end = 8.dp), fontWeight = FontWeight.Bold)
        }
        Column {
            Text(label, fontWeight = FontWeight.Bold)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun FindingRow(f: Finding) {
    val badge = when (f.severity) {
        Severity.FAIL -> "FAIL"
        Severity.WARN -> "WARN"
        Severity.INFO -> "INFO"
    }
    Text(
        "[$badge] ${f.ruleId}: ${f.message}",
        style = MaterialTheme.typography.bodySmall,
    )
}
