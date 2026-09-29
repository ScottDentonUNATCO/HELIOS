package com.omni.app.sockets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.omni.app.gamemaker.core.ZineBadge
import com.omni.app.gamemaker.core.ZineButton
import com.omni.app.gamemaker.core.ZineButtonStyle
import com.omni.app.gamemaker.core.ZineCard
import com.omni.app.gamemaker.core.ZineScreenScaffold
import com.omni.app.gamemaker.core.ZineSectionDivider
import com.omni.app.gamemaker.core.ZineTextField
import com.omni.app.gamemaker.core.LocalZineTheme
import com.omni.app.gamemaker.core.ZineToggle
import com.omni.gateway.Caps
import com.omni.gateway.SocketDef
import com.omni.gateway.SocketKind

private val CAP_LABELS: List<Pair<Int, String>> = listOf(
    Caps.CHAT to "chat",
    Caps.VISION to "vision",
    Caps.IMAGE_GEN to "image",
    Caps.VIDEO_GEN to "video",
    Caps.MUSIC_GEN to "music",
    Caps.TTS to "TTS",
    Caps.STT to "STT",
    Caps.CODE to "code",
    Caps.DEVICE to "device",
)

private fun kindBadge(kind: SocketKind): String = when (kind) {
    SocketKind.API_KEY -> "API KEY"
    SocketKind.OAUTH -> "OAUTH"
    SocketKind.LOCAL_TOOL -> "LOCAL TOOL"
    SocketKind.CUSTOM -> "CUSTOM"
}

@Composable
fun SocketBoardScreen(
    vm: SocketBoardViewModel,
    onBack: () -> Unit,
    onOpenOffline: () -> Unit = {},
) {
    val theme = LocalZineTheme.current
    ZineScreenScaffold(title = "Socket board", onBack = onBack) {
        vm.notice?.let { note ->
            ZineCard(seed = 900) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.onCard,
                )
                ZineButton(
                    text = "DISMISS",
                    onClick = vm::clearNotice,
                    style = ZineButtonStyle.SECONDARY,
                )
            }
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(vm.sockets, key = { _, def -> def.id }) { index, def ->
                SocketCard(vm = vm, def = def, seed = index, onOpenOffline = onOpenOffline)
            }

            item(key = "add-custom") {
                AddCustomCard(vm = vm)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SocketCard(
    vm: SocketBoardViewModel,
    def: SocketDef,
    seed: Int,
    onOpenOffline: () -> Unit,
) {
    val theme = LocalZineTheme.current
    ZineCard(seed = seed) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                def.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = theme.onCard,
                modifier = Modifier.weight(1f),
            )
            ZineBadge(kindBadge(def.kind), seed = seed)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((bit, label) in CAP_LABELS) {
                if (def.capabilities and bit != 0) {
                    ZineBadge(label, seed = seed + label.hashCode())
                }
            }
        }

        def.notes?.let { notes ->
            Text(
                notes,
                style = MaterialTheme.typography.bodySmall,
                color = theme.onCard.copy(alpha = 0.7f),
            )
        }

        ZineToggle(
            checked = def.enabled,
            onCheckedChange = { vm.toggle(def, it) },
            label = if (def.enabled) "Enabled" else "Off — excluded from routing",
        )

        when (def.kind) {
            SocketKind.OAUTH -> {
                Text(
                    "No login flow in this build — it lands in a later one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onCard.copy(alpha = 0.7f),
                )
            }
            SocketKind.LOCAL_TOOL -> {
                // M4: honest copy — model downloads ARE wired via the Offline tab.
                Text(
                    "On-device tool — download the model file from the Offline tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onCard.copy(alpha = 0.7f),
                )
                ZineButton(
                    text = "OPEN OFFLINE TAB",
                    onClick = onOpenOffline,
                    style = ZineButtonStyle.SECONDARY,
                )
            }
            else -> {
                if (vm.keyable(def)) {
                    KeyRow(vm = vm, def = def)
                    TestRow(vm = vm, def = def)
                } else {
                    // ibm-quantum: quantum-computing access, NOT an LLM — no key/test.
                    Text(
                        "No chat test — this socket is not an LLM endpoint.",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onCard.copy(alpha = 0.7f),
                    )
                }
            }
        }

        if (vm.isUserCustom(def)) {
            ZineButton(
                text = "REMOVE CUSTOM SOCKET",
                onClick = { vm.removeCustom(def) },
                style = ZineButtonStyle.DANGER,
            )
        }
    }
}

@Composable
private fun KeyRow(vm: SocketBoardViewModel, def: SocketDef) {
    val theme = LocalZineTheme.current
    val draft = vm.keyDrafts[def.id] ?: ""
    if (vm.hasKey(def)) {
        Text(
            "Key saved ••••${vm.keyLast4(def) ?: ""}",
            style = MaterialTheme.typography.bodySmall,
            color = theme.onCard.copy(alpha = 0.7f),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ZineTextField(
            value = draft,
            onValueChange = { vm.keyDrafts[def.id] = it },
            label = if (vm.hasKey(def)) "Replace key" else "API key",
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        ZineButton(text = "SAVE", onClick = { vm.saveKey(def) })
    }
}

@Composable
private fun TestRow(vm: SocketBoardViewModel, def: SocketDef) {
    val theme = LocalZineTheme.current
    val result = vm.testResults[def.id] ?: SocketBoardViewModel.TestResult.Idle
    Row(verticalAlignment = Alignment.CenterVertically) {
        ZineButton(
            text = "TEST",
            onClick = { vm.test(def) },
            enabled = result != SocketBoardViewModel.TestResult.Testing,
        )
        Spacer(Modifier.width(8.dp))
        when (result) {
            is SocketBoardViewModel.TestResult.Idle -> Text(
                "Sends \"Reply with the single word: ok\".",
                style = MaterialTheme.typography.bodySmall,
                color = theme.onCard.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )
            is SocketBoardViewModel.TestResult.Testing -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Testing…",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onCard,
                )
            }
            is SocketBoardViewModel.TestResult.Success -> Text(
                "Reply: ${result.reply}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = theme.onCard,
                modifier = Modifier.weight(1f),
            )
            is SocketBoardViewModel.TestResult.Failure -> Text(
                result.message,
                style = MaterialTheme.typography.bodySmall,
                color = theme.stamp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AddCustomCard(vm: SocketBoardViewModel) {
    val theme = LocalZineTheme.current
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }

    ZineCard(seed = 777) {
        Text(
            "ROLL YOUR OWN",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = theme.onCard,
        )
        Text(
            "Any OpenAI-compatible chat endpoint, kept on this device as CUSTOM.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.onCard.copy(alpha = 0.7f),
        )
        ZineTextField(
            value = name,
            onValueChange = { name = it },
            label = "Name",
        )
        ZineTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = "Base URL (https://…)",
        )
        ZineTextField(
            value = key,
            onValueChange = { key = it },
            label = "API key (optional)",
            visualTransformation = PasswordVisualTransformation(),
        )
        ZineButton(
            text = "ADD SOCKET",
            onClick = {
                vm.addCustom(name, baseUrl, key)
                name = ""
                baseUrl = ""
                key = ""
            },
            modifier = Modifier.fillMaxWidth(),
        )
        ZineSectionDivider()
        Text(
            "Routing: only ENABLED sockets are offered to the hub. Off genuinely means off.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.onCard.copy(alpha = 0.7f),
        )
    }
}
