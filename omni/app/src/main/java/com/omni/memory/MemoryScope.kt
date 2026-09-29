package com.omni.memory

/**
 * Durable buckets for Helios memory. Local-first: the phone is the source of
 * truth, sync is a convenience, and nothing here ever requires the cloud.
 */
enum class MemoryScope {
    /** Compressed snapshots of long builds (games, apps, multi-session work). */
    PROJECT_STATE,
    /** User likes/dislikes and standing directives. */
    PREFERENCE,
    /** Facts about the user's world: devices, accounts, places, contacts. */
    WORLD_DETAIL,
    /** Things agents observed happening. Failed runs log here — never as lessons. */
    OBSERVATION,
    /** Validated procedures. Only validator-passing runs may write these. */
    PROCEDURE,
    /** In-flight task state for model-neutral handoff between AIs. */
    TASK_HANDOFF,
}
