package com.omni.app.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.app.memoryui.ChatMemory
import com.omni.gateway.AiGateway
import com.omni.gateway.ChatEvent
import com.omni.gateway.ChatMessage
import com.omni.gateway.actionableGatewayError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Minimal Phase 0 chat state. Roles: "user" | "assistant" | "system" | "error". */
class ChatViewModel(private var gateway: AiGateway) : ViewModel() {

    data class UiMsg(val role: String, val text: String)

    val messages = mutableStateListOf<UiMsg>()
    var input by mutableStateOf("")
        private set
    var sending by mutableStateOf(false)
        private set

    /** Wired by the host activity; null means memory is disabled. */
    var memory: ChatMemory? = null

    fun onInputChange(value: String) {
        input = value
    }

    /**
     * Swaps the gateway after the host rebuilds it (key or base URL
     * changed). In-flight sends keep the old one.
     */
    fun updateGateway(g: AiGateway) {
        gateway = g
    }

    fun send(model: String = "gpt-4o-mini") {
        if (com.omni.app.safety.KillSwitch.halted.value) {
            messages.add(UiMsg("error", "Kill switch engaged — release it in Safety"))
            return
        }
        val text = input.trim()
        if (text.isEmpty() || sending) return
        input = ""
        messages.add(UiMsg("user", text))
        sending = true

        viewModelScope.launch {
            try {
                val recalled = memory?.recall(text, 3)
                val history = buildList {
                    recalled?.forEach { r ->
                        add(ChatMessage("system", "[memory ${r.scope}] ${r.key}: ${r.value}"))
                    }
                    addAll(
                        messages
                            .filter { it.role == "user" || it.role == "assistant" }
                            .map { ChatMessage(role = it.role, content = it.text) }
                    )
                }

                messages.add(UiMsg("assistant", ""))
                val streamIndex = messages.lastIndex

                gateway.generate(model = model, messages = history)
                    .flowOn(Dispatchers.IO)
                    .collect { event ->
                        when (event) {
                            is ChatEvent.Token -> {
                                val cur = messages[streamIndex]
                                messages[streamIndex] = cur.copy(text = cur.text + event.text)
                            }
                            is ChatEvent.Done -> {
                                val assistantText =
                                    if (streamIndex in messages.indices) messages[streamIndex].text
                                    else ""
                                withContext(Dispatchers.IO) {
                                    memory?.persistTurn(text, assistantText)
                                }
                                messages.add(
                                    UiMsg(
                                        "system",
                                        "\u2191 ${event.promptTokens} prompt / \u2193 ${event.completionTokens} completion tokens",
                                    )
                                )
                            }
                            is ChatEvent.Error -> {
                                messages.add(UiMsg("error", actionableGatewayError(event.message)))
                            }
                        }
                    }
            } catch (e: Exception) {
                messages.add(
                    UiMsg(
                        "error",
                        "Send failed (${e.javaClass.simpleName}): ${e.message ?: "no details"} — " +
                            "check your connection and the Sockets tab, then try again.",
                    ),
                )
            } finally {
                sending = false
            }
        }
    }
}
