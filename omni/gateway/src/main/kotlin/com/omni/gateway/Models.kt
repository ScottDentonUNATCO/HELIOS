package com.omni.gateway

data class ProviderConfig(
    val id: String,
    val baseUrl: String,
    val apiKeyRef: String,
    val models: List<String> = emptyList()
)

data class ChatMessage(val role: String, val content: String)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val maxTokens: Int = 1024
)
