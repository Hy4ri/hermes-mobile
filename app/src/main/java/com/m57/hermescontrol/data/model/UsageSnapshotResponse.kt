package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.ws.toAny
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Minimal view of the `session.usage` WS RPC result or `session.usage` push event —
 * only the fields the context widget consumes today. The backend sends many more keys
 * (`input`/`output`/`total`/`calls`/`active_subagents`/`credits_lines` …);
 * parsing is key-by-key, so unmodelled keys are simply ignored.
 *
 * Verified backend shape (`tui_gateway/server.py` `_get_usage`): `compressions`
 * = `context_compressor.compression_count` (0 when never compressed, absent on
 * sessions with no live agent), `context_used` = current window tokens,
 * `context_max` = max context length.
 */
data class UsageSnapshotResponse(
    /** How many times this session has been context-compressed. */
    val compressions: Int? = null,
    /** Current prompt / context tokens used in the active window. */
    val contextUsed: Long? = null,
    /** Max context length in tokens. */
    val contextMax: Long? = null,
    /** Cumulative session total tokens. */
    val totalTokens: Long? = null,
    /** Rolling tokens-per-second generation speed. */
    val avgTps: Double? = null,
)

/**
 * Parse the decoded JSON-RPC result of `session.usage` or `session.usage` push event payload.
 *
 * Handles both direct usage dict (from RPC response) and nested `{ "usage": { ... } }`
 * (from push event payload). The WS event parser decodes JSON numbers as [Double]
 * (`JsonRpcModels.toAny`), so every count is read through [Number]. Also supports raw [JsonObject].
 * Returns null when the payload is not a map (error/malformed response) — callers keep the last known value.
 */
fun parseUsageSnapshot(result: Any?): UsageSnapshotResponse? {
    val rawMap =
        when (result) {
            is JsonElement -> result.toAny() as? Map<*, *>
            is Map<*, *> -> result
            else -> null
        } ?: return null
    val usageMap = (rawMap["usage"] as? Map<*, *>) ?: rawMap

    fun num(key: String): Long? =
        when (val value = usageMap[key]) {
            is Number -> value.toLong()
            is JsonPrimitive -> value.longOrNull ?: value.intOrNull?.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }

    fun intNum(key: String): Int? =
        when (val value = usageMap[key]) {
            is Number -> value.toInt()
            is JsonPrimitive -> value.intOrNull
            is String -> value.toIntOrNull()
            else -> null
        }

    fun doubleNum(key: String): Double? =
        when (val value = usageMap[key]) {
            is Number -> value.toDouble()
            is JsonPrimitive -> value.doubleOrNull ?: value.longOrNull?.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }

    return UsageSnapshotResponse(
        compressions = intNum("compressions"),
        contextUsed = num("context_used"),
        contextMax = num("context_max"),
        totalTokens = num("total"),
        avgTps = doubleNum("avg_tps"),
    )
}
