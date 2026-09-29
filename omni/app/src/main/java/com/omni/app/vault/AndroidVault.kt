package com.omni.app.vault

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.omni.gateway.CredentialStore

/**
 * Android Keystore-backed [CredentialStore] for Phase 0.
 *
 * API keys live in EncryptedSharedPreferences ("omni_vault"), encrypted with a
 * MasterKey that prefers StrongBox hardware backing and falls back to the
 * regular TEE keystore on devices without StrongBox. The base URL is not
 * secret, so it lives in plain SharedPreferences ("omni_prefs").
 */
class AndroidVault(context: Context) : CredentialStore {

    private val appContext: Context = context.applicationContext

    private val encrypted: SharedPreferences by lazy {
        try {
            createEncryptedPrefs(buildMasterKey(strongBox = true))
        } catch (t: Throwable) {
            // Device lacks StrongBox (or keystore hiccup) — fall back to TEE-backed key.
            try {
                createEncryptedPrefs(buildMasterKey(strongBox = false))
            } catch (t2: Throwable) {
                // Last resort: the vault must NEVER crash the app. Keys are
                // stored unencrypted in this degraded mode.
                appContext.getSharedPreferences(VAULT_NAME + "_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private val plain: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun buildMasterKey(strongBox: Boolean): MasterKey =
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(strongBox)
            .build()

    private fun createEncryptedPrefs(masterKey: MasterKey): SharedPreferences =
        EncryptedSharedPreferences.create(
            appContext,
            VAULT_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    fun saveApiKey(ref: String, key: String) {
        encrypted.edit().putString("key:$ref", key).apply()
    }

    override fun apiKey(ref: String): String? =
        encrypted.getString("key:$ref", null)

    fun saveBaseUrl(url: String) {
        plain.edit().putString(KEY_BASE_URL, url).apply()
    }

    fun baseUrl(): String? =
        plain.getString(KEY_BASE_URL, null)

    companion object {
        private const val VAULT_NAME = "omni_vault"
        private const val PREFS_NAME = "omni_prefs"
        private const val KEY_BASE_URL = "base_url"
    }
}
