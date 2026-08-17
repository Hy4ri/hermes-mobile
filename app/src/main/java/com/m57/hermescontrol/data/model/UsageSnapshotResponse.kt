package com.m57.hermescontrol.data.model

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
)

/**
 * Parse the decoded JSON-RPC result of `session.usage` or `session.usage` push event payload.
 *
 * Handles both direct usage dict (from RPC response) and nested `{ "usage": { ... } }`
 * (from push event payload). The WS event parser decodes JSON numbers as [Double]
 * (`JsonRpcModels.toAny`), so every count is read through [Number].
 * Returns null when the payload is not a map (error/malformed response) — callers keep the last known value.
 */
fun parseUsageSnapshot(result: Any?): UsageSnapshotResponse? {
    val map = result as? Map<*, *> ?: return null
    val usageMap = (map["usage"] as? Map<*, *>) ?: map
    return UsageSnapshotResponse(
        compressions = (usageMap["compressions"] as? Number)?.toInt(),
        contextUsed = (usageMap["context_used"] as? Number)?.toLong(),
        contextMax = (usageMap["context_max"] as? Number)?.toLong(),
        totalTokens = (usageMap["total"] as? Number)?.toLong(),
    )
}
