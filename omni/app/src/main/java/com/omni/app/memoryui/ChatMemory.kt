package com.omni.app.memoryui

import android.content.Context
import com.omni.memory.AdaptiveRanker
import com.omni.memory.InMemoryStore
import com.omni.memory.MemoryRecord
import com.omni.memory.MemoryScope
import com.omni.memory.StaleVersionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean
import java.io.File
import java.util.UUID

/**
 * File-backed memory for chat. Records live in an [InMemoryStore] and are
 * persisted as JSON lines in `filesDir/helios_memory.jsonl`.
 *
 * MemoryRecord is serialized field-by-field with the programmatic
 * kotlinx.serialization.json API (no compiler plugin), mirroring the
 * pattern in com.omni.memory.HandoffPacket.
 *
 * Threading: InMemoryStore is thread-safe. [recall] runs on Dispatchers.IO;
 * [persistTurn] is a plain blocking call — call it from Dispatchers.IO.
 */
class ChatMemory(appContext: Context) {

    private val filesDir: File = appContext.filesDir
    private val file = File(filesDir, FILE_NAME)
    private val store = InMemoryStore()

    init {
        loadFromFile()
    }

    /** All records, newest first (pinned not boosted here — the UI handles pin display). */
    fun allRecords(): List<MemoryRecord> =
        store.all().sortedByDescending { it.updatedAtMs }

    /** Ranked recall for a chat turn: search hits first, else top-K by adaptive rank. */
    suspend fun recall(query: String, k: Int = 3): List<MemoryRecord> =
        withContext(Dispatchers.IO) {
            val hits = store.search(query).take(k)
            if (hits.isNotEmpty()) hits
            else {
                val now = System.currentTimeMillis()
                store.topK(k) { r -> AdaptiveRanker.score(r, now, emptyMap()) }
            }
        }

    /**
     * Persist one chat turn as an OBSERVATION. put() auto-scrubs secrets from
     * the value, so raw secrets never reach storage. Returns false when the
     * file write failed (the record is still in the in-memory store).
     */
    fun persistTurn(user: String, assistant: String): Boolean {
        val now = System.currentTimeMillis()
        val record = MemoryRecord(
            id = UUID.randomUUID().toString(),
            scope = MemoryScope.OBSERVATION,
            key = user.trim().take(KEY_CHARS).ifBlank { "(empty)" },
            value = "Q: $user\nA: $assistant",
            salience = 0.5,
            createdAtMs = now,
            updatedAtMs = now,
            version = 1L,
            provenance = "chat",
        )
        store.put(record)
        return saveToFile()
    }

    /** Upsert a record (used by the memory screen: pin/unpin) and persist. */
    fun updateRecord(record: MemoryRecord): Boolean {
        store.put(record)
        return saveToFile()
    }

    /**
     * Controlled seeding seam for the game-maker module: inserts the NES
     * knowledge base into the SAME store backing chat. Idempotent
     * (guard-flag + versioned upsert), exactly once per knowledge version.
     * Called once at app start; after that, seeded records persist to
     * [FILE_NAME] like everything else. This is the only entry point the
     * game-maker gets to the store — no second memory store exists.
     */
    fun seedGameMakerKnowledge(appContext: Context): Int {
        val before = store.all().size
        com.omni.app.gamemaker.knowledge.KnowledgeSeeder.ensureSeeded(appContext, store)
        val added = store.all().size - before
        if (added > 0) saveToFile()
        return added
    }

    /** Delete a record and persist. Returns false if the id was unknown or the write failed. */
    fun deleteRecord(id: String): Boolean {
        val deleted = store.delete(id)
        if (!deleted) return false
        return saveToFile()
    }

    private fun loadFromFile() {
        if (!file.exists()) return
        file.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachLine
            try {
                val record = recordFromJson(trimmed)
                try {
                    store.put(record)
                } catch (e: StaleVersionException) {
                    // Duplicate id with an older-or-equal version already stored:
                    // keep the newer one already in the store.
                }
            } catch (e: Exception) {
                // A corrupt line must not sink the whole file; skip it.
            }
        }
    }

    /** Writes the whole store atomically; false when the write failed (callers surface it). */
    private fun saveToFile(): Boolean = runCatching {
        val tmp = File(filesDir, "$FILE_NAME.tmp")
        tmp.writeText(store.all().joinToString("\n") { recordToJson(it) })
        if (!tmp.renameTo(file)) {
            // renameTo can fail across weird filesystems; fall back to a direct write.
            file.writeText(tmp.readText())
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    companion object {
        const val FILE_NAME = "helios_memory.jsonl"
        const val KEY_CHARS = 40

        fun recordToJson(r: MemoryRecord): String = buildJsonObject {
            put("id", r.id)
            put("scope", r.scope.name)
            put("key", r.key)
            put("value", r.value)
            put("salience", r.salience)
            put("createdAtMs", r.createdAtMs)
            put("updatedAtMs", r.updatedAtMs)
            put("version", r.version)
            put("provenance", r.provenance)
            put("pinned", r.pinned)
        }.toString()

        fun recordFromJson(s: String): MemoryRecord {
            val o = Json.parseToJsonElement(s).jsonObject
            fun str(name: String): String =
                o[name]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("memory JSON missing '$name'")
            return MemoryRecord(
                id = str("id"),
                scope = MemoryScope.valueOf(str("scope")),
                key = str("key"),
                value = str("value"),
                salience = o["salience"]?.jsonPrimitive?.double
                    ?: throw IllegalArgumentException("memory JSON missing 'salience'"),
                createdAtMs = o["createdAtMs"]?.jsonPrimitive?.long
                    ?: throw IllegalArgumentException("memory JSON missing 'createdAtMs'"),
                updatedAtMs = o["updatedAtMs"]?.jsonPrimitive?.long
                    ?: throw IllegalArgumentException("memory JSON missing 'updatedAtMs'"),
                version = o["version"]?.jsonPrimitive?.long
                    ?: throw IllegalArgumentException("memory JSON missing 'version'"),
                provenance = str("provenance"),
                pinned = o["pinned"]?.jsonPrimitive?.boolean ?: false,
            )
        }
    }
}
