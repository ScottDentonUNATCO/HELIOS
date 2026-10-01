package com.omni.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Compact JSON codecs for [KeyInstance] and [PersistedKeyUsage], used by
 * [com.omni.app.sockets.SocketStore] to persist smart-router state in
 * SharedPreferences. Pure functions (no Android) so the round-trip is unit
 * testable on the JVM.
 *
 * Malformed input decodes to null rather than throwing — a corrupt prefs
 * entry must never crash the socket board.
 */
private val codecJson = Json { ignoreUnknownKeys = true }

fun KeyInstance.toJsonString(): String = buildJsonObject {
    put("id", id)
    put("providerId", providerId)
    put("label", label)
    put("vaultRef", vaultRef)
    dailyTokenBudget?.let { put("dailyTokenBudget", it) }
    monthlySpendBudgetUsd?.let { put("monthlySpendBudgetUsd", it) }
    put("enabled", enabled)
}.toString()

fun keyInstanceFromJson(raw: String): KeyInstance? = runCatching {
    val o = codecJson.parseToJsonElement(raw).jsonObject
    KeyInstance(
        id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null,
        providerId = o["providerId"]?.jsonPrimitive?.contentOrNull ?: return null,
        label = o["label"]?.jsonPrimitive?.contentOrNull ?: "",
        vaultRef = o["vaultRef"]?.jsonPrimitive?.contentOrNull ?: return null,
        dailyTokenBudget = o["dailyTokenBudget"]?.jsonPrimitive?.longOrNull,
        monthlySpendBudgetUsd = o["monthlySpendBudgetUsd"]?.jsonPrimitive?.doubleOrNull,
        enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
    )
}.getOrNull()

fun PersistedKeyUsage.toJsonString(): String = buildJsonObject {
    put("keyId", keyId)
    put("tokensToday", tokensToday)
    put("spendMonthUsd", spendMonthUsd)
    put("dayOfYear", dayOfYear)
    put("monthKey", monthKey)
}.toString()

fun persistedKeyUsageFromJson(raw: String): PersistedKeyUsage? = runCatching {
    val o = codecJson.parseToJsonElement(raw).jsonObject
    PersistedKeyUsage(
        keyId = o["keyId"]?.jsonPrimitive?.contentOrNull ?: return null,
        tokensToday = o["tokensToday"]?.jsonPrimitive?.longOrNull ?: 0L,
        spendMonthUsd = o["spendMonthUsd"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        dayOfYear = o["dayOfYear"]?.jsonPrimitive?.intOrNull ?: -1,
        monthKey = o["monthKey"]?.jsonPrimitive?.intOrNull ?: -1
    )
}.getOrNull()

/**
 * Builds a [PersistedKeyUsage] stamped with the current calendar markers, for
 * the app's usageListener to persist after each attributed call.
 */
fun persistedKeyUsageNow(keyId: String, tokensToday: Long, spendMonthUsd: Double): PersistedKeyUsage {
    val cal = java.util.Calendar.getInstance()
    return PersistedKeyUsage(
        keyId = keyId,
        tokensToday = tokensToday,
        spendMonthUsd = spendMonthUsd,
        dayOfYear = cal.get(java.util.Calendar.DAY_OF_YEAR),
        monthKey = cal.get(java.util.Calendar.YEAR) * 12 + cal.get(java.util.Calendar.MONTH)
    )
}
