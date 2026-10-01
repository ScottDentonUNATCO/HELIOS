package com.omni.gateway

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LinkClassifier (pure, no network) and LinkInspector (scripted HTTP via an
 * in-process OkHttp interceptor — the repo's established mock pattern, since
 * this sandbox blocks loopback TCP).
 */
class FetchLinkTest {

    // ------------------------------------------------------------------
    // LinkClassifier: pure URL classification
    // ------------------------------------------------------------------

    @Test fun `classifies github repo urls`() {
        assertEquals(FetchLinkKind.GITHUB_REPO, LinkClassifier.classify("https://github.com/ggml-org/llama.cpp"))
        assertEquals(FetchLinkKind.GITHUB_REPO, LinkClassifier.classify("https://github.com/ggml-org/llama.cpp/tree/master"))
        assertEquals(FetchLinkKind.GITHUB_REPO, LinkClassifier.classify("https://github.com/ggml-org/llama.cpp.git"))
        assertEquals("ggml-org" to "llama.cpp", LinkClassifier.githubRepo("https://github.com/ggml-org/llama.cpp"))
    }

    @Test fun `classifies huggingface model urls`() {
        assertEquals(FetchLinkKind.HUGGINGFACE_MODEL, LinkClassifier.classify("https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF"))
        assertEquals(
            FetchLinkKind.HUGGINGFACE_MODEL,
            LinkClassifier.classify("https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/blob/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"),
        )
        assertEquals("Qwen/Qwen2.5-0.5B-Instruct-GGUF", LinkClassifier.huggingFaceModel("https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF"))
    }

    @Test fun `classifies direct file urls`() {
        assertEquals(FetchLinkKind.DIRECT_FILE, LinkClassifier.classify("https://example.com/models/tiny.gguf"))
        assertEquals(FetchLinkKind.DIRECT_FILE, LinkClassifier.classify("https://example.com/tool.zip?dl=1"))
        assertEquals(FetchLinkKind.DIRECT_FILE, LinkClassifier.classify("https://example.com/app.apk"))
    }

    @Test fun `classifies docs pages`() {
        assertEquals(FetchLinkKind.DOCS_PAGE, LinkClassifier.classify("https://example.com/blog/how-to-run-llms"))
        assertEquals(FetchLinkKind.DOCS_PAGE, LinkClassifier.classify("https://docs.example.com/guide"))
    }

    @Test fun `rejects credentialed urls and garbage`() {
        assertEquals(FetchLinkKind.UNKNOWN, LinkClassifier.classify("https://user:pass@example.com/secret.zip"))
        assertEquals(FetchLinkKind.UNKNOWN, LinkClassifier.classify(""))
        assertEquals(FetchLinkKind.UNKNOWN, LinkClassifier.classify("not a url"))
        assertEquals(FetchLinkKind.UNKNOWN, LinkClassifier.classify("ftp://example.com/file.zip"))
    }

    // ------------------------------------------------------------------
    // LinkInspector: scripted API responses
    // ------------------------------------------------------------------

    private class Backend : Interceptor {
        val calls = mutableListOf<String>()
        @Volatile var handler: (method: String, path: String) -> Pair<Int, String> =
            { _, _ -> 404 to "{}" }

        fun newClient(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val path = req.url.encodedPath
            synchronized(calls) { calls += "${req.method} $path" }
            val (status, body) = handler(req.method, path)
            val builder = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(status).message("mock")
            if (req.method == "HEAD") {
                // HEAD: communicate via headers, like a real server.
                builder.header("Content-Length", "1234567")
                builder.header("Content-Type", "application/octet-stream")
                builder.body(ByteArray(0).toResponseBody("application/octet-stream".toMediaType()))
            } else {
                builder.body(body.toResponseBody("application/json".toMediaType()))
            }
            return builder.build()
        }
    }

    @Test fun `inspects github repo license and size`() {
        val backend = Backend()
        backend.handler = { method, path ->
            assertEquals("GET", method)
            assertEquals("/api/repos/ggml-org/llama.cpp", path)
            200 to """{
              "full_name":"ggml-org/llama.cpp","description":"LLM inference in C/C++",
              "license":{"key":"mit","name":"MIT License","spdx_id":"MIT"},
              "size":98765,"default_branch":"master"}""".trimIndent()
        }
        val inspector = LinkInspector(backend.newClient(), githubApiBase = "https://mock/api")
        val info = inspector.inspect("https://github.com/ggml-org/llama.cpp")
        assertEquals(FetchLinkKind.GITHUB_REPO, info.kind)
        assertEquals("MIT", info.codeLicense)
        assertNull(info.weightsLicense)
        assertEquals(98765L * 1024, info.sizeBytes)
        assertEquals("https://github.com/ggml-org/llama.cpp/archive/refs/heads/master.zip", info.downloadUrl)
    }

    @Test fun `inspects github repo with no license honestly`() {
        val backend = Backend()
        backend.handler = { _, _ ->
            200 to """{"full_name":"o/r","license":null,"size":10,"default_branch":"main"}"""
        }
        val inspector = LinkInspector(backend.newClient(), githubApiBase = "https://mock/api")
        val info = inspector.inspect("https://github.com/o/r")
        assertNull(info.codeLicense)
        assertTrue(info.notes.contains("No license found"))
        assertTrue(info.notes.contains("ALL RIGHTS RESERVED"))
    }

    @Test fun `inspects huggingface model weights license and picks gguf`() {
        val backend = Backend()
        backend.handler = { method, path ->
            assertEquals("GET", method)
            assertEquals("/api/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF", path)
            200 to """{
              "pipeline_tag":"text-generation",
              "cardData":{"license":"apache-2.0"},
              "siblings":[
                {"rfilename":"qwen2.5-0.5b-instruct-q4_k_m.gguf","size":400000000},
                {"rfilename":"README.md","size":5000}
              ]}""".trimIndent()
        }
        val inspector = LinkInspector(
            backend.newClient(),
            hfApiBase = "https://mock/api/models",
            hfFileBase = "https://mock/files",
        )
        val info = inspector.inspect("https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF")
        assertEquals(FetchLinkKind.HUGGINGFACE_MODEL, info.kind)
        assertEquals("apache-2.0", info.weightsLicense)
        assertNull(info.codeLicense)
        assertEquals(400000000L, info.sizeBytes)
        assertEquals(
            "https://mock/files/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            info.downloadUrl,
        )
        assertTrue(info.notes.contains("Code license is NOT reported"))
    }

    @Test fun `inspects direct file with head`() {
        val backend = Backend()
        // HEAD succeeds (200); the mock attaches Content-Length/Type headers.
        backend.handler = { method, _ ->
            if (method == "HEAD") 200 to "" else 404 to "{}"
        }
        val inspector = LinkInspector(backend.newClient())
        val info = inspector.inspect("https://example.com/models/tiny.gguf")
        assertEquals(FetchLinkKind.DIRECT_FILE, info.kind)
        assertEquals(1234567L, info.sizeBytes)
        assertNull(info.codeLicense)
        assertTrue(info.notes.contains("NO license information"))
        assertEquals(listOf("HEAD /models/tiny.gguf"), backend.calls)
    }

    @Test fun `docs page inspects with no network`() {
        val backend = Backend()
        val inspector = LinkInspector(backend.newClient())
        val info = inspector.inspect("https://example.com/blog/how-to-run-llms")
        assertEquals(FetchLinkKind.DOCS_PAGE, info.kind)
        assertNull(info.downloadUrl)
        assertTrue("no HTTP calls", backend.calls.isEmpty())
        assertTrue(info.notes.contains("never auto-follows"))
    }

    // ------------------------------------------------------------------
    // FetchLinkDownloader: resume + checksum, scripted at the HTTP layer
    // ------------------------------------------------------------------

    private class DownloadBackend : Interceptor {
        /** Full file content served by the mock origin. */
        var content: ByteArray = ByteArray(0)
        /** sha256 hex the mock "<url>.sha256" endpoint reports (null = 404). */
        var publishedSha: String? = null
        val calls = mutableListOf<String>()

        fun newClient(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val path = req.url.encodedPath
            synchronized(calls) { calls += "${req.method} $path range=${req.header("Range")}" }
            if (path.endsWith(".sha256")) {
                val sha = publishedSha
                return if (sha == null) {
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(404)
                        .message("mock").body("".toResponseBody("text/plain".toMediaType())).build()
                } else {
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200)
                        .message("mock")
                        .body("$sha  file.bin\n".toResponseBody("text/plain".toMediaType())).build()
                }
            }
            val range = req.header("Range")
            return if (range != null && range.startsWith("bytes=")) {
                val start = range.removePrefix("bytes=").substringBefore('-').toLong()
                val slice = content.copyOfRange(start.toInt(), content.size)
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(206)
                    .message("mock")
                    .header("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
                    .body(slice.toResponseBody("application/octet-stream".toMediaType())).build()
            } else {
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200)
                    .message("mock")
                    .body(content.toResponseBody("application/octet-stream".toMediaType())).build()
            }
        }
    }

    private fun tempFile(): java.io.File {
        val f = java.io.File.createTempFile("fetchlink-test", ".bin")
        f.deleteOnExit()
        return f
    }

    @Test fun `downloads full file with no published checksum`() {
        val backend = DownloadBackend()
        backend.content = ByteArray(100_000) { (it % 251).toByte() }
        val target = tempFile().also { it.delete() }
        val seen = mutableListOf<Pair<Long, Long>>()
        val outcome = FetchLinkDownloader(backend.newClient()).download(
            "https://example.com/file.bin", target,
            onProgress = { r, t -> seen += r to t },
        )
        assertEquals(100_000L, outcome.bytesOnDisk)
        assertEquals("NOT_PUBLISHED", outcome.checksum)
        assertEquals(backend.content.toList(), target.readBytes().toList())
        assertTrue("progress reported", seen.isNotEmpty())
        assertEquals(100_000L, seen.last().first)
    }

    @Test fun `resumes partial download with range`() {
        val backend = DownloadBackend()
        backend.content = ByteArray(50_000) { (it % 251).toByte() }
        val target = tempFile()
        target.writeBytes(backend.content.copyOfRange(0, 20_000)) // partial prefix on disk
        val outcome = FetchLinkDownloader(backend.newClient())
            .download("https://example.com/file.bin", target)
        assertEquals(50_000L, outcome.bytesOnDisk)
        assertEquals(backend.content.toList(), target.readBytes().toList())
        assertTrue("range requested", backend.calls.any { it.contains("range=bytes=20000-") })
    }

    @Test fun `verified checksum when publisher provides one`() {
        val backend = DownloadBackend()
        backend.content = ByteArray(10_000) { (it % 251).toByte() }
        val f = tempFile().also { it.delete() }
        f.writeBytes(backend.content)
        backend.publishedSha = FetchLinkDownloader.sha256Hex(f)
        val target = tempFile().also { it.delete() }
        val outcome = FetchLinkDownloader(backend.newClient())
            .download("https://example.com/file.bin", target)
        assertEquals("VERIFIED", outcome.checksum)
    }

    @Test fun `checksum mismatch deletes the file and throws`() {
        val backend = DownloadBackend()
        backend.content = ByteArray(10_000) { (it % 251).toByte() }
        backend.publishedSha = "0".repeat(64) // wrong on purpose
        val target = tempFile().also { it.delete() }
        try {
            FetchLinkDownloader(backend.newClient()).download("https://example.com/file.bin", target)
            org.junit.Assert.fail("expected checksum mismatch")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("MISMATCH"))
        }
        assertTrue("bad file deleted", !target.exists())
    }

    @Test fun `cancel aborts the download`() {
        val backend = DownloadBackend()
        backend.content = ByteArray(5_000_000) { (it % 251).toByte() }
        val target = tempFile().also { it.delete() }
        var calls = 0
        try {
            FetchLinkDownloader(backend.newClient()).download(
                "https://example.com/file.bin", target,
                isCancelled = { ++calls > 3 },
            )
            org.junit.Assert.fail("expected cancel")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("cancel", ignoreCase = true))
        }
    }
}
