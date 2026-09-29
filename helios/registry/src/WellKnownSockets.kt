package com.omni.gateway

/**
 * Curated starter catalog. Keyed sockets need the user to add their key in the
 * vault; nothing here is "working" until a live call succeeds on device.
 * OAuth entries need a real login flow before they can serve traffic.
 */
object WellKnownSockets {

    private fun key(
        id: String, name: String, url: String, caps: Int,
        model: String? = null, notes: String? = null,
    ) = SocketDef(id, name, SocketKind.API_KEY, caps, baseUrl = url, apiKeyRef = "vault:$id", model = model, notes = notes)

    fun defaults(): List<SocketDef> = listOf(
        // --- chat / reasoning keys ---
        key("openai", "OpenAI", "https://api.openai.com/v1", Caps.CHAT or Caps.VISION or Caps.IMAGE_GEN or Caps.TTS or Caps.STT),
        key("anthropic", "Anthropic", "https://api.anthropic.com", Caps.CHAT or Caps.VISION or Caps.CODE,
            notes = "Native Anthropic API; needs adapter, not OpenAI-compat"),
        key("gemini-key", "Google Gemini (API key)", "https://generativelanguage.googleapis.com/v1beta/openai/",
            Caps.CHAT or Caps.VISION or Caps.IMAGE_GEN or Caps.TTS or Caps.STT),
        key("xai", "xAI Grok", "https://api.x.ai/v1", Caps.CHAT or Caps.VISION),
        key("deepseek", "DeepSeek", "https://api.deepseek.com/v1", Caps.CHAT or Caps.CODE),
        key("mistral", "Mistral", "https://api.mistral.ai/v1", Caps.CHAT or Caps.VISION or Caps.CODE),
        key("groq", "Groq", "https://api.groq.com/openai/v1", Caps.CHAT or Caps.VISION or Caps.STT or Caps.TTS),
        key("together", "Together AI", "https://api.together.xyz/v1", Caps.CHAT or Caps.VISION or Caps.IMAGE_GEN),
        key("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1", Caps.CHAT or Caps.VISION),
        key("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", Caps.CHAT or Caps.VISION),
        key("cohere", "Cohere", "https://api.cohere.com/compatibility/v1", Caps.CHAT),
        key("perplexity", "Perplexity", "https://api.perplexity.ai", Caps.CHAT),
        key("huggingface", "Hugging Face Inference", "https://router.huggingface.co/v1",
            Caps.CHAT or Caps.VISION or Caps.IMAGE_GEN,
            notes = "Hosts thousands of open models; token from huggingface.co/settings/tokens"),
        // --- logins (OAuth) ---
        SocketDef("gemini-oauth", "Google Gemini (login)", SocketKind.OAUTH,
            Caps.CHAT or Caps.VISION or Caps.IMAGE_GEN, accountId = "google",
            notes = "Needs Google sign-in flow; not wired yet"),
        SocketDef("meta-oauth", "Meta AI (login)", SocketKind.OAUTH,
            Caps.CHAT or Caps.VISION, accountId = "meta",
            notes = "Needs Meta sign-in flow; not wired yet"),
        // --- local / offline tools ---
        SocketDef("ollama", "Ollama (local server)", SocketKind.API_KEY,
            Caps.CHAT or Caps.VISION or Caps.CODE, baseUrl = "http://localhost:11434/v1",
            apiKeyRef = "none", notes = "No key needed; point at a local Ollama"),
        SocketDef("llamacpp", "llama.cpp (on-device)", SocketKind.LOCAL_TOOL,
            Caps.CHAT or Caps.CODE, installPath = "files/llamacpp", toolVersion = "bundled",
            notes = "GGUF 0.5-3B Q4 recommended on Moto-class phones"),
        SocketDef("whispercpp", "whisper.cpp (on-device STT)", SocketKind.LOCAL_TOOL,
            Caps.STT, installPath = "files/whispercpp", toolVersion = "bundled"),
        SocketDef("piper-tts", "Piper/Kokoro (on-device TTS)", SocketKind.LOCAL_TOOL,
            Caps.TTS, installPath = "files/piper", toolVersion = "bundled"),
        // --- generation: video / music / voice ---
        key("replicate", "Replicate", "https://api.replicate.com/v1",
            Caps.IMAGE_GEN or Caps.VIDEO_GEN or Caps.MUSIC_GEN,
            notes = "Cloud GPUs running open models (SD, AnimateDiff, MusicGen, SVD); pay per run"),
        key("elevenlabs", "ElevenLabs", "https://api.elevenlabs.io/v1",
            Caps.TTS or Caps.MUSIC_GEN, notes = "TTS + voice/music tooling"),
        key("runway", "Runway", "https://api.dev.runwayml.com/v1", Caps.VIDEO_GEN or Caps.IMAGE_GEN,
            notes = "Check current API availability before relying on it"),
        key("luma", "Luma Dream Machine", "https://api.lumalabs.ai/dream-machine/v1", Caps.VIDEO_GEN,
            notes = "Check current API availability before relying on it"),
        key("stability", "Stability AI", "https://api.stability.ai/v2beta", Caps.IMAGE_GEN or Caps.VIDEO_GEN),
        // --- custom slots for the user ---
        SocketDef("custom-1", "Custom socket 1", SocketKind.CUSTOM,
            Caps.CHAT, baseUrl = "https://", authScheme = "bearer", enabled = false,
            notes = "User-defined endpoint; fill in baseUrl + authScheme"),
        SocketDef("azure-openai", "Azure OpenAI", SocketKind.CUSTOM,
            Caps.CHAT or Caps.VISION, baseUrl = "https://YOUR-RESOURCE.openai.azure.com", authScheme = "api-key",
            enabled = false, notes = "Fill in your resource URL + deployment"),
        SocketDef("bedrock", "AWS Bedrock", SocketKind.CUSTOM,
            Caps.CHAT or Caps.VISION, baseUrl = "https://bedrock-runtime.REGION.amazonaws.com", authScheme = "aws-sigv4",
            enabled = false, notes = "Needs AWS SigV4 signing; adapter not built yet"),
        SocketDef("ibm-quantum", "IBM Quantum", SocketKind.CUSTOM,
            Caps.CODE, baseUrl = "https://auth.quantum-computing.ibm.com/api", authScheme = "x-access-token",
            enabled = false,
            notes = "Quantum-computing access, NOT an LLM. Real for quantum experiments; do not expect chat."),
    )
}
