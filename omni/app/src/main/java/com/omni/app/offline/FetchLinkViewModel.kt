package com.omni.app.offline

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.app.sockets.SocketStore
import com.omni.gateway.Caps
import com.omni.gateway.FetchLinkDownloader
import com.omni.gateway.FetchLinkKind
import com.omni.gateway.LinkClassifier
import com.omni.gateway.LinkInfo
import com.omni.gateway.LinkInspector
import com.omni.gateway.SocketDef
import com.omni.gateway.SocketKind
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** FetchLink UI phases: inspect first, download only after the user confirms. */
enum class FetchPhase { IDLE, INSPECTING, INSPECTED, DOWNLOADING, DONE, FAILED }

/**
 * FetchLink MVP ViewModel: paste a link -> [inspectLink] classifies and shows
 * license (code AND weights), size, and requirements BEFORE anything
 * downloads -> [confirmAndDownload] runs only after the user taps confirm ->
 * resumable download with progress + publisher checksum -> registers the file
 * as a LOCAL_TOOL socket in the app registry.
 *
 * Safety: [LinkClassifier] rejects credentialed URLs; the inspector never
 * auto-follows links from pages; [confirmAndDownload] refuses to run until
 * [licenseAcknowledged] is true (the UI shows the license first).
 */
class FetchLinkViewModel(
    appContext: Context,
    private val socketStore: SocketStore,
) : ViewModel() {

    private val ctx: Context = appContext.applicationContext
    private val fetchDir = File(ctx.filesDir, "fetchlink")
    private val http = OkHttpClient()
    private val inspector = LinkInspector(http)
    private val downloader = FetchLinkDownloader(http)

    var linkInput by mutableStateOf("")
        private set
    var phase by mutableStateOf(FetchPhase.IDLE)
        private set
    var info by mutableStateOf<LinkInfo?>(null)
        private set
    var licenseAcknowledged by mutableStateOf(false)
        private set
    var progressBytes by mutableStateOf(0L)
        private set
    var progressTotal by mutableStateOf(-1L)
        private set
    var resultSocketId by mutableStateOf<String?>(null)
        private set
    var resultPath by mutableStateOf<String?>(null)
        private set
    var checksumStatus by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    @Volatile private var cancelled = false

    fun updateLinkInput(v: String) {
        linkInput = v
        if (phase == FetchPhase.FAILED || phase == FetchPhase.DONE) reset()
    }

    fun acknowledgeLicense(v: Boolean) {
        licenseAcknowledged = v
    }

    fun reset() {
        cancelled = true
        phase = FetchPhase.IDLE
        info = null
        licenseAcknowledged = false
        progressBytes = 0L
        progressTotal = -1L
        resultSocketId = null
        resultPath = null
        checksumStatus = null
        error = null
    }

    /** Classifies + inspects the pasted link. No download happens here. */
    fun inspectLink() {
        val url = linkInput.trim()
        if (url.isEmpty()) {
            error = "Paste a link first."
            phase = FetchPhase.FAILED
            return
        }
        if (LinkClassifier.classify(url) == FetchLinkKind.UNKNOWN &&
            url.contains('@')
        ) {
            error = "Credentialed URLs (user:password@host) are rejected — paste a public link."
            phase = FetchPhase.FAILED
            return
        }
        reset()
        linkInput = url
        phase = FetchPhase.INSPECTING
        viewModelScope.launch(Dispatchers.IO) {
            val found = try {
                inspector.inspect(url)
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (found == null) {
                    error = "Inspection failed — check the link and the device connection."
                    phase = FetchPhase.FAILED
                } else {
                    info = found
                    phase = FetchPhase.INSPECTED
                }
            }
        }
    }

    /**
     * Downloads the inspected link's [LinkInfo.downloadUrl] after the user
     * confirmed the license. Resumable (Range) with progress; verifies the
     * publisher checksum when one is published.
     */
    fun confirmAndDownload() {
        val current = info
        if (phase != FetchPhase.INSPECTED || current == null) {
            error = "Inspect a link first."
            return
        }
        if (!licenseAcknowledged) {
            error = "Read the license above and acknowledge it before downloading."
            return
        }
        val downloadUrl = current.downloadUrl
        if (downloadUrl.isNullOrBlank()) {
            error = "Nothing downloadable was identified — FetchLink never guesses a file URL."
            return
        }
        cancelled = false
        phase = FetchPhase.DOWNLOADING
        error = null
        viewModelScope.launch(Dispatchers.IO) {
            val target = File(fetchDir, filenameFor(downloadUrl))
            var lastPosted = -1L
            try {
                val outcome = downloader.download(
                    url = downloadUrl,
                    target = target,
                    onProgress = { read, total ->
                        // Throttle main-thread posts: 256 KB steps, not per buffer fill.
                        if (read - lastPosted >= 256 * 1024 || read == total) {
                            lastPosted = read
                            launch(Dispatchers.Main) {
                                progressBytes = read
                                progressTotal = total
                            }
                        }
                    },
                    isCancelled = { cancelled },
                )
                val socketId = registerSocket(current, target)
                withContext(Dispatchers.Main) {
                    resultSocketId = socketId
                    resultPath = target.absolutePath
                    checksumStatus = outcome.checksum
                    phase = FetchPhase.DONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = when {
                        cancelled -> "Download cancelled."
                        else -> "Download failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                    phase = FetchPhase.FAILED
                }
            }
        }
    }

    fun cancelDownload() {
        cancelled = true
    }

    /**
     * Registers the downloaded file as a LOCAL_TOOL socket. Capability is
     * INFERRED from the filename and labelled as a guess in the socket notes —
     * never asserted as known.
     */
    private fun registerSocket(linkInfo: LinkInfo, file: File): String {
        val (caps, capNote) = inferCaps(file.name)
        val base = slugify(linkInfo.displayName)
        var id = "fetchlink-$base"
        var n = 2
        while (socketStore.registry.get(id) != null) {
            id = "fetchlink-$base-$n"
            n++
        }
        val def = SocketDef(
            id = id,
            displayName = "FetchLink: ${linkInfo.displayName.take(48)}",
            kind = SocketKind.LOCAL_TOOL,
            capabilities = caps,
            installPath = file.absolutePath,
            toolVersion = "fetchlink-1",
            notes = buildString {
                append("Fetched via FetchLink from ${linkInfo.kind}. ")
                append("Capability $capNote. ")
                linkInfo.codeLicense?.let { append("Code license: $it. ") }
                linkInfo.weightsLicense?.let { append("Weights license: $it. ") }
                if (linkInfo.codeLicense == null && linkInfo.weightsLicense == null) {
                    append("No license was published — verify your right to use this file. ")
                }
                append("No on-device runtime is bundled for arbitrary files; this socket records the download.")
            },
        )
        socketStore.registerLocalTool(def)
        return id
    }

    /** Best-effort capability guess from the filename; always labelled a guess. */
    private fun inferCaps(fileName: String): Pair<Int, String> {
        val lower = fileName.lowercase(Locale.US)
        return when {
            lower.endsWith(".gguf") -> (Caps.CHAT or Caps.CODE) to
                "guessed CHAT+CODE from the .gguf extension — verify on the Sockets tab"
            lower.contains("whisper") -> Caps.STT to
                "guessed STT from the filename — verify on the Sockets tab"
            lower.contains("piper") || lower.contains("kokoro") || lower.contains("tts") ->
                Caps.TTS to "guessed TTS from the filename — verify on the Sockets tab"
            else -> Caps.CODE to
                "guessed from the filename only — set the real capability on the Sockets tab"
        }
    }

    private fun slugify(name: String): String {
        val s = name.substringAfterLast('/')
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(32)
        return s.ifBlank { "file" }
    }

    private fun filenameFor(url: String): String {
        val raw = url.substringBefore('?').substringAfterLast('/').trim()
        val clean = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return if (clean.isNotBlank() && clean != "." && clean != "..") clean else "fetchlink-file.bin"
    }
}
