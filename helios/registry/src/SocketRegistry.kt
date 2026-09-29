package com.omni.gateway

/** How a socket is provisioned and authenticated. */
enum class SocketKind {
    /** Vault-held API key against a base URL (OpenAI-compatible shape). */
    API_KEY,
    /** Login-based access (Gemini/Meta account); token managed by the vault. */
    OAUTH,
    /** Downloaded open-source tool on device (llama.cpp, whisper.cpp, ...). */
    LOCAL_TOOL,
    /** User-defined endpoint with a named auth scheme. */
    CUSTOM,
}

/** Capability bit flags for what a socket can do. */
object Caps {
    const val CHAT = 1 shl 0
    const val VISION = 1 shl 1
    const val IMAGE_GEN = 1 shl 2
    const val VIDEO_GEN = 1 shl 3
    const val MUSIC_GEN = 1 shl 4
    const val TTS = 1 shl 5
    const val STT = 1 shl 6
    const val CODE = 1 shl 7
    const val DEVICE = 1 shl 8
    const val ALL = (1 shl 9) - 1
}

/**
 * One AI socket: an API key, an OAuth login, a downloaded local tool,
 * or a custom endpoint. The neutral hub routes tasks to sockets by capability.
 */
data class SocketDef(
    val id: String,
    val displayName: String,
    val kind: SocketKind,
    val capabilities: Int,
    val baseUrl: String? = null,
    val apiKeyRef: String? = null,
    val accountId: String? = null,
    val installPath: String? = null,
    val toolVersion: String? = null,
    val authScheme: String? = null,
    val model: String? = null,
    val notes: String? = null,
    val enabled: Boolean = true,
)

/** Validates a [SocketDef]; throws [IllegalArgumentException] with a clear reason. */
fun validateSocket(def: SocketDef) {
    require(def.id.isNotBlank()) { "socket id must not be blank" }
    require(def.displayName.isNotBlank()) { "socket displayName must not be blank" }
    require(def.capabilities != 0) { "socket '${def.id}' must declare at least one capability" }
    require(def.capabilities and Caps.ALL.inv() == 0) { "socket '${def.id}' has unknown capability bits" }
    when (def.kind) {
        SocketKind.API_KEY -> {
            require(!def.baseUrl.isNullOrBlank()) { "API_KEY socket '${def.id}' needs baseUrl" }
            require(!def.apiKeyRef.isNullOrBlank()) { "API_KEY socket '${def.id}' needs apiKeyRef" }
        }
        SocketKind.OAUTH -> {
            require(!def.accountId.isNullOrBlank()) { "OAUTH socket '${def.id}' needs accountId" }
        }
        SocketKind.LOCAL_TOOL -> {
            require(!def.installPath.isNullOrBlank()) { "LOCAL_TOOL socket '${def.id}' needs installPath" }
            require(!def.toolVersion.isNullOrBlank()) { "LOCAL_TOOL socket '${def.id}' needs toolVersion" }
        }
        SocketKind.CUSTOM -> {
            require(!def.baseUrl.isNullOrBlank()) { "CUSTOM socket '${def.id}' needs baseUrl" }
            require(!def.authScheme.isNullOrBlank()) { "CUSTOM socket '${def.id}' needs authScheme" }
        }
    }
}

/** In-memory registry of every socket Helios can route to. Off genuinely means off. */
class SocketRegistry {
    // Thread safety (stress-verified 2026-09-24): the hub routes from worker
    // coroutines while the UI registers/toggles sockets. Every access takes
    // [lock], so a pick (withCapability/get) always sees the latest
    // setEnabled — off genuinely means off, even under concurrency.
    private val lock = Any()
    private val sockets = linkedMapOf<String, SocketDef>()

    fun register(def: SocketDef) {
        validateSocket(def)
        synchronized(lock) {
            require(!sockets.containsKey(def.id)) { "socket id '${def.id}' is already registered" }
            sockets[def.id] = def
        }
    }

    fun unregister(id: String): Boolean = synchronized(lock) { sockets.remove(id) != null }

    fun get(id: String): SocketDef? = synchronized(lock) { sockets[id] }

    fun all(): List<SocketDef> = synchronized(lock) { sockets.values.toList() }

    fun setEnabled(id: String, enabled: Boolean): Boolean = synchronized(lock) {
        val cur = sockets[id] ?: return false
        sockets[id] = cur.copy(enabled = enabled)
        true
    }

    /** Enabled sockets offering [capability]. */
    fun withCapability(capability: Int): List<SocketDef> =
        synchronized(lock) { sockets.values.filter { it.enabled && (it.capabilities and capability) != 0 } }

    fun clear() = synchronized(lock) { sockets.clear() }
}
