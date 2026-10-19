package com.m57.hermescontrol.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SessionTransferTest {
    private val export =
        """
        {"id":"s1","title":"Trip plan","messages":[
          {"id":1,"role":"user","content":"Plan a trip","active":1},
          {"id":2,"role":"assistant","content":"Sure.\n@image:/tmp/a.png\nWhere to?"},
          {"id":3,"role":"tool","content":"{\"secret\":\"x\"}"},
          {"id":4,"role":"assistant","content":"old turn","compacted":true},
          {"id":5,"role":"assistant","content":"hidden","active":0},
          {"id":6,"role":"user","content":"marker","display_kind":"model_switch"},
          {"id":7,"role":"user","content":[{"type":"text","text":"Rome"},{"type":"image_url"}]},
          {"id":8,"role":"assistant","content":"raw","display_content":"Shown"}
        ]}
        """.trimIndent()

    @Test
    fun rendersOnlyLiveVisibleTurns() {
        val text = renderTranscript(export)!!
        assertEquals(
            "Trip plan\n\nYou:\nPlan a trip\n\nHermes:\nSure.\nWhere to?\n\nYou:\nRome\n\nHermes:\nShown\n",
            text,
        )
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("@image"))
    }

    @Test
    fun usesFallbackTitleWhenExportHasNone() {
        val json = """{"messages":[{"role":"user","content":"hi"}]}"""
        assertEquals("Chat\n\nYou:\nhi\n", renderTranscript(json, "Chat"))
    }

    @Test
    fun rejectsMalformedAndEmptyPayloads() {
        assertNull(renderTranscript("not json"))
        assertNull(renderTranscript("[]"))
        assertNull(renderTranscript("""{"messages":[]}"""))
        assertNull(renderTranscript("""{"messages":[{"role":"tool","content":"x"}]}"""))
    }

    @Test
    fun copyBoundedCopiesWithinLimit() {
        val out = ByteArrayOutputStream()
        val count = copyBounded(ByteArrayInputStream(ByteArray(100) { 1 }), out, limit = 100)
        assertEquals(100L, count)
        assertEquals(100, out.size())
    }

    @Test
    fun copyBoundedFailsPastLimit() {
        assertThrows(TransferTooLargeException::class.java) {
            copyBounded(ByteArrayInputStream(ByteArray(101)), ByteArrayOutputStream(), limit = 100)
        }
    }

    @Test
    fun exportFileNameIsSafe() {
        assertEquals("hermes-trip-plan-abc123.json", exportFileName("Trip plan!", "abc123"))
        assertEquals("hermes-a-b-c.json", exportFileName(null, "a/b/c"))
        assertTrue(exportFileName("../../etc/passwd", "x").none { it == '/' })
    }
}
