package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.remote.OkHttpProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginUpdateAllModelsTest {
    // CONTRACT: plugins.manage update_all -> {results:[{name,status,detail}], skipped:[{name,reason}], current:int}
    @Test
    fun `update all result decodes every backend status and skipped row`() {
        val result =
            OkHttpProvider.json.decodeFromString<PluginUpdateAllResult>(
                """
                {
                    "results": [
                        {"name": "snyk", "status": "updated", "detail": ""},
                        {"name": "camofox", "status": "needs_review", "detail": "adds tool browser_open"},
                        {"name": "kanban", "status": "current", "detail": ""},
                        {"name": "broken", "status": "failed", "detail": "clone timed out"}
                    ],
                    "skipped": [
                        {"name": "pinned-plugin", "reason": "pinned to a commit"},
                        {"name": "pip-plugin", "reason": "installed with pip"}
                    ],
                    "current": 1
                }
                """.trimIndent(),
            )

        assertEquals(4, result.results.size)
        assertEquals("updated", result.results[0].status)
        assertEquals("", result.results[0].detail)
        assertEquals("camofox", result.results[1].name)
        assertEquals("needs_review", result.results[1].status)
        assertEquals("adds tool browser_open", result.results[1].detail)
        assertEquals("failed", result.results[3].status)
        assertEquals(2, result.skipped.size)
        assertEquals("pinned-plugin", result.skipped[0].name)
        assertEquals("pinned to a commit", result.skipped[0].reason)
        assertEquals(1, result.current)
    }

    @Test
    fun `update all result defaults to no rows and zero current`() {
        val result = OkHttpProvider.json.decodeFromString<PluginUpdateAllResult>("{}")

        assertTrue(result.results.isEmpty())
        assertTrue(result.skipped.isEmpty())
        assertEquals(0, result.current)
    }

    // CONTRACT: plugins.manage auto_update -> {auto_apply: bool, check_hours: int}
    @Test
    fun `auto update state decodes the real switch state`() {
        val state =
            OkHttpProvider.json.decodeFromString<PluginAutoUpdateState>(
                """{"auto_apply": true, "check_hours": 6}""",
            )

        assertTrue(state.autoApply)
        assertEquals(6, state.checkHours)
    }

    @Test
    fun `auto update state defaults to off with the backend default cadence`() {
        val state = OkHttpProvider.json.decodeFromString<PluginAutoUpdateState>("{}")

        assertFalse(state.autoApply)
        assertEquals(24, state.checkHours)
    }
}
