package com.omni.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * FetchLink MVP — "paste a link, get a socket". Pure-Kotlin core:
 * link classification, pre-download inspection (license/size/requirements),
 * and a resumable downloader with publisher-checksum verification.
 *
 * Safety rules (enforced here, not just in the UI):
 * - Only fetches links the user explicitly supplies — the classifier never
 *   follows links found on pages.
 * - No credentialed URLs: userinfo (`user:pass@host`) is rejected.
 * - License is reported (code AND weights separately) BEFORE anything
 *   downloads; the UI gates the download on the user's explicit confirm.
 */
enum class FetchLinkKind {
    GITHUB_REPO,
    HUGGINGFACE_MODEL,
    DIRECT_FILE,
    DOCS_PAGE,
    UNKNOWN,
}

/**
 * What FetchLink learned about a link BEFORE downloading.
 * Null fields mean "unknown" — the UI must say so, never guess.
 */
data class LinkInfo(
    val kind: FetchLinkKind,
    val displayName: String,
    /** SPDX id or license name for the CODE, or null when unknown. */
    val codeLicense: String?,
    /** License for model WEIGHTS (often differs from code), or null. */
    val weightsLicense: String?,
    val sizeBytes: Long?,
    val requirements: String?,
    /** Resolved direct download URL, when one is known. Null = user picks. */
    val downloadUrl: String?,
    /** Human-readable caveats shown before the confirm button. */
    val notes: String,
)

/** Pure URL classification — no network. */
object LinkClassifier {

    fun classify(rawUrl: String): FetchLinkKind {
        val url = rawUrl.trim()
        if (url.isBlank()) return FetchLinkKind.UNKNOWN
        if (hasCredentials(url)) return FetchLinkKind.UNKNOWN
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return FetchLinkKind.UNKNOWN
        val host = hostOf(lower) ?: return FetchLinkKind.UNKNOWN
        if (host == "github.com" && githubRepoPath(lower) != null) return FetchLinkKind.GITHUB_REPO
        if (host == "huggingface.co" && hfModelPath(lower) != null) return FetchLinkKind.HUGGINGFACE_MODEL
        if (looksLikeFile(lower)) return FetchLinkKind.DIRECT_FILE
        if (host == "github.com" || host == "huggingface.co") return FetchLinkKind.DOCS_PAGE
        // A bare docs/blog page (no file extension, no repo shape).
        if (!looksLikeFile(lower)) return FetchLinkKind.DOCS_PAGE
        return FetchLinkKind.UNKNOWN
    }

    /** owner/repo from a github.com URL, or null (case preserved). */
    fun githubRepo(url: String): Pair<String, String>? = githubRepoPath(url.trim())

    /** "org/model" from a huggingface.co model URL, or null (case preserved). */
    fun huggingFaceModel(url: String): String? = hfModelPath(url.trim())

    // ------------------------------------------------------------------

    private fun hasCredentials(url: String): Boolean {
        // userinfo appears between :// and the first / or @ before the host.
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty() || afterScheme == url) return false
        val hostPart = afterScheme.substringBefore('/')
        return '@' in hostPart
    }

    private fun hostOf(lower: String): String? {
        val afterScheme = lower.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        return afterScheme.substringBefore('/').substringBefore(':').substringBefore('?')
    }

    /** Path after the host marker, matched case-insensitively but extracted from the original. */
    private fun hostPath(url: String, hostMarker: String): String? {
        val idx = url.lowercase().indexOf(hostMarker)
        return if (idx < 0) null else url.substring(idx + hostMarker.length)
    }

    private fun githubRepoPath(url: String): Pair<String, String>? {
        val path = (hostPath(url, "github.com/") ?: return null).substringBefore('?').trim('/')
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        // github.com/<owner>/<repo>[/(tree|blob|releases|...)/...]
        if (parts[0].length > 39 || parts[1].length > 100) return null
        if (!parts[0].all { it.isLetterOrDigit() || it == '-' }) return null
        val repo = parts[1].removeSuffix(".git")
        if (repo.isEmpty() || !repo.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) return null
        return parts[0] to repo
    }

    private fun hfModelPath(url: String): String? {
        val path = (hostPath(url, "huggingface.co/") ?: return null).substringBefore('?').trim('/')
        val parts = path.split('/').filter { it.isNotEmpty() }
        // huggingface.co/<org>/<model>[/blob|resolve|tree/...]
        if (parts.size < 2) return null
        if (parts.size > 2 && parts[2].lowercase() !in setOf("blob", "resolve", "tree", "discussions", "commits")) return null
        val id = "${parts[0]}/${parts[1]}"
        if (!id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == '/' }) return null
        return id
    }

    private val FILE_EXTENSIONS = setOf(
        ".zip", ".tar", ".gz", ".tgz", ".bz2", ".xz", ".7z", ".rar",
        ".gguf", ".bin", ".onnx", ".tflite", ".pt", ".pth", ".safetensors",
        ".apk", ".aar", ".jar", ".wasm", ".mp3", ".wav", ".ogg", ".flac",
        ".mp4", ".png", ".jpg", ".jpeg", ".pdf", ".json",
    )

    private fun looksLikeFile(lower: String): Boolean {
        val path = lower.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        return FILE_EXTENSIONS.any { last.endsWith(it) }
    }
}

/**
 * Pre-download inspection: resolves a classified link into a [LinkInfo]
 * with license (code AND weights), size, and requirements — shown BEFORE
 * the user confirms the download.
 *
 * API bases are injectable so tests run against a mock server; production
 * uses the real github.com / huggingface.co endpoints.
 */
class LinkInspector(
    private val http: OkHttpClient,
    private val githubApiBase: String = "https://api.github.com",
    private val hfApiBase: String = "https://huggingface.co/api/models",
    private val hfFileBase: String = "https://huggingface.co",
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun inspect(rawUrl: String): LinkInfo {
        val url = rawUrl.trim()
        return when (LinkClassifier.classify(url)) {
            FetchLinkKind.GITHUB_REPO -> inspectGithub(url)
            FetchLinkKind.HUGGINGFACE_MODEL -> inspectHuggingFace(url)
            FetchLinkKind.DIRECT_FILE -> inspectDirectFile(url)
            FetchLinkKind.DOCS_PAGE -> LinkInfo(
                kind = FetchLinkKind.DOCS_PAGE,
                displayName = url,
                codeLicense = null, weightsLicense = null, sizeBytes = null,
                requirements = null, downloadUrl = null,
                notes = "This looks like a documentation page, not a downloadable file. " +
                    "Find the actual file or repo link on the page and paste that instead — " +
                    "FetchLink never auto-follows links from pages.",
            )
            FetchLinkKind.UNKNOWN -> LinkInfo(
                kind = FetchLinkKind.UNKNOWN,
                displayName = url,
                codeLicense = null, weightsLicense = null, sizeBytes = null,
                requirements = null, downloadUrl = null,
                notes = "Could not identify this link (or it carried credentials, which are rejected). " +
                    "Paste a GitHub repo, Hugging Face model, or direct file URL.",
            )
        }
    }

    // ------------------------------------------------------------------
    // GitHub repo: license + size from the public API.
    // ------------------------------------------------------------------

    private fun inspectGithub(url: String): LinkInfo {
        val (owner, repo) = LinkClassifier.githubRepo(url)
            ?: return unknownOf(FetchLinkKind.GITHUB_REPO, url, "could not parse owner/repo")
        val api = "$githubApiBase/repos/$owner/$repo"
        val root = getJson(api) ?: return unknownOf(
            FetchLinkKind.GITHUB_REPO, url,
            "GitHub API did not answer for $owner/$repo — check the repo exists and the device is online.",
        )
        val licenseEl = root["license"]
        val license = (licenseEl as? kotlinx.serialization.json.JsonObject)
        val spdx = license?.get("spdx_id")?.jsonPrimitive?.contentOrNull
        val licenseName = license?.get("name")?.jsonPrimitive?.contentOrNull
        val codeLicense = when {
            spdx != null && spdx != "NOASSERTION" -> spdx
            !licenseName.isNullOrBlank() -> licenseName
            else -> null
        }
        val sizeKb = root["size"]?.jsonPrimitive?.longOrNull
        val defaultBranch = root["default_branch"]?.jsonPrimitive?.contentOrNull ?: "main"
        val description = root["description"]?.jsonPrimitive?.contentOrNull
        return LinkInfo(
            kind = FetchLinkKind.GITHUB_REPO,
            displayName = root["full_name"]?.jsonPrimitive?.contentOrNull ?: "$owner/$repo",
            codeLicense = codeLicense,
            weightsLicense = null, // a code repo: no separate weights license from this API
            sizeBytes = sizeKb?.let { it * 1024 },
            requirements = null,
            // Source archive of the default branch — an honest, real download.
            downloadUrl = "https://github.com/$owner/$repo/archive/refs/heads/$defaultBranch.zip",
            notes = buildString {
                if (!description.isNullOrBlank()) append(description).append(' ')
                append("GitHub reports the repo tree at ~${sizeKb ?: "?"} KB; the zip may differ. ")
                append("For a released binary, open the repo's Releases page and paste the asset link instead. ")
                if (codeLicense == null) append("No license found via the API — assume ALL RIGHTS RESERVED until you check the repo. ")
                append("Accepting a license here covers YOUR use of this code; it does not change what the license requires of you.")
            },
        )
    }

    // ------------------------------------------------------------------
    // Hugging Face model: weights license + sibling sizes from the model API.
    // ------------------------------------------------------------------

    private fun inspectHuggingFace(url: String): LinkInfo {
        val id = LinkClassifier.huggingFaceModel(url)
            ?: return unknownOf(FetchLinkKind.HUGGINGFACE_MODEL, url, "could not parse model id")
        val root = getJson("$hfApiBase/$id") ?: return unknownOf(
            FetchLinkKind.HUGGINGFACE_MODEL, url,
            "Hugging Face API did not answer for $id — check the model id and the device connection.",
        )
        val cardDataEl = root["cardData"]
        val cardData = (cardDataEl as? kotlinx.serialization.json.JsonObject)
        val weightsLicense = cardData?.get("license")?.jsonPrimitive?.contentOrNull
            ?: root["license"]?.jsonPrimitive?.contentOrNull
        val siblingsEl = root["siblings"]
        val siblings = (siblingsEl as? JsonArray) ?: JsonArray(emptyList())
        val files = siblings.mapNotNull { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val name = o["rfilename"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val size = o["size"]?.jsonPrimitive?.longOrNull
            name to size
        }
        // Prefer a single-file GGUF (the on-device story); else the largest file.
        val pick = files.firstOrNull { (n, _) -> n.endsWith(".gguf", ignoreCase = true) }
            ?: files.maxByOrNull { (_, s) -> s ?: -1 }
        val pipeline = root["pipeline_tag"]?.jsonPrimitive?.contentOrNull
        val totalBytes = files.mapNotNull { it.second }.sum().takeIf { it > 0 }
        return LinkInfo(
            kind = FetchLinkKind.HUGGINGFACE_MODEL,
            displayName = id,
            codeLicense = null, // HF model repos: code license is per-file; the API gives the model license
            weightsLicense = weightsLicense,
            sizeBytes = pick?.second ?: totalBytes,
            requirements = pipeline?.let { "pipeline: $it" },
            downloadUrl = pick?.let { (name, _) -> "$hfFileBase/$id/resolve/main/$name" },
            notes = buildString {
                append("Code license is NOT reported by this API — the model page may carry a separate code license; check it. ")
                if (weightsLicense == null) append("No weights license published — assume all rights reserved until you verify. ")
                else append("Weights license shown above applies to the MODEL FILES, not to any code. ")
                if (pick != null) append("Selected file: ${pick.first} (${formatSize(pick.second)}). ")
                append("Accepting a license here covers YOUR use; it does not change the license terms.")
            },
        )
    }

    // ------------------------------------------------------------------
    // Direct file: HEAD for size/type; license honestly unknown.
    // ------------------------------------------------------------------

    private fun inspectDirectFile(url: String): LinkInfo {
        val request = Request.Builder().url(url).head().build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return unknownOf(
                        FetchLinkKind.DIRECT_FILE, url,
                        "server returned HTTP ${resp.code} for HEAD — the file may not exist.",
                    )
                }
                val length = resp.header("Content-Length")?.toLongOrNull()
                val type = resp.header("Content-Type")
                LinkInfo(
                    kind = FetchLinkKind.DIRECT_FILE,
                    displayName = url.substringBefore('?').substringAfterLast('/').ifBlank { url },
                    codeLicense = null,
                    weightsLicense = null,
                    sizeBytes = length,
                    requirements = type?.let { "content-type: $it" },
                    downloadUrl = url,
                    notes = "Direct file link. NO license information is published at a bare file URL — " +
                        "only download this if you know you have the right to use it. " +
                        (if (length == null) "Size unknown until download starts. " else "") +
                        "Checksum is verified only when the publisher provides one alongside the file.",
                )
            }
        } catch (e: IOException) {
            unknownOf(FetchLinkKind.DIRECT_FILE, url, "network error: ${e.message}")
        }
    }

    // ------------------------------------------------------------------

    private fun getJson(url: String): kotlinx.serialization.json.JsonObject? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "HeliosFetchLink/1.0")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                runCatching { json.parseToJsonElement(resp.body!!.string()).jsonObject }.getOrNull()
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun unknownOf(kind: FetchLinkKind, url: String, why: String): LinkInfo = LinkInfo(
        kind = kind,
        displayName = url,
        codeLicense = null, weightsLicense = null, sizeBytes = null,
        requirements = null, downloadUrl = null,
        notes = why,
    )

    companion object {
        fun formatSize(bytes: Long?): String {
            if (bytes == null || bytes < 0) return "unknown size"
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "%.1f KB".format(kb)
            val mb = kb / 1024.0
            if (mb < 1024) return "%.1f MB".format(mb)
            return "%.2f GB".format(mb / 1024.0)
        }
    }
}

/** Outcome of a FetchLink download. */
data class FetchDownloadOutcome(
    /** Total bytes now on disk (resumed + new). */
    val bytesOnDisk: Long,
    /** VERIFIED (publisher checksum matched), NOT_PUBLISHED (no checksum found), or FAILED. */
    val checksum: String,
)

/**
 * Resumable downloader: if [target] already holds a prefix of the file and the
 * server honors Range, the download continues where it stopped instead of
 * restarting. Progress reports (bytesRead, totalBytes). [isCancelled] is polled
 * between buffer fills.
 *
 * Checksum: after the download, `<url>.sha256` is fetched; when the publisher
 * provides one and it matches, [FetchDownloadOutcome.checksum] is "VERIFIED".
 * When no checksum is published it is "NOT_PUBLISHED" (honest, not a failure).
 * A mismatch deletes the file and throws.
 */
class FetchLinkDownloader(private val http: OkHttpClient) {

    fun download(
        url: String,
        target: java.io.File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): FetchDownloadOutcome {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "refusing non-http(s) URL"
        }
        target.parentFile?.mkdirs()
        val existing = if (target.exists()) target.length() else 0L

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "HeliosFetchLink/1.0")
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .get()
            .build()

        try {
            http.newCall(request).execute().use { resp ->
                if (resp.code == 416) {
                    // Already complete (or server disagrees about length): verify what's here.
                    return finish(url, target, existing)
                }
                if (!resp.isSuccessful) {
                    throw IOException("server returned HTTP ${resp.code}")
                }
                val resumed = resp.code == 206 && existing > 0
                val remaining = resp.body!!.contentLength() // -1 when unknown
                val total = if (remaining >= 0) (if (resumed) existing else 0L) + remaining else -1L
                if (!resumed && existing > 0) {
                    // Server ignored Range: restart cleanly rather than appending garbage.
                    target.delete()
                }
                resp.body!!.byteStream().use { input ->
                    java.io.FileOutputStream(target, resumed).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read = if (resumed) existing else 0L
                        onProgress(read, total)
                        while (true) {
                            if (isCancelled()) throw InterruptedException("cancelled by user")
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            onProgress(read, total)
                        }
                    }
                }
                return finish(url, target, target.length())
            }
        } catch (e: InterruptedException) {
            throw IOException("download cancelled", e)
        }
    }

    private fun finish(url: String, target: java.io.File, bytes: Long): FetchDownloadOutcome {
        val checksum = verifyChecksum(url, target)
        if (checksum == "MISMATCH") {
            target.delete()
            throw IOException(
                "checksum MISMATCH — the published sha256 does not match the downloaded file; " +
                    "the file was deleted, not kept.",
            )
        }
        return FetchDownloadOutcome(bytesOnDisk = bytes, checksum = checksum)
    }

    /**
     * Fetches `<url>.sha256` (the common publisher convention). Returns
     * "VERIFIED", "NOT_PUBLISHED", or "MISMATCH".
     */
    fun verifyChecksum(url: String, target: java.io.File): String {
        val expected = try {
            val request = Request.Builder()
                .url("$url.sha256")
                .header("User-Agent", "HeliosFetchLink/1.0")
                .get()
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return "NOT_PUBLISHED"
                // Format is usually "<hex>  <filename>"; take the first hex token.
                resp.body!!.string().trim().split(Regex("\\s+")).firstOrNull()
                    ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }?.lowercase()
                    ?: return "NOT_PUBLISHED"
            }
        } catch (e: IOException) {
            return "NOT_PUBLISHED"
        }
        val actual = sha256Hex(target)
        return if (actual == expected) "VERIFIED" else "MISMATCH"
    }

    companion object {
        fun sha256Hex(file: java.io.File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
