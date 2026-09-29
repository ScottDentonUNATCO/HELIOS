package com.omni.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omni.app.chat.ChatViewModel
import com.omni.app.eyes.EyesScreen
import com.omni.app.gamemaker.core.GameMakerHubScreen
import com.omni.app.gamemaker.core.RansomCutLetter
import com.omni.app.gamemaker.core.ZineButton
import com.omni.app.gamemaker.core.ZineButtonStyle
import com.omni.app.gamemaker.core.ZineCard
import com.omni.app.gamemaker.core.ZineEmptyState
import com.omni.app.gamemaker.core.ZineGrungeTitle
import com.omni.app.gamemaker.core.ZineHeader
import com.omni.app.gamemaker.core.ZineNeonTitle
import com.omni.app.gamemaker.core.ZineRansomTitle
import com.omni.app.gamemaker.core.ZineScreenBackdrop
import com.omni.app.gamemaker.core.ZineScreenScaffold
import com.omni.app.gamemaker.core.ZineSectionDivider
import com.omni.app.gamemaker.core.ZineSkinPicker
import com.omni.app.gamemaker.core.ZineSkinStore
import com.omni.app.gamemaker.core.ZineStamp
import com.omni.app.gamemaker.core.ZineTextField
import com.omni.app.gamemaker.core.tornPaper
import com.omni.app.gamemaker.core.LocalZineTheme
import com.omni.app.gamemaker.core.ZineThemeProvider
import com.omni.app.gamemaker.core.ZineTitleStyle
import com.omni.app.gamemaker.core.zineThemeForSkin
import com.omni.app.gamemaker.easteregg.ChinaskarV1Activity
import com.omni.app.gamemaker.easteregg.rememberEasterEggTapDetector
import com.omni.app.gamemaker.nes.NesMakerScreen
import com.omni.app.gamemaker.nes.NesMakerViewModel
import com.omni.app.hub.ClaimLedgerScreen
import com.omni.app.hub.ClaimLedgerViewModel
import com.omni.app.hub.HubScreen
import com.omni.app.hub.HubViewModel
import com.omni.app.agentpool.AgentPoolScreen
import com.omni.app.agentpool.AgentPoolViewModel
import com.omni.app.agentpool.AgentPoolViewModelFactory
import com.omni.app.memoryui.ChatMemory
import com.omni.app.memoryui.MemoryScreen
import com.omni.app.memoryui.MemoryViewModel
import com.omni.app.offline.OfflineScreen
import com.omni.app.offline.OfflineViewModel
import com.omni.app.offline.StudioScreen
import com.omni.app.offline.StudioViewModel
import com.omni.app.selfimprove.SelfImproveScreen
import com.omni.app.selfimprove.SelfImproveViewModel
import com.omni.app.safety.SafetyScreen
import com.omni.app.safety.SafetyViewModel
import com.omni.app.safety.SafetyViewModelFactory
import com.omni.app.sockets.SocketBoardScreen
import com.omni.app.sockets.SocketBoardViewModel
import com.omni.app.sockets.SocketStore
import com.omni.app.vault.AndroidVault
import com.omni.gateway.AiGateway
import com.omni.gateway.OpenAiCompatClient
import com.omni.gateway.ProviderConfig
import com.omni.gateway.Router
import com.omni.gateway.SocketKind
import com.omni.gateway.SpendTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

private const val DEFAULT_PROVIDER_ID = "default"
private const val DEFAULT_BASE_URL = "https://api.openai.com"
private const val DEFAULT_MODEL = "gpt-4o-mini"
private const val BUDGET_CAP_USD = 5.0

/**
 * Crash-loop diagnosis screen: plain framework views, zero Compose.
 * Scott screenshots this and the stack trace comes back to the lab.
 */
private fun ComponentActivity.showCrashReport(crashFile: java.io.File) {
    val report = try {
        crashFile.readText()
    } catch (e: Exception) {
        "Could not read crash report: $e"
    }
    val layout = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(32, 32, 32, 32)
    }
    val retry = android.widget.Button(this).apply {
        text = "Clear report & retry"
        setOnClickListener {
            crashFile.delete()
            recreate()
        }
    }
    val scroll = android.widget.ScrollView(this)
    val tv = android.widget.TextView(this).apply {
        text = "Helios crashed on launch. Screenshot this and send it in:\n\n$report"
        setTextIsSelectable(true)
        textSize = 11f
        typeface = android.graphics.Typeface.MONOSPACE
    }
    scroll.addView(tv)
    layout.addView(retry)
    layout.addView(
        scroll,
        android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ),
    )
    setContentView(layout)
}

class ChatViewModelFactory(private val gateway: AiGateway) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ChatViewModel(gateway) as T
}

class SocketBoardViewModelFactory(
    private val store: SocketStore,
    private val vault: AndroidVault,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SocketBoardViewModel(store, vault) as T
}

class MemoryViewModelFactory(private val memory: ChatMemory) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MemoryViewModel(memory) as T
}

class OfflineViewModelFactory(
    private val appContext: android.content.Context,
    private val store: SocketStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        OfflineViewModel(appContext, store) as T
}

class HubViewModelFactory(
    private val appContext: android.content.Context,
    private val gateway: AiGateway,
    private val store: SocketStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        HubViewModel(appContext, gateway, store) as T
}

class SelfImproveViewModelFactory(
    private val appContext: android.content.Context,
    private val hub: HubViewModel,
    private val memory: ChatMemory,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SelfImproveViewModel(appContext, hub, memory) as T
}

class ClaimLedgerViewModelFactory(
    private val appContext: android.content.Context,
    private val hub: HubViewModel,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ClaimLedgerViewModel(appContext, hub) as T
}

class StudioViewModelFactory(
    private val appContext: android.content.Context,
    private val store: SocketStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        StudioViewModel(appContext, store) as T
}

class NesMakerViewModelFactory(
    private val store: SocketStore,
    private val vault: AndroidVault,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        NesMakerViewModel(store, vault) as T
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If a previous run crashed, show the crash report instead of the app.
        // Uses raw framework views (no Compose) so it still works even when
        // Compose itself is the thing crashing.
        val crashFile = java.io.File(filesDir, CrashCatcherProvider.CRASH_FILE)
        if (crashFile.exists()) {
            showCrashReport(crashFile)
            return
        }

        val vault = AndroidVault(applicationContext)
        // Hoisted so the Safety screen can read/reset live spend.
        val spendTracker = SpendTracker()
        /**
         * B1: the gateway carries one ProviderConfig per enabled, keyable
         * socket — not just the default provider. A Hub task's
         * `preferredProvider` id (set to the task's socketId by HubViewModel)
         * then resolves to that socket's own key/base URL instead of silently
         * spending the default key. Sockets with no base URL (OAuth,
         * LOCAL_TOOL) and the non-LLM ibm-quantum socket are skipped: they
         * can't serve chat.
         */
        fun buildGateway(store: SocketStore): AiGateway {
            val providers = mutableListOf(
                ProviderConfig(
                    id = DEFAULT_PROVIDER_ID,
                    baseUrl = vault.baseUrl() ?: DEFAULT_BASE_URL,
                    apiKeyRef = DEFAULT_PROVIDER_ID,
                    models = listOf(DEFAULT_MODEL),
                ),
            )
            for (def in store.all()) {
                val baseUrl = def.baseUrl ?: continue
                if (!def.enabled) continue
                if (def.kind != SocketKind.API_KEY && def.kind != SocketKind.CUSTOM) continue
                if (def.id == SocketBoardViewModel.IBM_QUANTUM_ID) continue
                providers += ProviderConfig(
                    id = def.id,
                    baseUrl = baseUrl,
                    apiKeyRef = store.keyRef(def),
                    models = listOf(def.model ?: SocketBoardViewModel.defaultTestModel(def.id)),
                )
            }
            return AiGateway(
                providers = providers,
                credentials = vault,
                client = OpenAiCompatClient(OkHttpClient()),
                router = Router(),
                tracker = spendTracker,
                budgetCapUsd = BUDGET_CAP_USD,
            )
        }

        setContent {
            // Zine theme wiring: the stored skin ("acid"|"beige"|"ransom"|"grunge")
            // picks the ambient theme for every screen below.
            val skin = remember { ZineSkinStore(this).skin }
            ZineThemeProvider(zineThemeForSkin(skin)) {
                MaterialTheme {
                    var hasKey by remember { mutableStateOf(vault.apiKey(DEFAULT_PROVIDER_ID) != null) }
                    // Shared app state (persisted prefs back the socket store, so rotation is safe).
                    val socketStore = remember { SocketStore(applicationContext) }
                    val chatMemory = remember { ChatMemory(applicationContext) }
                    // Seed the game-maker NES knowledge base into the SAME store
                    // backing chat. Idempotent (guard-flag + versioned upsert), so
                    // exactly once per knowledge version across installs/upgrades.
                    LaunchedEffect(chatMemory) {
                        withContext(Dispatchers.IO) {
                            chatMemory.seedGameMakerKnowledge(applicationContext)
                        }
                    }
                    // Rebuilt whenever the key screen saves, so a changed base URL takes effect.
                    var gatewayVersion by remember { mutableStateOf(0) }
                    val gateway = remember(gatewayVersion) { buildGateway(socketStore) }
                    var tab by remember { mutableStateOf(0) }
                    var moreScreen by remember { mutableStateOf<String?>(null) }
                    // N21: hardware back walks back through the More-screen stack
                    // instead of exiting the app.
                    BackHandler(enabled = moreScreen != null) {
                        moreScreen = if (moreScreen == "nesmaker") "gamemaker" else null
                    }
                    val tabs = listOf("Chat", "Sockets", "Memory", "Eyes", "Offline", "Hub", "Safety", "More")
                    // Shared hub instance: the Hub tab and the More-tab screens (improve,
                    // agent pool, claim ledger) all operate on the same task queue.
                    val hubVm: HubViewModel =
                        viewModel(factory = HubViewModelFactory(applicationContext, gateway, socketStore))
                    // The gateway object is rebuilt when sockets/keys change
                    // (gatewayVersion bump); push the fresh one into the
                    // long-lived HubViewModel so task routing uses it.
                    LaunchedEffect(gateway) { hubVm.updateGateway(gateway) }

                    if (!hasKey) {
                        KeyEntryScreen(
                            vault = vault,
                            onSaved = {
                                gatewayVersion++
                                hasKey = true
                            },
                            // N4: a Cancel path, but only when a key already exists
                            // (opened from Chat's "Key" button). The first-launch
                            // gate stays mandatory — there is nothing to go back to.
                            onCancel = if (vault.apiKey(DEFAULT_PROVIDER_ID) != null) {
                                { hasKey = true }
                            } else {
                                null
                            },
                        )
                    } else {
                        Scaffold(
                            bottomBar = {
                                HeliosBottomNav(
                                    tabs = tabs,
                                    selected = tab,
                                    onSelect = { i ->
                                        // B1: the socket set/enablement may have
                                        // changed on the Sockets tab — rebuild the
                                        // gateway so new ProviderConfigs take effect.
                                        if (tab == 1 && i != 1) gatewayVersion++
                                        tab = i
                                        moreScreen = null
                                    },
                                )
                            },
                        ) { padding ->
                            Column(
                                modifier = Modifier
                                    .padding(padding)
                                    .fillMaxSize(),
                            ) {
                                when (tab) {
                                    0 -> {
                                        val vm: ChatViewModel =
                                            viewModel(factory = ChatViewModelFactory(gateway))
                                        LaunchedEffect(vm) { vm.memory = chatMemory }
                                        LaunchedEffect(gateway) { vm.updateGateway(gateway) }
                                        ChatScreen(vm = vm, onChangeKey = { hasKey = false })
                                    }
                                    1 -> {
                                        val boardVm: SocketBoardViewModel =
                                            viewModel(factory = SocketBoardViewModelFactory(socketStore, vault))
                                        SocketBoardScreen(
                                            vm = boardVm,
                                            onBack = { tab = 0 },
                                            onOpenOffline = { tab = 4 },
                                        )
                                    }
                                    2 -> {
                                        val memVm: MemoryViewModel =
                                            viewModel(factory = MemoryViewModelFactory(chatMemory))
                                        MemoryScreen(vm = memVm, onBack = { tab = 0 })
                                    }
                                    3 -> EyesScreen(onBack = { tab = 0 })
                                    4 -> {
                                        val offVm: OfflineViewModel =
                                            viewModel(factory = OfflineViewModelFactory(applicationContext, socketStore))
                                        OfflineScreen(vm = offVm, onBack = { tab = 0 })
                                    }
                                    5 -> {
                                        HubScreen(
                                            viewModel = hubVm,
                                            socketStore = socketStore,
                                            onBack = { tab = 0 },
                                        )
                                    }
                                    6 -> {
                                        val safeVm: SafetyViewModel =
                                            viewModel(factory = SafetyViewModelFactory(applicationContext, socketStore, spendTracker, BUDGET_CAP_USD))
                                        SafetyScreen(vm = safeVm, onBack = { tab = 0 })
                                    }
                                    else -> {
                                        // "More" tab: second-wave screens.
                                        when (moreScreen) {
                                            "improve" -> {
                                                val vm: SelfImproveViewModel =
                                                    viewModel(factory = SelfImproveViewModelFactory(applicationContext, hubVm, chatMemory))
                                                SelfImproveScreen(vm = vm, onBack = { moreScreen = null })
                                            }
                                            "pool" -> {
                                                val vm: AgentPoolViewModel =
                                                    viewModel(factory = AgentPoolViewModelFactory(applicationContext, hubVm, chatMemory))
                                                AgentPoolScreen(vm = vm, onBack = { moreScreen = null })
                                            }
                                            "studio" -> {
                                                val vm: StudioViewModel =
                                                    viewModel(factory = StudioViewModelFactory(applicationContext, socketStore))
                                                StudioScreen(
                                                    vm = vm,
                                                    onBack = { moreScreen = null },
                                                    onOpenSockets = { tab = 1; moreScreen = null },
                                                )
                                            }
                                            "ledger" -> {
                                                val vm: ClaimLedgerViewModel =
                                                    viewModel(factory = ClaimLedgerViewModelFactory(applicationContext, hubVm))
                                                ClaimLedgerScreen(vm = vm, onBack = { moreScreen = null })
                                            }
                                            "gamemaker" -> {
                                                GameMakerHubScreen(
                                                    onBack = { moreScreen = null },
                                                    onOpenNesMaker = { moreScreen = "nesmaker" },
                                                )
                                            }
                                            "nesmaker" -> {
                                                val vm: NesMakerViewModel =
                                                    viewModel(factory = NesMakerViewModelFactory(socketStore, vault))
                                                NesMakerScreen(vm = vm, onBack = { moreScreen = "gamemaker" })
                                            }
                                            else -> MoreScreen(onPick = { moreScreen = it })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Screen title rendered in the active skin's title style. */
@Composable
private fun ZineTitleForSkin(text: String, modifier: Modifier = Modifier) {
    when (LocalZineTheme.current.titleStyle) {
        ZineTitleStyle.RANSOM -> ZineRansomTitle(text, modifier)
        ZineTitleStyle.STAMP -> ZineHeader(text, modifier)
        ZineTitleStyle.NEON -> ZineNeonTitle(text, modifier)
        ZineTitleStyle.MARKER -> ZineGrungeTitle(text, modifier)
    }
}

/**
 * Bottom tab bar. Every skin except ransom keeps its exact v7 rendering.
 * v8: under the ransom skin the bar sits on a torn-paper strip and each tab
 * gets a cut-out letter scrap icon (the reference's C/S/M/E/O/H/S/M nav)
 * with a pasted label chip.
 */
@Composable
private fun HeliosBottomNav(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val theme = LocalZineTheme.current
    val isRansom = theme.skinKey == "ransom"
    @Composable
    fun bar() {
        NavigationBar(
            containerColor = if (isRansom) Color.Transparent else NavigationBarDefaults.containerColor,
        ) {
            tabs.forEachIndexed { i, label ->
                NavigationBarItem(
                    selected = selected == i,
                    onClick = { onSelect(i) },
                    icon = {
                        if (isRansom) {
                            RansomCutLetter(label.first(), i, 20.sp)
                        } else {
                            Text(label.take(1))
                        }
                    },
                    label = {
                        if (isRansom) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = theme.ink,
                                modifier = Modifier
                                    .background(Color(0xFFFDFDF8).copy(alpha = 0.85f))
                                    .border(1.dp, theme.ink.copy(alpha = 0.4f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        } else {
                            Text(label)
                        }
                    },
                )
            }
        }
    }
    if (isRansom) {
        Box(
            Modifier
                .fillMaxWidth()
                .tornPaper(
                    background = theme.card,
                    edgeColor = theme.ink.copy(alpha = 0.5f),
                    seed = 4242,
                )
                .padding(top = 10.dp, bottom = 4.dp),
        ) {
            bar()
        }
    } else {
        bar()
    }
}

@Composable
private fun MoreScreen(onPick: (String) -> Unit) {
    ZineScreenScaffold(title = "More") {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZineButton("GAME MAKER", onClick = { onPick("gamemaker") }, modifier = Modifier.fillMaxWidth())
            ZineButton("IMPROVE", onClick = { onPick("improve") }, modifier = Modifier.fillMaxWidth())
            ZineButton("AGENT POOL", onClick = { onPick("pool") }, modifier = Modifier.fillMaxWidth())
            ZineButton("STUDIO", onClick = { onPick("studio") }, modifier = Modifier.fillMaxWidth())
            ZineButton("CLAIM LEDGER", onClick = { onPick("ledger") }, modifier = Modifier.fillMaxWidth())
            ZineSectionDivider("SETTINGS")
            ZineSkinPicker()
        }
    }
}

@Composable
private fun KeyEntryScreen(
    vault: AndroidVault,
    onSaved: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var key by remember { mutableStateOf(vault.apiKey(DEFAULT_PROVIDER_ID) ?: "") }
    var baseUrl by remember { mutableStateOf(vault.baseUrl() ?: DEFAULT_BASE_URL) }
    var keyError by remember { mutableStateOf<String?>(null) }
    val theme = LocalZineTheme.current

    ZineScreenScaffold(title = "WAKE UP HELIOS") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            ZineCard(seed = 3) {
                Text(
                    "Slap in an OpenAI-compatible key to wake the machine up. It stays locked in the Android Keystore — on your device, nowhere else.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.onCard,
                )
                ZineTextField(
                    value = key,
                    onValueChange = { key = it; keyError = null },
                    label = "API key",
                    placeholder = "sk-…",
                    visualTransformation = PasswordVisualTransformation(),
                )
                ZineTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = "Base URL",
                    placeholder = DEFAULT_BASE_URL,
                )
                keyError?.let { err ->
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Black,
                        color = theme.stamp,
                    )
                }
                ZineButton(
                    text = "WAKE IT UP",
                    onClick = {
                        if (key.isBlank()) {
                            // N4: blank save was a silent no-op — say so inline.
                            keyError = "No key, no chat — paste one in."
                        } else {
                            vault.saveApiKey(DEFAULT_PROVIDER_ID, key.trim())
                            vault.saveBaseUrl(baseUrl.trim().ifEmpty { DEFAULT_BASE_URL })
                            onSaved()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                // N4: explicit escape when a key already exists.
                if (onCancel != null) {
                    ZineButton(
                        text = "CANCEL",
                        onClick = onCancel,
                        style = ZineButtonStyle.SECONDARY,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(vm: ChatViewModel, onChangeKey: () -> Unit) {
    val listState = rememberLazyListState()
    // CHINASCAR easter egg: 5 rapid taps on the Helios logo opens the hidden
    // build. The hub card only hints — it never fires this itself.
    val ctx = LocalContext.current
    val onLogoTap = rememberEasterEggTapDetector {
        ctx.startActivity(Intent(ctx, ChinaskarV1Activity::class.java))
    }

    // N2: only auto-scroll when the user was already near the bottom, so a
    // streamed token can't yank them away from history they're reading.
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (lastVisible >= vm.messages.lastIndex - 2) {
                listState.animateScrollToItem(vm.messages.lastIndex)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // v8: shared backdrop — theme paper + ransom collage bed + ink corners.
        ZineScreenBackdrop()
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onLogoTap() },
                ) {
                    ZineTitleForSkin("Helios")
                }
                ZineButton(
                    text = "KEY",
                    onClick = onChangeKey,
                    style = ZineButtonStyle.SECONDARY,
                )
            }
            ZineSectionDivider()
            if (vm.messages.isEmpty()) {
                ZineEmptyState(
                    "Nothing here yet. Write the first line — make it a good one.",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(vm.messages.size) { index ->
                        MessageBubble(vm.messages[index])
                    }
                }
            }
            ZineSectionDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZineTextField(
                    value = vm.input,
                    onValueChange = vm::onInputChange,
                    label = "Message",
                    placeholder = "Say something worth stamping.",
                    singleLine = false,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                ZineButton(
                    text = if (vm.sending) "..." else "SEND",
                    onClick = { vm.send(DEFAULT_MODEL) },
                    // N1: Send stays disabled on empty input (send() is a
                    // silent no-op there — don't offer the tap).
                    enabled = !vm.sending && vm.input.isNotBlank(),
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatViewModel.UiMsg) {
    val theme = LocalZineTheme.current
    when (msg.role) {
        "user" -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .graphicsLayer { rotationZ = 1f }
                    .background(theme.accent1)
                    .border(2.dp, theme.ink)
                    .padding(10.dp),
            ) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.onAccent,
                )
            }
        }
        "assistant" -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .graphicsLayer { rotationZ = -1f }
                    .background(theme.card)
                    .border(2.dp, theme.ink)
                    .padding(10.dp),
            ) {
                Text(
                    msg.text.ifEmpty { "…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.onCard,
                )
            }
        }
        "error" -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZineStamp("BUSTED")
            Spacer(Modifier.width(8.dp))
            Text(
                msg.text,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.stamp,
                modifier = Modifier.weight(1f),
            )
        }
        else -> Text(
            msg.text,
            style = MaterialTheme.typography.labelSmall,
            color = theme.onPaper.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        )
    }
}
