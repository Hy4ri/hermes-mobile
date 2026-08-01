package com.m57.hermescontrol.data.model

/**
 * Live context-window occupancy from the `session.context_breakdown` WS RPC —
 * the same RPC the Hermes desktop app's status-bar meter uses.
 *
 * Unlike the cumulative `input_tokens` REST counter, [contextUsed] is the
 * live agent's actual prompt occupancy: the context compressor's
 * `last_prompt_tokens` (the last prompt actually sent to the model), falling
 * back to an estimate of the live system prompt + tools + (compacted)
 * history. It therefore DROPS after context compression, which the cumulative
 * counter never does (issue #756).
 *
 * All counts are nullable — the backend omits/zeroes them when there is no
 * live agent or the engine doesn't track per-window occupancy.
 */
data class ContextBreakdownResponse(
    /** Actual current prompt size in tokens, or null when unknown. */
    val contextUsed: Long?,
    /** The active model's full context window in tokens, or null. */
    val contextMax: Long?,
    /** Clamped 0-100 fill percentage, or null. */
    val contextPercent: Int?,
    /** Rough estimate of system prompt + tools + history, or null. */
    val estimatedTotal: Long?,
    val model: String?,
)

/**
 * Parse the decoded JSON-RPC result of `session.context_breakdown`.
 *
 * The WS event parser decodes JSON numbers as [Double] (`JsonRpcModels.toAny`),
 * so every count is read through [Number]. Returns null when the payload is
 * not a map (error/malformed response) — callers treat that as unknown and
 * keep the last known meter values.
 */
fun parseContextBreakdown(result: Any?): ContextBreakdownResponse? {
    val map = result as? Map<*, *> ?: return null

    fun num(key: String): Long? = (map[key] as? Number)?.toLong()

    return ContextBreakdownResponse(
        contextUsed = num("context_used"),
        contextMax = num("context_max"),
        contextPercent = (map["context_percent"] as? Number)?.toInt(),
        estimatedTotal = num("estimated_total"),
        model = map["model"] as? String,
    )
}
