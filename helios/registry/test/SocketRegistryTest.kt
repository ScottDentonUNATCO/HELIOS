package com.omni.gateway

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SocketRegistryTest {
    private lateinit var reg: SocketRegistry

    @Before fun setup() { reg = SocketRegistry() }

    private fun apiKey(id: String = "openai") = SocketDef(
        id, "OpenAI", SocketKind.API_KEY, Caps.CHAT,
        baseUrl = "https://api.openai.com/v1", apiKeyRef = "vault:openai")

    @Test fun `register valid API_KEY socket`() {
        reg.register(apiKey())
        assertEquals("OpenAI", reg.get("openai")!!.displayName)
    }

    @Test fun `duplicate id rejected`() {
        reg.register(apiKey())
        try { reg.register(apiKey()); fail("expected") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("already registered")) }
    }

    @Test fun `blank id rejected`() {
        try { reg.register(apiKey("").copy(id = "")); fail("expected") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("id")) }
    }

    @Test fun `API_KEY needs baseUrl`() {
        try { reg.register(apiKey().copy(baseUrl = null)); fail("expected") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("baseUrl")) }
    }

    @Test fun `API_KEY needs apiKeyRef`() {
        try { reg.register(apiKey().copy(apiKeyRef = " ")); fail("expected") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("apiKeyRef")) }
    }

    @Test fun `OAUTH needs accountId`() {
        val bad = SocketDef("m", "M", SocketKind.OAUTH, Caps.CHAT, accountId = null)
        try { reg.register(bad); fail("expected") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("accountId")) }
    }

    @Test fun `OAUTH valid with accountId`() {
        reg.register(SocketDef("m", "M", SocketKind.OAUTH, Caps.CHAT, accountId = "meta"))
        assertNotNull(reg.get("m"))
    }

    @Test fun `LOCAL_TOOL needs installPath and toolVersion`() {
        val noPath = SocketDef("t", "T", SocketKind.LOCAL_TOOL, Caps.CHAT, toolVersion = "1")
        val noVer = SocketDef("t", "T", SocketKind.LOCAL_TOOL, Caps.CHAT, installPath = "files/x")
        try { reg.register(noPath); fail("expected") } catch (e: IllegalArgumentException) {}
        try { reg.register(noVer); fail("expected") } catch (e: IllegalArgumentException) {}
    }

    @Test fun `LOCAL_TOOL valid with path and version`() {
        reg.register(SocketDef("llama", "llama.cpp", SocketKind.LOCAL_TOOL, Caps.CHAT,
            installPath = "files/llamacpp", toolVersion = "bundled"))
        assertNotNull(reg.get("llama"))
    }

    @Test fun `CUSTOM needs baseUrl and authScheme`() {
        val bad = SocketDef("c", "C", SocketKind.CUSTOM, Caps.CHAT, baseUrl = "https://x", authScheme = null)
        try { reg.register(bad); fail("expected") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("authScheme")) }
    }

    @Test fun `zero capabilities rejected`() {
        try { reg.register(apiKey().copy(capabilities = 0)); fail("expected") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("capability")) }
    }

    @Test fun `unknown capability bits rejected`() {
        try { reg.register(apiKey().copy(capabilities = 1 shl 30)); fail("expected") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("capability")) }
    }

    @Test fun `unregister removes and reports`() {
        reg.register(apiKey())
        assertTrue(reg.unregister("openai"))
        assertNull(reg.get("openai"))
        assertFalse(reg.unregister("openai"))
    }

    @Test fun `withCapability filters by bit`() {
        reg.register(apiKey("a").copy(capabilities = Caps.CHAT))
        reg.register(apiKey("b").copy(capabilities = Caps.VIDEO_GEN))
        assertEquals(listOf("a"), reg.withCapability(Caps.CHAT).map { it.id })
        assertEquals(listOf("b"), reg.withCapability(Caps.VIDEO_GEN).map { it.id })
        assertTrue(reg.withCapability(Caps.MUSIC_GEN).isEmpty())
    }

    @Test fun `multi-capability socket matches each`() {
        reg.register(apiKey("m").copy(capabilities = Caps.CHAT or Caps.VIDEO_GEN))
        assertEquals(1, reg.withCapability(Caps.CHAT).size)
        assertEquals(1, reg.withCapability(Caps.VIDEO_GEN).size)
    }

    @Test fun `off genuinely means off - disabled socket never routes`() {
        reg.register(apiKey())
        assertTrue(reg.setEnabled("openai", false))
        assertTrue(reg.withCapability(Caps.CHAT).isEmpty())
        assertTrue(reg.get("openai")!!.enabled == false)
        reg.setEnabled("openai", true)
        assertEquals(1, reg.withCapability(Caps.CHAT).size)
    }

    @Test fun `setEnabled unknown id returns false`() {
        assertFalse(reg.setEnabled("nope", false))
    }

    @Test fun `all preserves registration order`() {
        reg.register(apiKey("b")); reg.register(apiKey("a"))
        assertEquals(listOf("b", "a"), reg.all().map { it.id })
    }
}

class WellKnownSocketsTest {
    @Test fun `catalog ids are unique`() {
        val ids = WellKnownSockets.defaults().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `every catalog entry passes validation`() {
        val reg = SocketRegistry()
        for (def in WellKnownSockets.defaults()) reg.register(def)
        assertTrue(reg.all().isNotEmpty())
    }

    @Test fun `catalog covers chat video music tts stt`() {
        val reg = SocketRegistry()
        WellKnownSockets.defaults().forEach { reg.register(it) }
        for (cap in listOf(Caps.CHAT, Caps.VIDEO_GEN, Caps.MUSIC_GEN, Caps.TTS, Caps.STT, Caps.IMAGE_GEN)) {
            assertTrue("no socket for cap $cap", reg.withCapability(cap).isNotEmpty())
        }
    }

    @Test fun `catalog includes offline local tools`() {
        val reg = SocketRegistry()
        WellKnownSockets.defaults().forEach { reg.register(it) }
        val local = reg.all().filter { it.kind == SocketKind.LOCAL_TOOL && it.enabled }
        assertTrue(local.any { it.capabilities and Caps.CHAT != 0 })
        assertTrue(local.any { it.capabilities and Caps.STT != 0 })
        assertTrue(local.any { it.capabilities and Caps.TTS != 0 })
    }

    @Test fun `catalog includes oauth login slots`() {
        val oauth = WellKnownSockets.defaults().filter { it.kind == SocketKind.OAUTH }
        assertTrue(oauth.any { it.id.contains("gemini") })
        assertTrue(oauth.any { it.id.contains("meta") })
    }

    @Test fun `ibm quantum flagged as not-an-llm`() {
        val ibm = WellKnownSockets.defaults().first { it.id == "ibm-quantum" }
        assertTrue(ibm.notes!!.contains("NOT an LLM"))
        assertEquals(0, ibm.capabilities and Caps.CHAT)
    }

    @Test fun `custom slots exist for user endpoints`() {
        val custom = WellKnownSockets.defaults().filter { it.kind == SocketKind.CUSTOM }
        assertTrue(custom.size >= 3)
    }
}
