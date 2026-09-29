package com.omni.app.gamemaker.nes

import com.omni.app.sockets.SocketStore
import com.omni.app.sockets.SocketBoardViewModel
import com.omni.app.safety.KillSwitch
import com.omni.app.vault.AndroidVault
import com.omni.gateway.AiGateway
import com.omni.gateway.ChatEvent
import com.omni.gateway.ChatMessage
import com.omni.gateway.OpenAiCompatClient
import com.omni.gateway.ProviderConfig
import com.omni.gateway.Router
import com.omni.gateway.SpendTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import omni.nes.gen.GameSpec
import omni.nes.gen.MockGenerator

/**
 * The LLM seam for the NES game-maker.
 *
 * The game-maker pipeline never talks to a provider directly: it calls
 * [LlmDraft.draft] and gets back raw 6502 assembly text (or a thrown
 * exception, which the orchestrator turns into an honest FAILED run).
 * This keeps the pipeline model-agnostic — a socket, a local model, or a
 * deterministic template all satisfy this one contract.
 */
fun interface LlmDraft {
    /**
     * Returns raw 6502 assembly text for [prompt]. May suspend (network).
     * Throws on any failure; the caller treats that as a draft failure,
     * never as silent empty output.
     */
    suspend fun draft(prompt: String): String
}

/**
 * Adapter from [LlmDraft] to the sibling socket-board call path.
 *
 * This deliberately mirrors `SocketBoardViewModel.runTest`'s exact gateway
 * construction (single [ProviderConfig] scoped to one socket, [AndroidVault]
 * as the [com.omni.gateway.CredentialStore], `OpenAiCompatClient` over
 * OkHttp, fresh [Router]/[SpendTracker]) and reuses its public
 * `defaultTestModel` companion for the model guess — so the draft call goes
 * through the same routing, key resolution, and error surfacing the user
 * already tested on the socket board. Failure messages match the board's
 * ("Socket is OFF", "No key saved for 'X'", provider HTTP errors verbatim).
 */
class SocketLlmDraft(
    private val socketId: String,
    private val store: SocketStore,
    private val vault: AndroidVault,
) : LlmDraft {

    override suspend fun draft(prompt: String): String {
        if (KillSwitch.halted.value)
            throw IllegalStateException("Kill switch engaged — release it in Safety before drafting.")
        val def = store.registry.get(socketId)
            ?: throw IllegalStateException("Socket '$socketId' is no longer registered.")
        require(def.enabled) {
            "Socket '${def.displayName}' is OFF — enable it before drafting."
        }
        val baseUrl = def.baseUrl
            ?: throw IllegalStateException("Socket '${def.displayName}' has no base URL.")
        val ref = store.keyRef(def)
        vault.apiKey(ref)
            ?: throw IllegalStateException("No key saved for '${def.displayName}'. Save a key first.")
        val model = def.model ?: SocketBoardViewModel.defaultTestModel(def.id)

        val gateway = AiGateway(
            providers = listOf(
                ProviderConfig(
                    id = def.id,
                    baseUrl = baseUrl,
                    apiKeyRef = ref,
                    models = listOf(model),
                ),
            ),
            credentials = vault,
            client = OpenAiCompatClient(OkHttpClient()),
            router = Router(),
            tracker = SpendTracker(),
        )

        val sb = StringBuilder()
        var failure: String? = null
        gateway.generate(
            model = model,
            messages = listOf(
                ChatMessage("system", DRAFT_SYSTEM_PROMPT),
                ChatMessage("user", prompt),
            ),
            preferredProvider = def.id,
            temperature = 0.2,
            maxTokens = 4096,
        ).flowOn(Dispatchers.IO).collect { event ->
            when (event) {
                is ChatEvent.Token -> sb.append(event.text)
                is ChatEvent.Error -> if (failure == null) failure = event.message
                is ChatEvent.Done -> Unit
            }
        }
        failure?.let { throw IllegalStateException("Provider error: $it") }
        val text = sb.toString()
        if (text.isBlank()) throw IllegalStateException("Provider returned an empty draft.")
        return text
    }

    companion object {
        /**
         * System prompt handed to the socket's model. Mirrors the seam
         * documented in `omni.nes.gen.GameSpec`: the ONLY acceptable output
         * is assembly in the OMNI assembler's ca65-subset syntax.
         */
        const val DRAFT_SYSTEM_PROMPT: String =
            "You are an NES game programmer. Emit ONLY 6502 assembly in the OMNI assembler syntax: " +
                "labels ending with ':', directives .org/.byte/.word/.res, all official 6502 mnemonics, " +
                "'#' for immediates, '$' for hex. Target NROM-128: code at .org \$C000 (16KB PRG), " +
                "vector table at .org \$FFFA with .word NMI, RESET, IRQ. " +
                "No markdown fences. No commentary. No prose — assembly only."
    }
}

/**
 * Offline deterministic fallback: the proven template generator from the
 * Phase-1 pipeline ([MockGenerator], all validator tests green), wrapped as
 * an [LlmDraft]. Used when no socket is usable; the UI labels its output as
 * template-generated, never as AI-drafted.
 */
class TemplateDraft(private val spec: GameSpec) : LlmDraft {
    private val gen = MockGenerator()
    override suspend fun draft(prompt: String): String = gen.generate(spec)
}
