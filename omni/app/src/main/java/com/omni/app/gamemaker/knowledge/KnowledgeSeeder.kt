package com.omni.app.gamemaker.knowledge

import android.content.Context
import com.omni.memory.MemoryStore
import com.omni.memory.StaleVersionException

/** Outcome of an [KnowledgeSeeder.ensureSeeded] call. */
sealed interface SeedResult {
    /** The guard flag was already set; nothing was written. */
    data object AlreadySeeded : SeedResult
    /** Records were written this run (duplicates impossible — see below). */
    data class Seeded(val inserted: Int, val alreadyPresent: Int) : SeedResult
}

/**
 * Seeds [NesKnowledgeBase.records] into a [MemoryStore] exactly once.
 *
 * Idempotency, two layers:
 * 1. A SharedPreferences guard flag stores the last seeded version; a run
 *    whose version is already covered returns [SeedResult.AlreadySeeded]
 *    without touching the store.
 * 2. Even if the flag is lost, [MemoryStore.put] is an upsert keyed by the
 *    record id and rejects stale versions — re-running can never duplicate a
 *    record, it only bumps versions when the knowledge version is newer.
 *
 * Designed to be called at first launch by the GameMaker hub; [isSeeded] and
 * [seedStatus] feed its status line. No permissions, no network, no other
 * dependencies — just the memory API and the Android framework.
 */
object KnowledgeSeeder {

    private const val PREFS = "nes_knowledge_seeder"
    private const val KEY_SEEDED_VERSION = "seeded_version"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Seed every knowledge record into [store] if this version hasn't been
     * seeded yet. Safe to call on every launch.
     */
    fun ensureSeeded(context: Context, store: MemoryStore): SeedResult {
        val seededVersion = prefs(context).getLong(KEY_SEEDED_VERSION, 0L)
        if (seededVersion >= NesKnowledgeBase.VERSION) return SeedResult.AlreadySeeded

        var inserted = 0
        var alreadyPresent = 0
        for (record in NesKnowledgeBase.records) {
            try {
                store.put(record)
                inserted++
            } catch (e: StaleVersionException) {
                // Same-or-newer version already stored under this id: not a
                // duplicate, just not newer. Skip without clobbering.
                alreadyPresent++
            }
        }
        prefs(context).edit()
            .putLong(KEY_SEEDED_VERSION, NesKnowledgeBase.VERSION)
            .apply()
        return SeedResult.Seeded(inserted, alreadyPresent)
    }

    /** True once the current knowledge version has been seeded. */
    fun isSeeded(context: Context): Boolean =
        prefs(context).getLong(KEY_SEEDED_VERSION, 0L) >= NesKnowledgeBase.VERSION

    /** One-line status for the GameMakerHub status line. */
    fun seedStatus(context: Context): String =
        if (isSeeded(context)) {
            "NES knowledge: ${NesKnowledgeBase.records.size} records seeded " +
                "(v${NesKnowledgeBase.VERSION})"
        } else {
            "NES knowledge: not seeded"
        }
}
