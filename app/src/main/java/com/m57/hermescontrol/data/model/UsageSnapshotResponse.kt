package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.ws.toAny
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Complete cumulative `session.usage` snapshot returned by Hermes Agent. */
data class UsageSnapshotResponse(
    val model: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null,
    val apiCalls: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val compressions: Int? = null,
    val contextUsed: Long? = null,
    val contextMax: Long? = null,
    val contextPercent: Int? = null,
    val contextSource: String? = null,
    val contextEstimated: Boolean? = null,
    val cacheHitPercent: Int? = null,
    val avgLatencySeconds: Double? = null,
    val avgTps: Double? = null,
    val activeSubagents: Int? = null,
    val devCreditsSpentMicros: Long? = null,
    val costUsd: Double? = null,
    val costStatus: String? = null,
    val creditsLines: List<String>? = null,
)

/** Merge an incremental snapshot without erasing values omitted by the backend. */
fun UsageSnapshotResponse.mergeWith(previous: UsageSnapshotResponse?): UsageSnapshotResponse =
    if (previous == null) {
        this
    } else {
        copy(
            model = model ?: previous.model,
            inputTokens = inputTokens ?: previous.inputTokens,
            outputTokens = outputTokens ?: previous.outputTokens,
            reasoningTokens = reasoningTokens ?: previous.reasoningTokens,
            promptTokens = promptTokens ?: previous.promptTokens,
            completionTokens = completionTokens ?: previous.completionTokens,
            totalTokens = totalTokens ?: previous.totalTokens,
            apiCalls = apiCalls ?: previous.apiCalls,
            cacheReadTokens = cacheReadTokens ?: previous.cacheReadTokens,
            cacheWriteTokens = cacheWriteTokens ?: previous.cacheWriteTokens,
            compressions = compressions ?: previous.compressions,
            contextUsed = contextUsed ?: previous.contextUsed,
            contextMax = contextMax ?: previous.contextMax,
            contextPercent = contextPercent ?: previous.contextPercent,
            contextSource = contextSource ?: previous.contextSource,
            contextEstimated = contextEstimated ?: previous.contextEstimated,
            cacheHitPercent = cacheHitPercent ?: previous.cacheHitPercent,
            avgLatencySeconds = avgLatencySeconds ?: previous.avgLatencySeconds,
            avgTps = avgTps ?: previous.avgTps,
            activeSubagents = activeSubagents ?: previous.activeSubagents,
            devCreditsSpentMicros = devCreditsSpentMicros ?: previous.devCreditsSpentMicros,
            costUsd = costUsd ?: previous.costUsd,
            costStatus = costStatus ?: previous.costStatus,
            creditsLines = creditsLines ?: previous.creditsLines,
        )
    }

/**
 * Usage attributable to one completed turn, derived from two cumulative snapshots.
 * Rolling/context fields are copied from the final snapshot rather than differenced.
 */
data class TurnUsage(
    val model: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null,
    val apiCalls: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val devCreditsSpentMicros: Long? = null,
    val costUsd: Double? = null,
    val compressionCountDelta: Int? = null,
    val avgTps: Double? = null,
    val avgLatencySeconds: Double? = null,
)

private fun Long?.deltaFrom(
    baseline: Long?,
    final: Long?,
): Long? = if (baseline != null && final != null && final >= baseline) final - baseline else null

private fun Double?.deltaFrom(
    baseline: Double?,
    final: Double?,
): Double? = if (baseline != null && final != null && final >= baseline) final - baseline else null

/** Derive per-turn cumulative counters without ever clamping counter resets. */
fun UsageSnapshotResponse.deltaFrom(baseline: UsageSnapshotResponse?): TurnUsage =
    TurnUsage(
        model = model,
        inputTokens = inputTokens.deltaFrom(baseline?.inputTokens, inputTokens),
        outputTokens = outputTokens.deltaFrom(baseline?.outputTokens, outputTokens),
        reasoningTokens = reasoningTokens.deltaFrom(baseline?.reasoningTokens, reasoningTokens),
        promptTokens = promptTokens.deltaFrom(baseline?.promptTokens, promptTokens),
        completionTokens = completionTokens.deltaFrom(baseline?.completionTokens, completionTokens),
        totalTokens = totalTokens.deltaFrom(baseline?.totalTokens, totalTokens),
        apiCalls = apiCalls.deltaFrom(baseline?.apiCalls, apiCalls),
        cacheReadTokens = cacheReadTokens.deltaFrom(baseline?.cacheReadTokens, cacheReadTokens),
        cacheWriteTokens = cacheWriteTokens.deltaFrom(baseline?.cacheWriteTokens, cacheWriteTokens),
        devCreditsSpentMicros = devCreditsSpentMicros.deltaFrom(baseline?.devCreditsSpentMicros, devCreditsSpentMicros),
        costUsd = costUsd.deltaFrom(baseline?.costUsd, costUsd),
        compressionCountDelta =
            compressions
                ?.let { final -> baseline?.compressions?.let { base -> if (final >= base) final - base else null } },
        avgTps = avgTps,
        avgLatencySeconds = avgLatencySeconds,
    )

/**
 * Parse the decoded JSON-RPC result of `session.usage`, push events, or message.complete.
 *
 * Handles direct snapshots and nested `{ "usage": { ... } }` envelopes. JSON numbers may
 * arrive as [Double] through [JsonRpcModels.toAny], or as [JsonPrimitive] from an RPC result.
 * Unknown and malformed optional fields are ignored; a non-map root returns null.
 */
fun parseUsageSnapshot(result: Any?): UsageSnapshotResponse? {
    fun asMap(value: Any?): Map<*, *>? =
        when (value) {
            is Map<*, *> -> value
            is JsonElement -> value.toAny() as? Map<*, *>
            else -> null
        }

    val rawMap = asMap(result) ?: return null
    val usageMap = asMap(rawMap["usage"]) ?: rawMap

    fun num(key: String): Long? =
        when (val value = usageMap[key]) {
            is Byte, is Short, is Int, is Long -> {
                (value as Number).toLong()
            }

            is Float, is Double -> {
                (value as Number)
                    .toDouble()
                    .takeIf { it.isFinite() && it % 1.0 == 0.0 && it >= Long.MIN_VALUE && it <= Long.MAX_VALUE }
                    ?.toLong()
            }

            is Number -> {
                value
                    .toDouble()
                    .takeIf { it.isFinite() && it % 1.0 == 0.0 && it >= Long.MIN_VALUE && it <= Long.MAX_VALUE }
                    ?.toLong()
            }

            is JsonPrimitive -> {
                value.longOrNull
                    ?: value.doubleOrNull
                        ?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it >= Long.MIN_VALUE && it <= Long.MAX_VALUE }
                        ?.toLong()
            }

            is String -> {
                value.toLongOrNull()
                    ?: value
                        .toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it >= Long.MIN_VALUE && it <= Long.MAX_VALUE }
                        ?.toLong()
            }

            else -> {
                null
            }
        }

    fun intNum(key: String): Int? = num(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    fun doubleNum(key: String): Double? =
        when (val value = usageMap[key]) {
            is Number -> value.toDouble()
            is JsonPrimitive -> value.doubleOrNull ?: value.longOrNull?.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() }

    fun string(key: String): String? =
        when (val value = usageMap[key]) {
            is String -> value
            is JsonPrimitive -> value.content
            else -> null
        }

    fun bool(key: String): Boolean? =
        when (val value = usageMap[key]) {
            is Boolean -> value
            is JsonPrimitive -> value.booleanOrNull
            is String -> value.toBooleanStrictOrNull()
            else -> null
        }

    fun strings(key: String): List<String>? =
        when (val value = usageMap[key]) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }
            is List<*> -> value.filterIsInstance<String>()
            is JsonElement -> (value.toAny() as? List<*>)?.filterIsInstance<String>()
            else -> null
        }

    return UsageSnapshotResponse(
        model = string("model"),
        inputTokens = num("input"),
        outputTokens = num("output"),
        reasoningTokens = num("reasoning"),
        promptTokens = num("prompt"),
        completionTokens = num("completion"),
        totalTokens = num("total"),
        apiCalls = num("calls"),
        cacheReadTokens = num("cache_read"),
        cacheWriteTokens = num("cache_write"),
        compressions = intNum("compressions"),
        contextUsed = num("context_used"),
        contextMax = num("context_max"),
        contextPercent = intNum("context_percent"),
        contextSource = string("context_source"),
        contextEstimated = bool("context_estimated"),
        cacheHitPercent = intNum("cache_hit_pct"),
        avgLatencySeconds = doubleNum("avg_latency_s"),
        avgTps = doubleNum("avg_tps"),
        activeSubagents = intNum("active_subagents"),
        devCreditsSpentMicros = num("dev_credits_spent_micros"),
        costUsd = doubleNum("cost_usd"),
        costStatus = string("cost_status"),
        creditsLines = strings("credits_lines"),
    )
}
