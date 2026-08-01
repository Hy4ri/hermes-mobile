package com.m57.hermescontrol.data.model

/**
 * Minimal view of the `session.usage` WS RPC result — only the fields the
 * context widget consumes today. The backend sends many more keys
 * (`input`/`output`/`total`/`calls`/`active_subagents`/`credits_lines` …);
 * parsing is key-by-key, so unmodelled keys are simply ignored.
 *
 * Verified backend shape (`tui_gateway/server.py` `_get_usage`): `compressions`
 * = `context_compressor.compression_count` (0 when never compressed, absent on
 * sessions with no live agent).
 */
data class UsageSnapshotResponse(
    /** How many times this session has been context-compressed. */
    val compressions: Int?,
)

/**
 * Parse the decoded JSON-RPC result of `session.usage`.
 *
 * The WS event parser decodes JSON numbers as [Double] (`JsonRpcModels.toAny`),
 * so every count is read through [Number]. Returns null when the payload is
 * not a map (error/malformed response) — callers keep the last known value.
 */
fun parseUsageSnapshot(result: Any?): UsageSnapshotResponse? {
    val map = result as? Map<*, *> ?: return null
    return UsageSnapshotResponse(
        compressions = (map["compressions"] as? Number)?.toInt(),
    )
}
