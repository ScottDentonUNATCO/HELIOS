package com.omni.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Model-neutral task handoff. Any AI — local llama.cpp, a cloud frontier
 * model, a future agent — can pick up EXACTLY where another stopped: the
 * opaque [stateJson] round-trips byte-for-byte through [toJson]/[fromJson],
 * so no model-specific framing survives the transfer. [memoryRefIds] point at
 * the [MemoryRecord]s that carry the task's durable context.
 *
 * Built with the programmatic kotlinx.serialization.json API only (no
 * compiler plugin), so the schema stays explicit and version-gated.
 */
data class HandoffPacket(
    val taskId: String,
    val schemaVersion: Int = SCHEMA_VERSION,
    val goal: String,
    val stateJson: String,
    val constraints: List<String>,
    val budgetTokens: Long,
    val attempts: Int,
    val memoryRefIds: List<String>,
    val createdBy: String,
    val createdAtMs: Long,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "unsupported schemaVersion=$schemaVersion (expected $SCHEMA_VERSION)"
        }
    }

    fun toJson(): String = buildJsonObject {
        put("taskId", taskId)
        put("schemaVersion", schemaVersion)
        put("goal", goal)
        put("stateJson", stateJson)
        put("constraints", buildJsonArray { constraints.forEach { add(it) } })
        put("budgetTokens", budgetTokens)
        put("attempts", attempts)
        put("memoryRefIds", buildJsonArray { memoryRefIds.forEach { add(it) } })
        put("createdBy", createdBy)
        put("createdAtMs", createdAtMs)
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(s: String): HandoffPacket {
            val o = Json.parseToJsonElement(s).jsonObject
            val v = o["schemaVersion"]?.jsonPrimitive?.int
                ?: throw IllegalArgumentException("handoff JSON missing schemaVersion")
            require(v == SCHEMA_VERSION) {
                "unsupported schemaVersion=$v (expected $SCHEMA_VERSION)"
            }
            return HandoffPacket(
                taskId = o.getValue("taskId").jsonPrimitive.content,
                schemaVersion = v,
                goal = o.getValue("goal").jsonPrimitive.content,
                stateJson = o.getValue("stateJson").jsonPrimitive.content,
                constraints = o.getValue("constraints").jsonArray.map { it.jsonPrimitive.content },
                budgetTokens = o.getValue("budgetTokens").jsonPrimitive.long,
                attempts = o.getValue("attempts").jsonPrimitive.int,
                memoryRefIds = o.getValue("memoryRefIds").jsonArray.map { it.jsonPrimitive.content },
                createdBy = o.getValue("createdBy").jsonPrimitive.content,
                createdAtMs = o.getValue("createdAtMs").jsonPrimitive.long,
            )
        }
    }
}
