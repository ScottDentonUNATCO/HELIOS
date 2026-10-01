package com.omni.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON round-trips for the smart-router persistence codecs (the exact
 * serialization [com.omni.app.sockets.SocketStore] writes to SharedPreferences).
 */
class KeyInstanceCodecTest {

    @Test
    fun `key instance round-trips with budgets`() {
        val inInstance = KeyInstance(
            id = "key-123",
            providerId = "openai",
            label = "Personal",
            vaultRef = "vault:openai:key:key-123",
            dailyTokenBudget = 50_000,
            monthlySpendBudgetUsd = 5.0,
            enabled = true
        )

        val out = keyInstanceFromJson(inInstance.toJsonString())

        assertEquals(inInstance, out)
    }

    @Test
    fun `key instance round-trips with null budgets and disabled`() {
        val inInstance = KeyInstance(
            id = "key-9",
            providerId = "deepseek",
            label = "Backup",
            vaultRef = "vault:deepseek",
            enabled = false
        )

        val out = keyInstanceFromJson(inInstance.toJsonString())

        assertEquals(inInstance, out)
        assertNull(out!!.dailyTokenBudget)
        assertNull(out.monthlySpendBudgetUsd)
        assertFalse(out.enabled)
    }

    @Test
    fun `malformed key instance json decodes to null`() {
        assertNull(keyInstanceFromJson("not json at all {{{"))
        assertNull(keyInstanceFromJson("{}"))
        assertNull(keyInstanceFromJson("""{"id":"x"}"""))
    }

    @Test
    fun `usage snapshot round-trips`() {
        val snap = PersistedKeyUsage(
            keyId = "key-123",
            tokensToday = 12_345,
            spendMonthUsd = 1.2345,
            dayOfYear = 274,
            monthKey = 2026 * 12 + 9
        )

        val out = persistedKeyUsageFromJson(snap.toJsonString())

        assertEquals(snap, out)
    }

    @Test
    fun `malformed usage json decodes to null`() {
        assertNull(persistedKeyUsageFromJson("garbage"))
        assertNull(persistedKeyUsageFromJson("{}"))
    }
}
