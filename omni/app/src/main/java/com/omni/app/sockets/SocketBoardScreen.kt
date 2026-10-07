package com.omni.app.sockets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.omni.app.gamemaker.core.ZineDoodle
import com.omni.app.gamemaker.core.ZineDoodleKind
import com.omni.app.gamemaker.core.ZineExpander
import com.omni.app.gamemaker.core.ZineHandwritten
import com.omni.app.gamemaker.core.ZineMeter
import com.omni.app.gamemaker.core.ZineScreenScaffold
import com.omni.app.gamemaker.core.ZineSectionDivider
import com.omni.app.gamemaker.core.ZineStamp
import com.omni.app.gamemaker.core.ZineTextField
import com.omni.app.gamemaker.core.LocalZineTheme
import com.omni.app.gamemaker.core.ZineToggle
import com.omni.app.gamemaker.core.ZineStamp
import com.omni.app.onboarding.quickStartTargets
import com.omni.app.onboarding.validateKeyPaste
import com.omni.app.ux.defaultKeysExpanded
import com.omni.app.ux.formatCompactTokens
import com.omni.app.ux.formatCompactUsd
import com.omni.app.ux.isRateLimitedStatus
import com.omni.app.ux.spendBudgetFraction
import com.omni.app.ux.tokenBudgetFraction
import com.omni.gateway.Caps
import com.omni.gateway.KeyInstance
import com.omni.gateway.SocketDef
import com.omni.gateway.SocketKind
import com.omni.app.insignia.DivisionInsignia
import com.omni.app.insignia.InsigniaHeader

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
    onOpenChat: () -> Unit = {},
) {
    val theme = LocalZineTheme.current
    // v17 Worker F: SYNAPSE division insignia — the socket board's identity.
    ZineScreenScaffold(
        title = "Socket board",
        onBack = onBack,
        actions = { InsigniaHeader(DivisionInsignia.SYNAPSE) },
    ) {
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
            item(key = "quickstart") {
                QuickStartCard(vm = vm, onOpenChat = onOpenChat)
            }
            itemsIndexed(vm.sockets, key = { _, def -> def.id }) { index, def ->
                SocketCard(vm = vm, def = def, seed = index, onOpenOffline = onOpenOffline)
            }

            item(key = "add-custom") {
                AddCustomCard(vm = vm)
            }
        }
    }
}

/**
 * "60-SECOND KEY DROP" (patterns doc: first value inside 60 seconds): the
 * fastest path from zero to a working key — pick a socket, paste a key, ADD
 * fires the connectivity test on the spot, and a success stamp hands over a
 * GO CHAT button. Hidden once any socket holds a saved key AND this card
 * wasn't the one that put it there — progressive disclosure, no nagging
 * past activation.
 */
@Composable
private fun QuickStartCard(vm: SocketBoardViewModel, onOpenChat: () -> Unit) {
    // Recompose when keys change so the card hides after activation.
    @Suppress("UNUSED_EXPRESSION") vm.keyInstancesVersion
    val targets = remember(vm.sockets) { quickStartTargets(vm.sockets) }
    if (targets.isEmpty()) return
    // Set when THIS card's ADD saved the key, so the in-flight test and its
    // success stamp stay visible instead of the card vanishing mid-flow.
    var activatedHere by remember { mutableStateOf(false) }
    if (!activatedHere && vm.sockets.any { vm.hasSavedKey(it) }) return

    val theme = LocalZineTheme.current
    var selectedId by remember { mutableStateOf(targets.first().id) }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val def = targets.firstOrNull { it.id == selectedId } ?: targets.first()

    ZineCard(seed = 42) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "60-SECOND KEY DROP",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = theme.onCard,
                modifier = Modifier.weight(1f),
            )
            ZineStamp("FAST LANE")
        }
        Text(
            "Pick a socket, paste a key, hit ADD — Helios tests it on the spot. " +
                "Fastest way from zero to talking.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.onCard.copy(alpha = 0.7f),
        )
        SocketPickerRow(
            targets = targets,
            selectedId = selectedId,
            onSelect = { selectedId = it; error = null },
        )
        ZineTextField(
            value = key,
            onValueChange = { key = it; error = null },
            label = "API key for ${def.displayName}",
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Black,
                color = theme.stamp,
            )
        }
        ZineButton(
            text = "ADD KEY + TEST",
            onClick = {
                // Fast client-side pass first; the ViewModel re-validates.
                validateKeyPaste(key)?.let { error = it; return@ZineButton }
                val err = vm.quickAddKey(def, key.trim())
                if (err != null) {
                    error = err
                } else {
                    key = ""
                    error = null
                    activatedHere = true
                    vm.test(def)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        when (val result = vm.testResults[def.id]) {
            is SocketBoardViewModel.TestResult.Testing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Testing…",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onCard,
                    )
                }
            }
            is SocketBoardViewModel.TestResult.Success -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZineStamp("IT'S ALIVE")
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Reply: ${result.reply}",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onCard,
                        modifier = Modifier.weight(1f),
                    )
                }
                ZineButton(
                    text = "GO CHAT",
                    onClick = onOpenChat,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is SocketBoardViewModel.TestResult.Failure -> {
                Text(
                    result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.stamp,
                )
            }
            else -> Unit
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SocketPickerRow(
    targets: List<SocketDef>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (target in targets) {
            ZineButton(
                text = target.displayName,
                onClick = { onSelect(target.id) },
                style = if (target.id == selectedId) ZineButtonStyle.PRIMARY else ZineButtonStyle.SECONDARY,
            )
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
                    // Design gem (Zen of Palm): KEYS & TEST live behind one
                    // labeled expander — open by default only when setup is
                    // still pending, so 25 configured sockets stop shouting.
                    // Read as state so add/remove recomposes the count.
                    @Suppress("UNUSED_EXPRESSION") vm.keyInstancesVersion
                    val keyCount = vm.instancesFor(def).size
                    ZineExpander(
                        title = "Keys & test ($keyCount)",
                        defaultExpanded = defaultKeysExpanded(keyCount),
                        seed = seed + 500,
                    ) {
                        KeyInstancesSection(vm = vm, def = def)
                        TestRow(vm = vm, def = def, hasKeys = vm.hasSavedKey(def))
                    }
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
            // Design gem (Norman forcing function / Rams thorough): removing
            // a custom socket destroys its keys and budgets — one tap was
            // too easy. Same confirm pattern as Memory's delete (N7).
            var confirmRemove by remember { mutableStateOf(false) }
            ZineButton(
                text = "REMOVE CUSTOM SOCKET",
                onClick = { confirmRemove = true },
                style = ZineButtonStyle.DANGER,
            )
            if (confirmRemove) {
                AlertDialog(
                    onDismissRequest = { confirmRemove = false },
                    title = { Text("Remove ${def.displayName}?") },
                    text = { Text("Its keys and budgets go with it. This can't be undone.") },
                    confirmButton = {
                        ZineButton(
                            text = "REMOVE",
                            onClick = { vm.removeCustom(def); confirmRemove = false },
                            style = ZineButtonStyle.DANGER,
                        )
                    },
                    dismissButton = {
                        ZineButton(
                            text = "KEEP",
                            onClick = { confirmRemove = false },
                            style = ZineButtonStyle.SECONDARY,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun KeyInstancesSection(vm: SocketBoardViewModel, def: SocketDef) {
    val theme = LocalZineTheme.current
    // Read as state so instance add/remove/enable recomposes the list.
    @Suppress("UNUSED_EXPRESSION") vm.keyInstancesVersion
    val instances = vm.instancesFor(def)
    // Design gem (Rams: unobtrusive): the old code ran an infinite 1-second
    // ticker on EVERY socket card, forever — 25+ coroutines waking the UI
    // thread each second to render countdowns nobody was watching. Now the
    // tick is 1s only while a key is in a live 429 backoff, otherwise a 15s
    // heartbeat just to notice new backoffs (e.g. from Hub tasks).
    var tick by remember { mutableStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") tick
    val backoffLive = remember(tick) {
        instances.any { isRateLimitedStatus(vm.instanceStatus(it)) }
    }
    LaunchedEffect(def.id, backoffLive) {
        while (true) {
            kotlinx.coroutines.delay(if (backoffLive) 1000L else 15000L)
            tick++
        }
    }

    Text(
        "KEYS (${instances.size}) — round-robin, 429 fails over",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Black,
        color = theme.onCard,
    )
    if (instances.isEmpty()) {
        Text(
            "No keys yet — add one below.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.onCard.copy(alpha = 0.7f),
        )
    }
    for (instance in instances) {
        KeyInstanceRow(vm = vm, def = def, instance = instance)
    }
    AddInstanceForm(vm = vm, def = def)
}

@Composable
private fun KeyInstanceRow(
    vm: SocketBoardViewModel,
    def: SocketDef,
    instance: KeyInstance,
) {
    val theme = LocalZineTheme.current
    val status = vm.instanceStatus(instance)
    val usage = vm.instanceUsage(instance)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                instance.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Black,
                color = theme.onCard,
                modifier = Modifier.weight(1f),
            )
            Text(
                status.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = if (status == "ready") theme.onCard.copy(alpha = 0.7f) else theme.stamp,
            )
        }
        Text(
            "••••${vm.instanceKeyLast4(instance) ?: "????"}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Black,
            color = theme.onCard,
        )
        // Design gem (Tufte): usage as data bars, not a concatenated string.
        // Each bar names its numbers; no budget configured = no bar, honest copy.
        val tokenFrac = tokenBudgetFraction(usage.tokensToday, instance.dailyTokenBudget)
        if (tokenFrac != null) {
            ZineMeter(
                fraction = tokenFrac,
                caption = "${formatCompactTokens(usage.tokensToday)} / " +
                    "${formatCompactTokens(instance.dailyTokenBudget ?: 0L)} tokens today",
            )
        } else {
            Text(
                "${formatCompactTokens(usage.tokensToday)} tokens today · no daily budget set",
                style = MaterialTheme.typography.bodySmall,
                color = theme.onCard.copy(alpha = 0.7f),
            )
        }
        val spendFrac = spendBudgetFraction(usage.spendMonthUsd, instance.monthlySpendBudgetUsd)
        if (spendFrac != null) {
            ZineMeter(
                fraction = spendFrac,
                caption = "${formatCompactUsd(usage.spendMonthUsd)} / " +
                    "${formatCompactUsd(instance.monthlySpendBudgetUsd ?: 0.0)} this month",
            )
        } else {
            Text(
                "${formatCompactUsd(usage.spendMonthUsd)} spent this month · no monthly budget set",
                style = MaterialTheme.typography.bodySmall,
                color = theme.onCard.copy(alpha = 0.7f),
            )
        }
        if (!instance.enabled) {
            Text(
                "Disabled — skipped by the router.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.stamp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZineButton(
                text = if (instance.enabled) "DISABLE" else "ENABLE",
                onClick = { vm.setInstanceEnabled(def, instance, !instance.enabled) },
                style = ZineButtonStyle.SECONDARY,
            )
            // Design gem (Norman forcing function): removing a key destroys
            // its usage history and budgets — confirm first.
            var confirmRemove by remember { mutableStateOf(false) }
            ZineButton(
                text = "REMOVE",
                onClick = { confirmRemove = true },
                style = ZineButtonStyle.DANGER,
            )
            if (confirmRemove) {
                AlertDialog(
                    onDismissRequest = { confirmRemove = false },
                    title = { Text("Remove key '${instance.label}'?") },
                    text = { Text("Its usage history and budgets go with it. This can't be undone.") },
                    confirmButton = {
                        ZineButton(
                            text = "REMOVE",
                            onClick = { vm.removeInstance(def, instance); confirmRemove = false },
                            style = ZineButtonStyle.DANGER,
                        )
                    },
                    dismissButton = {
                        ZineButton(
                            text = "KEEP",
                            onClick = { confirmRemove = false },
                            style = ZineButtonStyle.SECONDARY,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun AddInstanceForm(vm: SocketBoardViewModel, def: SocketDef) {
    val theme = LocalZineTheme.current
    var label by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var daily by remember { mutableStateOf("") }
    var monthly by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Text(
        "ADD A KEY",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Black,
        color = theme.onCard,
    )
    ZineTextField(
        value = label,
        onValueChange = { label = it; error = null },
        label = "Label (e.g. Personal)",
        modifier = Modifier.fillMaxWidth(),
    )
    ZineTextField(
        value = key,
        onValueChange = { key = it; error = null },
        label = "API key",
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZineTextField(
            value = daily,
            onValueChange = { daily = it; error = null },
            label = "Tokens/day (optional)",
            modifier = Modifier.weight(1f),
        )
        ZineTextField(
            value = monthly,
            onValueChange = { monthly = it; error = null },
            label = "$/month (optional)",
            modifier = Modifier.weight(1f),
        )
    }
    error?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Black,
            color = theme.stamp,
        )
    }
    ZineButton(
        text = "ADD KEY",
        onClick = {
            val dailyTrimmed = daily.trim()
            val monthlyTrimmed = monthly.trim()
            val dailyTokens = dailyTrimmed.ifEmpty { null }?.toLongOrNull()
            val monthlySpend = monthlyTrimmed.ifEmpty { null }?.toDoubleOrNull()
            if (dailyTrimmed.isNotEmpty() && dailyTokens == null) {
                error = "Daily budget must be a whole number."
                return@ZineButton
            }
            if (monthlyTrimmed.isNotEmpty() && monthlySpend == null) {
                error = "Monthly budget must be a number."
                return@ZineButton
            }
            val err = vm.addInstance(def, label.trim(), key.trim(), dailyTokens, monthlySpend)
            if (err == null) {
                label = ""
                key = ""
                daily = ""
                monthly = ""
                error = null
            } else {
                error = err
            }
        },
    )
    Text(
        "Keys round-robin. A 429 on one key fails over to the next instantly; " +
            "a key that hits its budget sits out until the budget resets.",
        style = MaterialTheme.typography.bodySmall,
        color = theme.onCard.copy(alpha = 0.7f),
    )
}

@Composable
private fun TestRow(vm: SocketBoardViewModel, def: SocketDef, hasKeys: Boolean) {
    val theme = LocalZineTheme.current
    val result = vm.testResults[def.id] ?: SocketBoardViewModel.TestResult.Idle
    Row(verticalAlignment = Alignment.CenterVertically) {
        ZineButton(
            text = "TEST",
            onClick = { vm.test(def) },
            // Design gem (Norman signifier / Krug): the old TEST was always
            // tappable and always failed with "no key saved" when no key
            // existed — a dead control. Now it's disabled with a signifier.
            enabled = hasKeys && result != SocketBoardViewModel.TestResult.Testing,
        )
        Spacer(Modifier.width(8.dp))
        when (result) {
            is SocketBoardViewModel.TestResult.Idle -> Text(
                if (hasKeys) "Sends \"Reply with the single word: ok\"."
                else "Add a key above — TEST has nothing to send without one.",
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "ROLL YOUR OWN",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = theme.onCard,
                modifier = Modifier.weight(1f),
            )
            ZineDoodle(kind = ZineDoodleKind.LIGHTNING, seed = 12, alpha = 0.8f)
        }
        Text(
            "Any OpenAI-compatible chat endpoint, kept on this device as CUSTOM.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.onCard.copy(alpha = 0.7f),
        )
        ZineHandwritten("(yes, really — any endpoint. we checked.)", seed = 5)
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
