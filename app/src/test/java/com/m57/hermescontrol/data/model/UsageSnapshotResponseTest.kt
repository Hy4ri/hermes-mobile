package com.m57.hermescontrol.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parser tests for the `session.usage` WS RPC result.
 *
 * The WS event parser (`JsonRpcModels.toAny`) decodes JSON numbers as Double,
 * so the parser reads counts through [Number] — tests use Double literals to
 * mirror the real wire shape.
 */
class UsageSnapshotResponseTest {
    private fun completePayload(): Map<String, Any?> =
        mapOf(
            "model" to "provider/model",
            "input" to 13_000L,
            "output" to 2_700L,
            "reasoning" to 1_100L,
            "prompt" to 48_000L,
            "completion" to 2_700L,
            "total" to 50_700L,
            "calls" to 5L,
            "compressions" to 2,
            "context_used" to 12_345L,
            "context_max" to 200_000L,
            "context_percent" to 6,
            "context_source" to "compressor",
            "context_estimated" to true,
            "cache_hit_pct" to 42,
            "cache_read" to 1_200L,
            "cache_write" to 300L,
            "avg_latency_s" to 0.75,
            "avg_tps" to 42.5,
            "active_subagents" to 1,
            "dev_credits_spent_micros" to 9_000L,
            "cost_usd" to 0.0125,
            "cost_status" to "estimated",
            "credits_lines" to listOf("line one", "line two"),
        )

    @Test
    fun `complete direct payload maps every usage field`() {
        val parsed = parseUsageSnapshot(completePayload())

        assertEquals("provider/model", parsed?.model)
        assertEquals(13_000L, parsed?.inputTokens)
        assertEquals(2_700L, parsed?.outputTokens)
        assertEquals(1_100L, parsed?.reasoningTokens)
        assertEquals(48_000L, parsed?.promptTokens)
        assertEquals(2_700L, parsed?.completionTokens)
        assertEquals(50_700L, parsed?.totalTokens)
        assertEquals(5L, parsed?.apiCalls)
        assertEquals(2, parsed?.compressions)
        assertEquals(12_345L, parsed?.contextUsed)
        assertEquals(200_000L, parsed?.contextMax)
        assertEquals(6, parsed?.contextPercent)
        assertEquals("compressor", parsed?.contextSource)
        assertEquals(true, parsed?.contextEstimated)
        assertEquals(42, parsed?.cacheHitPercent)
        assertEquals(1_200L, parsed?.cacheReadTokens)
        assertEquals(300L, parsed?.cacheWriteTokens)
        assertEquals(0.75, parsed?.avgLatencySeconds ?: 0.0, 0.001)
        assertEquals(42.5, parsed?.avgTps ?: 0.0, 0.001)
        assertEquals(1, parsed?.activeSubagents)
        assertEquals(9_000L, parsed?.devCreditsSpentMicros)
        assertEquals(0.0125, parsed?.costUsd ?: 0.0, 0.0001)
        assertEquals("estimated", parsed?.costStatus)
        assertEquals(listOf("line one", "line two"), parsed?.creditsLines)
    }

    @Test
    fun `nested usage envelope and JsonObject map correctly`() {
        val json =
            Json.parseToJsonElement(
                """{"usage":{"model":"m","input":100,"output":200,"reasoning":50,"prompt":300,"completion":200,"total":300,"calls":2,"context_used":400,"context_max":1000,"avg_tps":12.5,"context_estimated":false,"credits_lines":["credit"]}}""",
            ) as JsonObject

        val parsed = parseUsageSnapshot(json)

        assertEquals("m", parsed?.model)
        assertEquals(100L, parsed?.inputTokens)
        assertEquals(200L, parsed?.outputTokens)
        assertEquals(50L, parsed?.reasoningTokens)
        assertEquals(2L, parsed?.apiCalls)
        assertEquals(12.5, parsed?.avgTps ?: 0.0, 0.001)
        assertEquals(false, parsed?.contextEstimated)
        assertEquals(listOf("credit"), parsed?.creditsLines)
    }

    @Test
    fun `numeric strings booleans and large Long values are preserved`() {
        val parsed =
            parseUsageSnapshot(
                mapOf(
                    "input" to "2147483648",
                    "output" to JsonPrimitive("9223372036854770000"),
                    "context_estimated" to "true",
                    "avg_tps" to "42.25",
                ),
            )

        assertEquals(2_147_483_648L, parsed?.inputTokens)
        assertEquals(9_223_372_036_854_770_000L, parsed?.outputTokens)
        assertEquals(true, parsed?.contextEstimated)
        assertEquals(42.25, parsed?.avgTps ?: 0.0, 0.001)
    }

    @Test
    fun `missing and malformed optional fields remain unavailable`() {
        val parsed =
            parseUsageSnapshot(
                buildJsonObject {
                    put("input", JsonPrimitive("not-a-number"))
                    put("context_estimated", JsonPrimitive("maybe"))
                    put("avg_tps", JsonPrimitive("NaN"))
                    put("credits_lines", JsonPrimitive("not-a-list"))
                },
            )

        assertNull(parsed?.inputTokens)
        assertNull(parsed?.outputTokens)
        assertNull(parsed?.contextEstimated)
        assertNull(parsed?.avgTps)
        assertNull(parsed?.creditsLines)
    }

    @Test
    fun `compressions parses from Double and Int`() {
        assertEquals(2, parseUsageSnapshot(mapOf("compressions" to 2.0))?.compressions)
        assertEquals(0, parseUsageSnapshot(mapOf("compressions" to 0))?.compressions)
    }

    @Test
    fun `missing compressions key parses to null`() {
        val parsed = parseUsageSnapshot(mapOf("calls" to 12.0, "total" to 5000.0))
        assertNull(parsed?.compressions)
    }

    @Test
    fun `non-map result returns null`() {
        assertNull(parseUsageSnapshot(null))
        assertNull(parseUsageSnapshot("oops"))
        assertNull(parseUsageSnapshot(listOf(1.0)))
    }

    @Test
    fun `JsonObject and JsonPrimitive payloads parse successfully`() {
        val json =
            kotlinx.serialization.json.buildJsonObject {
                put("compressions", kotlinx.serialization.json.JsonPrimitive(3))
                put("context_used", kotlinx.serialization.json.JsonPrimitive(12000))
                put("context_max", kotlinx.serialization.json.JsonPrimitive(200000))
                put("total", kotlinx.serialization.json.JsonPrimitive(50000))
                put("avg_tps", kotlinx.serialization.json.JsonPrimitive(45.5))
            }

        val parsed = parseUsageSnapshot(json)

        assertEquals(3, parsed?.compressions)
        assertEquals(12000L, parsed?.contextUsed)
        assertEquals(200000L, parsed?.contextMax)
        assertEquals(50000L, parsed?.totalTokens)
        assertEquals(45.5, parsed?.avgTps ?: 0.0, 0.001)
    }

    @Test
    fun `deltaFrom derives independent cumulative fields and copies rolling values`() {
        val baseline =
            UsageSnapshotResponse(
                inputTokens = 10_000,
                outputTokens = 2_000,
                reasoningTokens = 800,
                promptTokens = 40_000,
                completionTokens = 2_000,
                totalTokens = 42_000,
                apiCalls = 3,
                cacheReadTokens = 100,
                cacheWriteTokens = 50,
                costUsd = 1.0,
            )
        val final =
            UsageSnapshotResponse(
                inputTokens = 13_000,
                outputTokens = 2_700,
                reasoningTokens = 1_100,
                promptTokens = 48_000,
                completionTokens = 2_700,
                totalTokens = 50_700,
                apiCalls = 5,
                cacheReadTokens = 180,
                cacheWriteTokens = 75,
                costUsd = 1.25,
                avgTps = 42.5,
                avgLatencySeconds = 0.75,
            )

        val turn = final.deltaFrom(baseline)

        assertEquals(3_000L, turn.inputTokens)
        assertEquals(700L, turn.outputTokens)
        assertEquals(300L, turn.reasoningTokens)
        assertEquals(8_000L, turn.promptTokens)
        assertEquals(700L, turn.completionTokens)
        assertEquals(8_700L, turn.totalTokens)
        assertEquals(2L, turn.apiCalls)
        assertEquals(80L, turn.cacheReadTokens)
        assertEquals(25L, turn.cacheWriteTokens)
        assertEquals(0.25, turn.costUsd ?: 0.0, 0.001)
        assertEquals(42.5, turn.avgTps ?: 0.0, 0.001)
    }

    @Test
    fun `deltaFrom treats missing reset and equal counters correctly`() {
        val baseline = UsageSnapshotResponse(outputTokens = 5_000, inputTokens = 10, totalTokens = 10)
        val reset = UsageSnapshotResponse(outputTokens = 100, inputTokens = 20, totalTokens = 20)
        val equal = UsageSnapshotResponse(outputTokens = 5_000, inputTokens = 10, totalTokens = 10)

        assertNull(reset.deltaFrom(baseline).outputTokens)
        assertNull(UsageSnapshotResponse(outputTokens = 6).deltaFrom(baseline).outputTokens)
        assertEquals(0L, equal.deltaFrom(baseline).outputTokens)
        assertEquals(0L, equal.deltaFrom(baseline).inputTokens)
        assertEquals(0L, equal.deltaFrom(baseline).totalTokens)
    }
}
