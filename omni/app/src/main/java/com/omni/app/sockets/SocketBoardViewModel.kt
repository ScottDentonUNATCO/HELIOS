package com.omni.app.sockets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.app.safety.KillSwitch
import com.omni.app.vault.AndroidVault
import com.omni.gateway.AiGateway
import com.omni.gateway.ChatEvent
import com.omni.gateway.ChatMessage
import com.omni.gateway.OpenAiCompatClient
import com.omni.gateway.ProviderConfig
import com.omni.gateway.Router
import com.omni.gateway.SocketDef
import com.omni.gateway.SocketKind
import com.omni.gateway.SpendTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * State for the socket board. Survives rotation (it's a ViewModel).
 *
 * Privacy: keys are written straight to [AndroidVault] and never kept in
 * memory longer than a test call. The UI only ever sees "saved ••••" + last 4,
 * and failures report exception class + message — never key material.
 */
class SocketBoardViewModel(
    private val store: SocketStore,
    private val vault: AndroidVault,
) : ViewModel() {

    sealed interface TestResult {
        data object Idle : TestResult
        data object Testing : TestResult
        data class Success(val reply: String) : TestResult
        data class Failure(val message: String) : TestResult
    }

    var sockets by mutableStateOf(store.all())
        private set

    /** Unsaved key drafts, per socket id. Cleared on save. */
    val keyDrafts = mutableStateMapOf<String, String>()

    val testResults = mutableStateMapOf<String, TestResult>()

    var notice by mutableStateOf<String?>(null)
        private set

    fun keyable(def: SocketDef): Boolean =
        (def.kind == SocketKind.API_KEY || def.kind == SocketKind.CUSTOM) &&
            def.id != IBM_QUANTUM_ID

    fun hasKey(def: SocketDef): Boolean =
        vault.apiKey(store.keyRef(def)) != null

    fun keyLast4(def: SocketDef): String? =
        vault.apiKey(store.keyRef(def))?.takeLast(4)

    fun isUserCustom(def: SocketDef): Boolean = store.isUserCustom(def.id)

    fun toggle(def: SocketDef, enabled: Boolean) {
        store.setEnabled(def.id, enabled)
        sockets = store.all()
    }

    fun saveKey(def: SocketDef) {
        val draft = keyDrafts[def.id].orEmpty().trim()
        if (draft.isBlank()) {
            notice = "Key is empty — not saved."
            return
        }
        vault.saveApiKey(store.keyRef(def), draft)
        keyDrafts.remove(def.id)
        notice = "Saved key for ${def.displayName} (••••${draft.takeLast(4)})."
    }

    fun test(def: SocketDef) {
        if (KillSwitch.halted.value) {
            testResults[def.id] =
                TestResult.Failure("Kill switch is engaged — release it in Safety before testing.")
            return
        }
        if (testResults[def.id] == TestResult.Testing) return
        testResults[def.id] = TestResult.Testing
        viewModelScope.launch {
            testResults[def.id] = runTest(def)
        }
    }

    /**
     * Fires one minimal chat request through the same [AiGateway.generate]
     * path ChatViewModel uses, scoped to this single socket. Returns the reply
     * text, or the EXACT failure (HTTP status from the provider, or exception
     * class + message).
     */
    private suspend fun runTest(def: SocketDef): TestResult {
        val cur = store.registry.get(def.id)
            ?: return TestResult.Failure("Socket '${def.id}' is no longer registered.")
        if (!cur.enabled) {
            return TestResult.Failure("Socket is OFF — enable it before testing.")
        }
        val baseUrl = cur.baseUrl
            ?: return TestResult.Failure("Socket '${cur.displayName}' has no base URL.")
        val ref = store.keyRef(cur)
        val key = vault.apiKey(ref)
            ?: return TestResult.Failure("No key saved for '${cur.displayName}'. Save a key first.")
        val model = cur.model ?: defaultTestModel(cur.id)
        val config = ProviderConfig(
            id = cur.id,
            baseUrl = baseUrl,
            apiKeyRef = ref,
            models = listOf(model),
        )
        val gateway = AiGateway(
            providers = listOf(config),
            credentials = vault,
            client = OpenAiCompatClient(OkHttpClient()),
            router = Router(),
            tracker = SpendTracker(),
        )
        return try {
            val sb = StringBuilder()
            var failure: String? = null
            gateway.generate(
                model = model,
                messages = listOf(ChatMessage("user", "Reply with the single word: ok")),
                temperature = 0.0,
                maxTokens = 16,
            ).flowOn(Dispatchers.IO).collect { event ->
                when (event) {
                    is ChatEvent.Token -> sb.append(event.text)
                    is ChatEvent.Error -> if (failure == null) failure = event.message
                    is ChatEvent.Done -> Unit
                }
            }
            failure?.let { TestResult.Failure(it) }
                ?: run {
                    val reply = sb.toString().trim()
                    TestResult.Success(if (reply.isEmpty()) "(empty reply)" else reply.take(400))
                }
        } catch (e: Exception) {
            TestResult.Failure(
                "${e.javaClass.simpleName}: ${e.message} — " +
                    "check the socket's key and base URL, then test again.",
            )
        }
    }

    fun addCustom(name: String, baseUrl: String, key: String) {
        val url = baseUrl.trim()
        if (url.isBlank()) {
            notice = "Base URL is required."
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            notice = "Base URL must start with http:// or https://"
            return
        }
        try {
            val def = store.addCustom(name.trim(), url)
            val k = key.trim()
            if (k.isNotBlank()) vault.saveApiKey(store.keyRef(def), k)
            sockets = store.all()
            notice = "Added '${def.displayName}'."
        } catch (e: IllegalArgumentException) {
            notice = "Could not add socket: ${e.message}"
        }
    }

    fun removeCustom(def: SocketDef) {
        if (store.removeCustom(def.id)) {
            keyDrafts.remove(def.id)
            testResults.remove(def.id)
            sockets = store.all()
            notice = "Removed '${def.displayName}'."
        }
    }

    fun clearNotice() {
        notice = null
    }

    companion object {
        const val IBM_QUANTUM_ID = "ibm-quantum"

        /**
         * Best-effort default model per socket for the connectivity ping. A
         * wrong guess surfaces as an exact HTTP error (e.g. model-not-found),
         * which still proves/denies the key — never silently swallowed.
         */
        fun defaultTestModel(socketId: String): String = when (socketId) {
            "deepseek" -> "deepseek-chat"
            "xai" -> "grok-2-latest"
            "mistral" -> "mistral-small-latest"
            "gemini-key" -> "gemini-2.0-flash"
            "cohere" -> "command-r"
            "perplexity" -> "sonar"
            else -> "gpt-4o-mini"
        }
    }
}
