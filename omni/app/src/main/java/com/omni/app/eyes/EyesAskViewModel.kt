package com.omni.app.eyes

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.app.safety.KillSwitch
import com.omni.app.sockets.SocketStore
import com.omni.app.vault.AndroidVault
import com.omni.gateway.Caps
import com.omni.gateway.ChatEvent
import com.omni.gateway.ChatMessage
import com.omni.gateway.ChatRequest
import com.omni.gateway.OpenAiCompatClient
import com.omni.gateway.ProviderConfig
import com.omni.gateway.SocketKind
import com.omni.gateway.VisionImage
import com.omni.gateway.actionableGatewayError
import com.omni.vision.Frame
import com.omni.vision.VisionHub
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Ask-about-screen phases. */
enum class AskPhase { IDLE, ASKING, DONE, FAILED }

/**
 * "Ask about screen": grabs the latest fresh capture frame, downscales it to
 * ≤1024px, JPEG-compresses it, and sends it with the user's question to the
 * first enabled VISION-capable API_KEY/CUSTOM socket with a base URL.
 *
 * Design notes:
 * - The vision frame NEVER leaves this flow except as the image part of the
 *   request to the socket the user enabled for vision.
 * - [KillSwitch] is checked before anything is sent.
 * - Uses [OpenAiCompatClient] directly for the single chosen provider:
 *   AiGateway.generate() has no image parameter, and AiGateway is owned by
 *   another track — a future gateway-level images parameter would restore
 *   routing/spend-tracking here.
 * - Honest states: no fresh frame, no vision socket configured, no saved key.
 */
class EyesAskViewModel(
    appContext: Context,
    private val store: SocketStore,
    private val vault: AndroidVault,
) : ViewModel() {

    private val ctx: Context = appContext.applicationContext
    private val client = OpenAiCompatClient(OkHttpClient())

    var question by mutableStateOf("")
    var phase by mutableStateOf(AskPhase.IDLE)
        private set
    var answer by mutableStateOf("")
        private set
    var usedSocketName by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The socket that would serve an ask right now, or null. */
    fun visionSocket(): com.omni.gateway.SocketDef? = pickVisionSocket()

    fun reset() {
        phase = AskPhase.IDLE
        answer = ""
        usedSocketName = null
        error = null
    }

    fun ask() {
        if (phase == AskPhase.ASKING) return
        if (KillSwitch.halted.value) {
            error = "Kill switch is engaged — asks are halted until it is disengaged."
            phase = AskPhase.FAILED
            return
        }
        val q = question.trim()
        if (q.isEmpty()) {
            error = "Ask something about the screen first."
            phase = AskPhase.FAILED
            return
        }
        val frame = freshFrame()
        if (frame == null) {
            error = "No fresh capture frame — start capture and wait a moment."
            phase = AskPhase.FAILED
            return
        }
        val def = pickVisionSocket()
        if (def == null) {
            error = "No vision socket configured — enable a VISION-capable socket on the Sockets tab."
            phase = AskPhase.FAILED
            return
        }
        val apiKey = vault.apiKey(store.keyRef(def))
        if (apiKey.isNullOrBlank()) {
            error = "No API key saved for '${def.displayName}' — save one on the Sockets tab."
            phase = AskPhase.FAILED
            return
        }
        phase = AskPhase.ASKING
        error = null
        answer = ""
        usedSocketName = def.displayName

        val model = def.model ?: com.omni.app.sockets.SocketBoardViewModel.defaultTestModel(def.id)
        val config = ProviderConfig(
            id = def.id,
            baseUrl = def.baseUrl!!,
            apiKeyRef = store.keyRef(def),
            models = listOf(model),
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val image = frameToVisionImage(frame)
                val request = ChatRequest(
                    model = model,
                    messages = listOf(ChatMessage("user", q)),
                    images = listOf(image),
                )
                val sb = StringBuilder()
                client.streamChat(config, apiKey, request).collect { event ->
                    when (event) {
                        is ChatEvent.Token -> {
                            sb.append(event.text)
                            val snapshot = sb.toString()
                            withContext(Dispatchers.Main) { answer = snapshot }
                        }
                        is ChatEvent.Done -> {
                            withContext(Dispatchers.Main) { phase = AskPhase.DONE }
                        }
                        is ChatEvent.Error -> {
                            val msg = actionableGatewayError(event.message)
                            withContext(Dispatchers.Main) {
                                error = msg
                                phase = AskPhase.FAILED
                            }
                        }
                    }
                }
                // Stream ended without Done/Error: treat accumulated text as the answer.
                if (phase == AskPhase.ASKING) {
                    withContext(Dispatchers.Main) {
                        phase = if (sb.isNotEmpty()) AskPhase.DONE else AskPhase.FAILED
                        if (sb.isEmpty()) error = "The socket closed the stream without answering."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = actionableGatewayError(e.message)
                    phase = AskPhase.FAILED
                }
            }
        }
    }

    /**
     * Latest capture frame, only when the pipeline is actually running and the
     * frame is fresh (<2s old). Stale ring frames after a dead service are
     * refused — same honesty rule as [EyesViewModel].
     */
    private fun freshFrame(): Frame? {
        if (!VisionHub.capturing) return null
        val latest = VisionHub.ring.latest() ?: return null
        return if (System.nanoTime() - latest.timestampNs < 2_000_000_000L) latest else null
    }

    private fun pickVisionSocket(): com.omni.gateway.SocketDef? =
        store.all().firstOrNull { def ->
            def.enabled &&
                (def.kind == SocketKind.API_KEY || def.kind == SocketKind.CUSTOM) &&
                !def.baseUrl.isNullOrBlank() &&
                (def.capabilities and Caps.VISION) != 0
        }

    /**
     * Downscale the longest side to ≤1024px, JPEG-compress. Keeps payloads
     * small enough for vision endpoints without inventing detail.
     */
    private fun frameToVisionImage(frame: Frame): VisionImage {
        val src = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        src.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        val scale = (1024f / maxOf(frame.width, frame.height)).coerceAtMost(1f)
        val w = (frame.width * scale).toInt().coerceAtLeast(1)
        val h = (frame.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== src) scaled.recycle()
        src.recycle()
        return VisionImage("image/jpeg", out.toByteArray())
    }
}
