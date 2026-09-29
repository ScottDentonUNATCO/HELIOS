package com.omni.memory

import org.junit.Assert.*
import org.junit.Test

class SecretScrubberTest {
    @Test fun `vault references masked`() {
        assertEquals(
            "use vault:[REDACTED] now",
            SecretScrubber.scrub("use vault:openai-key-99 now"),
        )
    }

    @Test fun `sk keys masked`() {
        assertEquals(
            "key=sk-[REDACTED] ok",
            SecretScrubber.scrub("key=sk-abcDEF123-_xyz ok"),
        )
    }

    @Test fun `slack style tokens masked`() {
        assertEquals("[REDACTED]", SecretScrubber.scrub("xoxb-1234-abcd"))
        assertEquals("[REDACTED]", SecretScrubber.scrub("xoxa-999-zzz"))
        assertEquals("[REDACTED]", SecretScrubber.scrub("xoxp-1-2-3"))
    }

    @Test fun `clean text untouched`() {
        val clean = "The vault door is heavy; ask Kojima about snakes."
        assertEquals(clean, SecretScrubber.scrub(clean))
    }

    @Test fun `multiple secrets in one string all masked`() {
        val out = SecretScrubber.scrub("a vault:one b sk-two_3 c xoxb-4")
        assertFalse(out.contains("vault:one"))
        assertFalse(out.contains("sk-two_3"))
        assertFalse(out.contains("xoxb-4"))
        assertTrue(out.contains("vault:[REDACTED]"))
        assertTrue(out.contains("sk-[REDACTED]"))
    }
}
