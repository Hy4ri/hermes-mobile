package com.m57.hermescontrol.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parser tests for the `session.context_breakdown` WS RPC result.
 *
 * The WS event parser (`JsonRpcModels.toAny`) decodes JSON numbers as Double,
 * so the parser must read every count through [Number] — these tests use
 * Double literals to mirror the real wire shape.
 */
class ContextBreakdownResponseTest {
    @Test
    fun `full payload parses all fields`() {
        val result =
            mapOf(
                "context_used" to 123456.0,
                "context_max" to 262144.0,
                "context_percent" to 47.0,
                "estimated_total" to 120000.0,
                "model" to "deepseek-v4-flash",
            )

        val parsed = parseContextBreakdown(result)

        assertEquals(123456L, parsed?.contextUsed)
        assertEquals(262144L, parsed?.contextMax)
        assertEquals(47, parsed?.contextPercent)
        assertEquals(120000L, parsed?.estimatedTotal)
        assertEquals("deepseek-v4-flash", parsed?.model)
    }

    @Test
    fun `missing optional keys parse to null`() {
        val result = mapOf("context_used" to 5000.0)

        val parsed = parseContextBreakdown(result)

        assertEquals(5000L, parsed?.contextUsed)
        assertNull(parsed?.contextMax)
        assertNull(parsed?.contextPercent)
        assertNull(parsed?.estimatedTotal)
        assertNull(parsed?.model)
    }

    @Test
    fun `zero and negative counts survive parsing`() {
        val result =
            mapOf(
                "context_used" to 0.0,
                "context_max" to -1.0,
            )

        val parsed = parseContextBreakdown(result)

        // The >0 policy lives in the consumer (ChatViewModel), not the parser —
        // the parser reports the wire value faithfully.
        assertEquals(0L, parsed?.contextUsed)
        assertEquals(-1L, parsed?.contextMax)
    }

    @Test
    fun `non-map result returns null`() {
        assertNull(parseContextBreakdown(null))
        assertNull(parseContextBreakdown("oops"))
        assertNull(parseContextBreakdown(listOf(1.0, 2.0)))
        assertNull(parseContextBreakdown(42.0))
    }

    @Test
    fun `integer-typed numbers parse too`() {
        val result =
            mapOf(
                "context_used" to 999L,
                "context_max" to 262144,
                "context_percent" to 0,
            )

        val parsed = parseContextBreakdown(result)

        assertEquals(999L, parsed?.contextUsed)
        assertEquals(262144L, parsed?.contextMax)
        assertEquals(0, parsed?.contextPercent)
    }
}
