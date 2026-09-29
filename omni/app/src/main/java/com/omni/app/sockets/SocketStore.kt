package com.omni.app.sockets

import android.content.Context
import android.content.SharedPreferences
import com.omni.gateway.Caps
import com.omni.gateway.SocketDef
import com.omni.gateway.SocketKind
import com.omni.gateway.SocketRegistry
import com.omni.gateway.WellKnownSockets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Owns the [SocketRegistry] for the app and persists board state.
 *
 * - Seeds from [WellKnownSockets.defaults()] on first run.
 * - Enable/disable flags persist in plain SharedPreferences ("off" survives
 *   restarts, and the registry's `withCapability` routing excludes disabled
 *   sockets — off genuinely means off).
 * - User-added CUSTOM sockets persist (name + base URL in plain prefs; the
 *   key itself lives in [com.omni.app.vault.AndroidVault], never here).
 */
class SocketStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val registry = SocketRegistry()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        for (def in WellKnownSockets.defaults()) {
            registry.register(def)
            val enabled = prefs.getBoolean(KEY_ENABLED + def.id, def.enabled)
            if (enabled != def.enabled) registry.setEnabled(def.id, enabled)
            // A downloaded tool's on-device path survives restarts: re-apply
            // it to the freshly seeded catalog entry.
            val savedPath = prefs.getString(KEY_INSTALL_PATH + def.id, null)
            if (def.kind == SocketKind.LOCAL_TOOL &&
                !savedPath.isNullOrBlank() && savedPath != def.installPath
            ) {
                registry.unregister(def.id)
                runCatching { registry.register(def.copy(installPath = savedPath)) }
            }
        }
        for (custom in loadCustoms()) {
            runCatching { registry.register(custom) }
        }
    }

    fun all(): List<SocketDef> = registry.all()

    fun setEnabled(id: String, enabled: Boolean) {
        if (registry.setEnabled(id, enabled)) {
            prefs.edit().putBoolean(KEY_ENABLED + id, enabled).apply()
        }
    }

    /**
     * Registers (or re-registers) a LOCAL_TOOL socket whose model/tool file
     * was resolved on this device. The installPath is persisted and re-applied
     * in [init]; [clearLocalTool] drops it again. validateSocket throws on bad
     * shape.
     */
    fun registerLocalTool(def: SocketDef) {
        require(def.kind == SocketKind.LOCAL_TOOL) { "registerLocalTool is only for LOCAL_TOOL sockets" }
        registry.unregister(def.id)
        registry.register(def)
        if (!def.installPath.isNullOrBlank()) {
            prefs.edit().putString(KEY_INSTALL_PATH + def.id, def.installPath).apply()
        }
        setEnabled(def.id, true)
    }

    /**
     * Drops the device-resolved installPath (model file deleted) and turns
     * the socket off, so a restart can't resurrect a download that is gone.
     */
    fun clearLocalTool(id: String) {
        prefs.edit().remove(KEY_INSTALL_PATH + id).apply()
        if (registry.get(id) != null) setEnabled(id, false)
    }

    /** Vault ref for a socket's key. Built-ins already carry `vault:<id>`; slots without one get it. */
    fun keyRef(def: SocketDef): String = def.apiKeyRef ?: "vault:${def.id}"

    fun isUserCustom(id: String): Boolean = id in userCustomIds()

    /** Registers a user CUSTOM socket and persists it. Key (if any) is stored by the caller in the vault. */
    fun addCustom(name: String, baseUrl: String): SocketDef {
        val cleanUrl = baseUrl.trim()
        require(cleanUrl.isNotBlank()) { "base URL must not be blank" }
        val id = "custom-" + System.currentTimeMillis()
        val def = SocketDef(
            id = id,
            displayName = name.ifBlank { "Custom socket" },
            kind = SocketKind.CUSTOM,
            capabilities = Caps.CHAT,
            baseUrl = cleanUrl,
            apiKeyRef = "vault:$id",
            authScheme = "bearer",
            notes = "Added by user on device",
        )
        registry.register(def) // validateSocket throws on bad shape
        saveUserCustomIds(userCustomIds() + id)
        prefs.edit().putString(
            KEY_CUSTOM_DEF + id,
            buildJsonObject {
                put("name", def.displayName)
                put("baseUrl", cleanUrl)
            }.toString(),
        ).apply()
        return def
    }

    /** Removes a user-added CUSTOM socket. Built-in catalog entries cannot be removed. */
    fun removeCustom(id: String): Boolean {
        if (!isUserCustom(id)) return false
        val removed = registry.unregister(id)
        if (removed) {
            saveUserCustomIds(userCustomIds() - id)
            prefs.edit().remove(KEY_CUSTOM_DEF + id).apply()
        }
        return removed
    }

    private fun userCustomIds(): Set<String> =
        prefs.getString(KEY_USER_CUSTOMS, "").orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun saveUserCustomIds(ids: Set<String>) {
        prefs.edit().putString(KEY_USER_CUSTOMS, ids.joinToString(",")).apply()
    }

    private fun loadCustoms(): List<SocketDef> {
        val out = mutableListOf<SocketDef>()
        for (id in userCustomIds()) {
            val raw = prefs.getString(KEY_CUSTOM_DEF + id, null) ?: continue
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Custom socket"
            val baseUrl = obj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: continue
            val enabled = prefs.getBoolean(KEY_ENABLED + id, true)
            out.add(
                SocketDef(
                    id = id,
                    displayName = name,
                    kind = SocketKind.CUSTOM,
                    capabilities = Caps.CHAT,
                    baseUrl = baseUrl,
                    apiKeyRef = "vault:$id",
                    authScheme = "bearer",
                    notes = "Added by user on device",
                    enabled = enabled,
                ),
            )
        }
        return out
    }

    companion object {
        private const val PREFS = "omni_prefs"
        private const val KEY_ENABLED = "socket_enabled:"
        private const val KEY_INSTALL_PATH = "socket_install_path:"
        private const val KEY_USER_CUSTOMS = "socket_user_customs"
        private const val KEY_CUSTOM_DEF = "socket_custom_def:"
    }
}
