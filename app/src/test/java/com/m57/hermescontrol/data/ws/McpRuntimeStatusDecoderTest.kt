package com.m57.hermescontrol.data.ws

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-fixture tests for the `mcp.servers.status` decoder (#419). Payloads mirror the
 * upstream `McpServersStatusResult` / `McpServerRuntimeRow` shape.
 */
class McpRuntimeStatusDecoderTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private fun decode(text: String) = McpRuntimeStatusDecoder.decode(json.parseToJsonElement(text))

    @Test
    fun `needs_auth without error text decodes as an auth-parked row`() {
        val rows =
            decode(
                """{"servers":[{"name":"docs","transport":"http","tools":3,"connected":false,
                    "disabled":false,"status":"failed","source":"config","plugin":null,"needs_auth":true}],
                    "checked_at":1710000000}""",
            )
        assertNotNull(rows)
        val row = rows!!.getValue("docs")
        assertTrue(row.needsAuth)
        assertFalse(row.connected)
        assertEquals("failed", row.status)
    }

    @Test
    fun `omitted needs_auth defaults to false`() {
        val rows =
            decode(
                """{"servers":[{"name":"files","transport":"stdio","tools":0,"connected":false,
                    "disabled":false,"status":"configured","source":"config"}]}""",
            )
        assertNotNull(rows)
        assertFalse(rows!!.getValue("files").needsAuth)
    }

    @Test
    fun `explicit needs_auth false decodes as false`() {
        val rows =
            decode(
                """{"servers":[{"name":"files","transport":"stdio","tools":0,"connected":false,
                    "disabled":false,"status":"lazy","source":"config","plugin":null,"needs_auth":false}]}""",
            )
        assertFalse(rows!!.getValue("files").needsAuth)
    }

    @Test
    fun `connected row carries its tools count and plugin ownership`() {
        val rows =
            decode(
                """{"servers":[{"name":"plug-in","transport":"stdio","tools":12,"connected":true,
                    "disabled":false,"status":"connected","source":"plugin","plugin":"security"}]}""",
            )
        val row = rows!!.getValue("plug-in")
        assertTrue(row.connected)
        assertEquals(12, row.tools)
        assertEquals("plugin", row.source)
        assertEquals("security", row.plugin)
    }

    @Test
    fun `malformed entries are skipped but keep decodable rows`() {
        val rows =
            decode(
                """{"servers":[null,{"transport":"stdio"},{"name":""},{"name":"ok","transport":"stdio",
                    "tools":1,"connected":true,"disabled":false,"status":"connected","source":"config"}]}""",
            )
        assertNotNull(rows)
        assertEquals(setOf("ok"), rows!!.keys)
    }

    @Test
    fun `missing servers array is no evidence rather than an empty list`() {
        assertNull(decode("""{"checked_at":1710000000}"""))
        assertNull(decode("""{}"""))
    }
}
