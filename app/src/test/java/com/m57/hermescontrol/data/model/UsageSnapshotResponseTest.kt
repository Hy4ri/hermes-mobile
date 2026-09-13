package com.m57.hermescontrol.data.model

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
}
