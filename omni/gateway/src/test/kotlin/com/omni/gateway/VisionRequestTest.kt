package com.omni.gateway

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Vision attachments: a [ChatRequest] carrying [VisionImage]s must serialize
 * to the OpenAI vision wire shape (content = text part + image_url data-URI
 * parts on the last user message), while text-only requests keep the exact
 * legacy string-content shape.
 */
class VisionRequestTest {

    private var lastBody: String = ""

    private fun client(): OpenAiCompatClient {
        val fake = Interceptor { chain ->
            val req = chain.request()
            val buf = Buffer()
            req.body?.writeTo(buf)
            lastBody = buf.readUtf8()
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("mock")
                .body(
                    """data: {"choices":[{"delta":{"content":"seen"}}]}

data: [DONE]

""".toResponseBody("text/event-stream".toMediaType()),
                )
                .build()
        }
        return OpenAiCompatClient(OkHttpClient.Builder().addInterceptor(fake).build())
    }

    private val config = ProviderConfig(id = "t", baseUrl = "https://mock/v1", apiKeyRef = "k")

    @Test fun `text-only request keeps legacy string content`() = runBlocking {
        client().streamChat(
            config, "k",
            ChatRequest("m", listOf(ChatMessage("user", "hi"))),
        ).toList()
        assertTrue(lastBody.contains(""""content":"hi""""))
        assertFalse(lastBody.contains("image_url"))
    }

    @Test fun `image attaches as data-uri part on the last user message`() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // fake PNG magic
        client().streamChat(
            config, "k",
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage("system", "sys"),
                    ChatMessage("user", "what is this?"),
                ),
                images = listOf(VisionImage("image/png", png)),
            ),
        ).toList()
        // image_url part exists with a data URI carrying the base64 payload
        assertTrue(lastBody.contains(""""type":"image_url""""))
        val expectedB64 = okio.ByteString.of(*png).base64()
        assertTrue(lastBody.contains("data:image/png;base64,$expectedB64"))
        // text part preserved alongside
        assertTrue(lastBody.contains(""""type":"text","text":"what is this?""""))
        // system message stayed a plain string-content message
        assertTrue(lastBody.contains(""""role":"system","content":"sys""""))
    }

    @Test fun `multiple images each become a part`() = runBlocking {
        client().streamChat(
            config, "k",
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", "compare")),
                images = listOf(
                    VisionImage("image/jpeg", byteArrayOf(1)),
                    VisionImage("image/jpeg", byteArrayOf(2)),
                ),
            ),
        ).toList()
        val count = lastBody.split("\"type\":\"image_url\"").size - 1
        assertTrue("two image_url parts, got $count", count == 2)
    }
}
