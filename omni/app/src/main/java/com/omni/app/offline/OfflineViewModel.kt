package com.omni.app.offline

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.app.sockets.SocketStore
import com.omni.gateway.Caps
import com.omni.gateway.SocketKind
import com.omni.gateway.WellKnownSockets
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Per-tool download state shown in the Offline models screen. */
sealed interface ToolStatus {
    data object NotDownloaded : ToolStatus
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : ToolStatus
    data class Ready(val bytes: Long) : ToolStatus
    data class Failed(val reason: String) : ToolStatus
}

/** Display data for one LOCAL_TOOL catalog entry. */
data class OfflineTool(
    val id: String,
    val displayName: String,
    val capLabels: List<String>,
    val notes: String?,
)

/** Formats a byte count as B/KB/MB/GB with one decimal for the larger units. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private fun capLabels(caps: Int): List<String> {
    val out = mutableListOf<String>()
    if (caps and Caps.CHAT != 0) out += "CHAT"
    if (caps and Caps.VISION != 0) out += "VISION"
    if (caps and Caps.IMAGE_GEN != 0) out += "IMAGE_GEN"
    if (caps and Caps.VIDEO_GEN != 0) out += "VIDEO_GEN"
    if (caps and Caps.MUSIC_GEN != 0) out += "MUSIC_GEN"
    if (caps and Caps.TTS != 0) out += "TTS"
    if (caps and Caps.STT != 0) out += "STT"
    if (caps and Caps.CODE != 0) out += "CODE"
    if (caps and Caps.DEVICE != 0) out += "DEVICE"
    return out
}

/**
 * Download manager for on-device (LOCAL_TOOL) model files.
 *
 * - Tool list comes from the shared catalog ([WellKnownSockets.defaults()] filtered to
 *   [SocketKind.LOCAL_TOOL]); this ViewModel never edits the catalog.
 * - Files land in app-private storage: `filesDir/models/<socketId>/<filename>`.
 *   No new permissions: INTERNET is already declared in the manifest.
 * - None of the current LOCAL_TOOL catalog entries ship a model URL, so the screen
 *   shows a "paste model URL" field per row instead of inventing download URLs.
 * - After a successful download the socket is registered (or re-registered) in the
 *   [SocketStore.registry] the app already owns, with kind=LOCAL_TOOL and
 *   installPath set to the downloaded file's absolute path, following the
 *   validateSocket rules (installPath + toolVersion non-blank).
 */
class OfflineViewModel(
    appContext: Context,
    private val socketStore: SocketStore,
) : ViewModel() {

    private val ctx: Context = appContext.applicationContext
    private val modelsDir = File(ctx.filesDir, "models")

    val tools = mutableStateListOf<OfflineTool>()
    val statuses = mutableStateMapOf<String, ToolStatus>()
    var storageBytes by mutableStateOf(0L)
        private set
    val urlErrors = mutableStateMapOf<String, String>()

    private val cancelFlags = mutableMapOf<String, AtomicBoolean>()

    init {
        val catalog = WellKnownSockets.defaults()
            .filter { it.kind == SocketKind.LOCAL_TOOL }
        tools.addAll(
            catalog.map {
                OfflineTool(
                    id = it.id,
                    displayName = it.displayName,
                    capLabels = capLabels(it.capabilities),
                    notes = it.notes,
                )
            },
        )
        refresh()
    }

    /** Re-scan app-private model storage and update statuses + totals. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val scanned = tools.associate { tool ->
                val dir = File(modelsDir, tool.id)
                val status = if (statuses[tool.id] is ToolStatus.Downloading) {
                    statuses[tool.id]!!
                } else {
                    val bytes = if (dir.isDirectory) {
                        dir.listFiles()?.sumOf { it.length() } ?: 0L
                    } else 0L
                    if (bytes > 0) ToolStatus.Ready(bytes) else ToolStatus.NotDownloaded
                }
                tool.id to status
            }
            val total = modelsDir.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
            withContext(Dispatchers.Main) {
                statuses.putAll(scanned)
                storageBytes = total
            }
        }
    }

    fun setUrlError(toolId: String, message: String?) {
        if (message == null) urlErrors.remove(toolId) else urlErrors[toolId] = message
    }

    /** Starts a streaming download for [toolId] from [rawUrl]. Cancellable via [cancelDownload]. */
    fun startDownload(toolId: String, rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            setUrlError(toolId, "Enter a full http(s) model file URL.")
            return
        }
        if (statuses[toolId] is ToolStatus.Downloading) return
        setUrlError(toolId, null)

        val cancel = AtomicBoolean(false)
        cancelFlags[toolId] = cancel
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(modelsDir, toolId).apply { mkdirs() }
            val target = File(dir, filenameFor(url, toolId))
            var connection: HttpURLConnection? = null
            var cancelled = false
            try {
                withContext(Dispatchers.Main) {
                    statuses[toolId] = ToolStatus.Downloading(0, -1)
                }
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "HeliosOfflineDownloader/1.0")
                }
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("Server returned HTTP $code")
                val total = connection.contentLengthLong // -1 when unknown; handled in UI
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            if (cancel.get()) {
                                cancelled = true
                                break
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            withContext(Dispatchers.Main) {
                                statuses[toolId] = ToolStatus.Downloading(read, total)
                            }
                        }
                    }
                }
                if (cancelled) {
                    target.delete()
                    withContext(Dispatchers.Main) { statuses[toolId] = ToolStatus.NotDownloaded }
                } else {
                    withContext(Dispatchers.Main) { onDownloadFinished(toolId, target) }
                }
            } catch (e: Exception) {
                target.delete() // never leave a partial file behind
                withContext(Dispatchers.Main) {
                    statuses[toolId] = ToolStatus.Failed(
                        when (e) {
                            is java.net.MalformedURLException -> "Bad URL: ${e.message}"
                            is java.net.UnknownHostException -> "Could not reach host."
                            is java.net.SocketTimeoutException -> "Connection timed out."
                            else -> "Download failed: ${e.message ?: e.javaClass.simpleName}"
                        },
                    )
                }
            } finally {
                connection?.disconnect()
                cancelFlags.remove(toolId)
                refreshStorageOnly()
            }
        }
    }

    /** Requests cancellation; the read loop checks the flag and deletes the partial file. */
    fun cancelDownload(toolId: String) {
        cancelFlags[toolId]?.set(true)
    }

    /** Deletes a downloaded model file/dir and unregisters the socket from the registry. */
    fun deleteModel(toolId: String) {
        if (statuses[toolId] is ToolStatus.Downloading) return // cancel first; delete would race
        viewModelScope.launch(Dispatchers.IO) {
            cancelFlags[toolId]?.set(true)
            val dir = File(modelsDir, toolId)
            dir.deleteRecursively()
            withContext(Dispatchers.Main) {
                socketStore.clearLocalTool(toolId) // drops persisted path, turns socket off
                statuses[toolId] = ToolStatus.NotDownloaded
                refresh()
            }
        }
    }

    /**
     * Called on the main thread after a download completes cleanly.
     * Registers (or re-registers, since SocketRegistry.register rejects duplicates)
     * a LOCAL_TOOL SocketDef copied from the catalog entry, with installPath
     * pointing at the downloaded file and enabled=true.
     */
    private fun onDownloadFinished(toolId: String, file: File) {
        val catalog = WellKnownSockets.defaults().first { it.id == toolId }
        val note = buildString {
            catalog.notes?.let { append(it).append(' ') }
            append("Model file downloaded on device (${formatBytes(file.length())}).")
        }
        val def = catalog.copy(
            installPath = file.absolutePath,
            notes = note,
            enabled = socketStore.registry.get(toolId)?.enabled ?: true,
        )
        socketStore.registerLocalTool(def) // validateSocket runs inside; installPath persists
        statuses[toolId] = ToolStatus.Ready(file.length())
        refresh()
    }

    private fun refreshStorageOnly() {
        viewModelScope.launch(Dispatchers.IO) {
            val total = modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            withContext(Dispatchers.Main) { storageBytes = total }
        }
    }

    private fun filenameFor(url: String, toolId: String): String {
        val raw = url.substringBefore('?').substringAfterLast('/').trim()
        val clean = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return if (clean.isNotBlank() && clean != "." && clean != "..") clean else "$toolId-model.bin"
    }
}
