package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.model.SubagentListResponse
import com.m57.hermescontrol.data.model.SubagentTailResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * WebSocket RPC repository for subagent operations: `subagent.list` and `subagent.tail` (issue #1089).
 */
object SubagentRepository {
    private const val TAG = "SubagentRepository"

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    /**
     * Request the current active subagent roster for a given [sessionId].
     *
     * Returns null if RPC fails or is unsupported by server.
     */
    suspend fun listSubagents(sessionId: String): SubagentListResponse? {
        if (sessionId.isBlank()) return null
        return try {
            val result =
                HermesWsClient
                    .request(
                        WsMethods.SUBAGENT_LIST,
                        mapOf("session_id" to sessionId),
                        suppressErrorEvent = true,
                    ).await()
            decode<SubagentListResponse>(result)
        } catch (e: Exception) {
            Log.w(TAG, "subagent.list request failed for session $sessionId: ${e.message}")
            null
        }
    }

    /**
     * Request a rolling live transcript tail for [subagentId].
     *
     * Supplies [sessionId] when available so gateway authority checks pass cleanly.
     * Throws or returns null if unavailable.
     */
    suspend fun tailSubagent(
        sessionId: String,
        subagentId: String,
        maxBytes: Int = 16384,
    ): SubagentTailResponse? {
        if (subagentId.isBlank()) return null
        val params =
            mutableMapOf<String, Any>(
                "subagent_id" to subagentId,
                "max_bytes" to maxBytes,
            )
        if (sessionId.isNotBlank()) {
            params["session_id"] = sessionId
        }
        val result =
            HermesWsClient
                .request(
                    WsMethods.SUBAGENT_TAIL,
                    params,
                    suppressErrorEvent = true,
                ).await()
        return decode<SubagentTailResponse>(result)
    }

    @Suppress("UNCHECKED_CAST")
    internal inline fun <reified T> decode(result: Any?): T? {
        if (result == null) return null
        val element: JsonElement =
            when (result) {
                is JsonElement -> {
                    result
                }

                is Map<*, *> -> {
                    anyToJsonElement(result)
                }

                is List<*> -> {
                    JsonArray(result.map { anyToJsonElement(it) })
                }

                else -> {
                    val str = result.toString()
                    try {
                        json.parseToJsonElement(str)
                    } catch (_: Exception) {
                        JsonPrimitive(str)
                    }
                }
            }
        return json.decodeFromJsonElement(serializer<T>(), element)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun anyToJsonElement(value: Any?): JsonElement =
        when (value) {
            null -> {
                JsonNull
            }

            is JsonElement -> {
                value
            }

            is Map<*, *> -> {
                JsonObject(
                    (value as Map<String, Any?>).mapValues { (_, v) -> anyToJsonElement(v) },
                )
            }

            is List<*> -> {
                JsonArray(value.map { anyToJsonElement(it) })
            }

            is String -> {
                JsonPrimitive(value)
            }

            is Boolean -> {
                JsonPrimitive(value)
            }

            is Number -> {
                JsonPrimitive(value)
            }

            else -> {
                JsonPrimitive(value.toString())
            }
        }
}
