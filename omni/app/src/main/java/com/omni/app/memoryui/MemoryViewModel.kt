package com.omni.app.memoryui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.memory.AdaptiveRanker
import com.omni.memory.MemoryCompressor
import com.omni.memory.MemoryRecord
import com.omni.memory.MemoryScope
import com.omni.memory.StaleVersionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Preview of a compression pass: counts only, never mutates the store. */
data class CompressionPreview(
    val kept: Int,
    val summaries: Int,
    val dropped: Int,
)

/**
 * Backing view-model for the memory screen. Takes a [ChatMemory] so the
 * screen and the chat layer share one store.
 */
class MemoryViewModel(private val memory: ChatMemory) : ViewModel() {

    var records by mutableStateOf(listOf<MemoryRecord>())
        private set
    var loading by mutableStateOf(false)
        private set
    var scopeFilter by mutableStateOf<MemoryScope?>(null)
    var searchText by mutableStateOf("")

    /** Last pin/delete failure, shown in the UI. Cleared with [clearNotice]. */
    var notice by mutableStateOf<String?>(null)
        private set

    fun clearNotice() {
        notice = null
    }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            records = withContext(Dispatchers.IO) { memory.allRecords() }
            loading = false
        }
    }

    fun filteredRecords(): List<MemoryRecord> {
        val scope = scopeFilter
        val q = searchText.trim()
        return records
            .filter { scope == null || it.scope == scope }
            .filter {
                q.isEmpty() ||
                    it.key.contains(q, ignoreCase = true) ||
                    it.value.contains(q, ignoreCase = true)
            }
            // Pinned first, then newest.
            .sortedWith(compareByDescending<MemoryRecord> { it.pinned }
                .thenByDescending { it.updatedAtMs })
    }

    fun countByScope(scope: MemoryScope): Int = records.count { it.scope == scope }

    /** Counts of what a compressor pass over the current records would produce. */
    fun compressionPreview(tokenBudget: Int = 2000): CompressionPreview {
        val now = System.currentTimeMillis()
        val result = MemoryCompressor().compress(
            records,
            tokenBudget,
            ranker = { r -> AdaptiveRanker.score(r, now, emptyMap()) },
        )
        return CompressionPreview(
            kept = result.kept.size,
            summaries = result.summaries.size,
            dropped = result.dropped,
        )
    }

    fun togglePin(record: MemoryRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val saved = memory.updateRecord(record.bump().copy(pinned = !record.pinned))
                if (!saved) {
                    withContext(Dispatchers.Main) {
                        notice = "Pin change wasn't saved — the memory file couldn't be written. " +
                            "Check device storage and try again."
                    }
                }
            } catch (e: StaleVersionException) {
                // Another writer moved the record first; refresh to converge.
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    notice = "Pin change failed (${e.javaClass.simpleName}): ${e.message ?: "no details"} — try again."
                }
            }
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun delete(record: MemoryRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val done = memory.deleteRecord(record.id)
                if (!done) {
                    withContext(Dispatchers.Main) {
                        notice = "Couldn't delete that memory — it may already be gone, or the " +
                            "memory file couldn't be written. Check device storage and try again."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    notice = "Delete failed (${e.javaClass.simpleName}): ${e.message ?: "no details"} — try again."
                }
            }
            withContext(Dispatchers.Main) { refresh() }
        }
    }
}
