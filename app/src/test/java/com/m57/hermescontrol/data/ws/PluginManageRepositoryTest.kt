package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginManageRepositoryTest {
    private fun pluginRepo(handler: suspend (String, Map<String, Any>) -> Any?) =
        PluginManageRepository(fakeCaller(handler))

    // CONTRACT: `listPlugins(profile)` calls WsMethods.PLUGINS_MANAGE with {"action": "list"} (and profile if set) and decodes plugins list.
    @Test
    fun `listPlugins without profile calls PLUGINS_MANAGE action list and decodes plugins list`() =
        runTest {
            var capturedMethod = ""
            var capturedParams: Map<String, Any?> = emptyMap()

            val mockPayload =
                OkHttpProvider.json.parseToJsonElement(
                    """
                    {
                        "plugins": [
                            {
                                "name": "plugin-alpha",
                                "version": "1.0.0",
                                "status": "active"
                            },
                            {
                                "name": "plugin-beta",
                                "version": "2.1.0",
                                "status": "disabled"
                            }
                        ]
                    }
                    """.trimIndent(),
                )

            val repository =
                pluginRepo { method, params ->
                    capturedMethod = method
                    capturedParams = params
                    mockPayload
                }

            val plugins = repository.listPlugins()

            assertEquals(WsMethods.PLUGINS_MANAGE, capturedMethod)
            assertEquals(mapOf("action" to "list"), capturedParams)
            assertEquals(2, plugins.size)
            assertEquals("plugin-alpha", plugins[0].name)
            assertEquals("1.0.0", plugins[0].version)
            assertEquals("active", plugins[0].status)
            assertEquals("plugin-beta", plugins[1].name)
            assertEquals("2.1.0", plugins[1].version)
            assertEquals("disabled", plugins[1].status)
        }

    // CONTRACT: `listPlugins(profile)` calls WsMethods.PLUGINS_MANAGE with {"action": "list"} (and profile if set) and decodes plugins list.
    @Test
    fun `listPlugins with non-blank profile includes profile parameter in request`() =
        runTest {
            var capturedMethod = ""
            var capturedParams: Map<String, Any?> = emptyMap()

            val mockPayload =
                OkHttpProvider.json.parseToJsonElement(
                    """
                    {
                        "plugins": []
                    }
                    """.trimIndent(),
                )

            val repository =
                pluginRepo { method, params ->
                    capturedMethod = method
                    capturedParams = params
                    mockPayload
                }

            val plugins = repository.listPlugins(profile = "work")

            assertEquals(WsMethods.PLUGINS_MANAGE, capturedMethod)
            assertEquals(mapOf("action" to "list", "profile" to "work"), capturedParams)
            assertTrue(plugins.isEmpty())
        }

    // CONTRACT: `listPlugins(profile)` calls WsMethods.PLUGINS_MANAGE with {"action": "list"} (and profile if set) and decodes plugins list.
    @Test
    fun `listPlugins returns empty list when plugins key is missing or response is not a JsonObject`() =
        runTest {
            val emptyObjRepo =
                pluginRepo { _, _ ->
                    OkHttpProvider.json.parseToJsonElement("{}")
                }
            assertTrue(emptyObjRepo.listPlugins().isEmpty())

            val nonObjRepo =
                pluginRepo { _, _ ->
                    OkHttpProvider.json.parseToJsonElement("[]")
                }
            assertTrue(nonObjRepo.listPlugins().isEmpty())

            val nullRepo =
                pluginRepo { _, _ ->
                    null
                }
            assertTrue(nullRepo.listPlugins().isEmpty())
        }

    // CONTRACT: `saveSettings(key, values, profile)` calls WsMethods.PLUGINS_MANAGE with {"action": "settings", "key": key, "values": values} and decodes result.
    @Test
    fun `saveSettings without profile calls PLUGINS_MANAGE with action settings key and JsonObject values`() =
        runTest {
            var capturedMethod = ""
            var capturedParams: Map<String, Any?> = emptyMap()

            val mockResponse =
                OkHttpProvider.json.parseToJsonElement(
                    """
                    {
                        "ok": true,
                        "name": "camofox",
                        "written": ["port", "headless"],
                        "plugin": {
                            "name": "camofox",
                            "version": "1.0.0"
                        }
                    }
                    """.trimIndent(),
                )

            val repository =
                pluginRepo { method, params ->
                    capturedMethod = method
                    capturedParams = params
                    mockResponse
                }

            val valuesToSave: Map<String, JsonElement> =
                mapOf(
                    "port" to JsonPrimitive(9222),
                    "headless" to JsonPrimitive(true),
                )

            val result = repository.saveSettings(key = "camofox", values = valuesToSave)

            assertEquals(WsMethods.PLUGINS_MANAGE, capturedMethod)
            assertEquals("settings", capturedParams["action"])
            assertEquals("camofox", capturedParams["key"])
            assertEquals(mapOf("port" to 9222, "headless" to true), capturedParams["values"])
            assertFalse(capturedParams.containsKey("profile"))

            assertTrue(result.ok)
            assertEquals("camofox", result.name)
            assertEquals(listOf("port", "headless"), result.written)
            assertNotNull(result.plugin)
            assertEquals("camofox", result.plugin?.name)
            assertNull(result.error)
        }

    // CONTRACT: `saveSettings(key, values, profile)` calls WsMethods.PLUGINS_MANAGE with {"action": "settings", "key": key, "values": values} and decodes result.
    @Test
    fun `saveSettings with profile includes profile in request and handles error result`() =
        runTest {
            var capturedMethod = ""
            var capturedParams: Map<String, Any?> = emptyMap()

            val mockResponse =
                OkHttpProvider.json.parseToJsonElement(
                    """
                    {
                        "ok": false,
                        "name": "camofox",
                        "error": "Port already in use"
                    }
                    """.trimIndent(),
                )

            val repository =
                pluginRepo { method, params ->
                    capturedMethod = method
                    capturedParams = params
                    mockResponse
                }

            val valuesToSave: Map<String, JsonElement> =
                mapOf(
                    "port" to JsonPrimitive(8080),
                )

            val result = repository.saveSettings(key = "camofox", values = valuesToSave, profile = "staging")

            assertEquals(WsMethods.PLUGINS_MANAGE, capturedMethod)
            assertEquals(
                mapOf(
                    "action" to "settings",
                    "key" to "camofox",
                    "values" to mapOf("port" to 8080),
                    "profile" to "staging",
                ),
                capturedParams,
            )

            assertFalse(result.ok)
            assertEquals("camofox", result.name)
            assertEquals("Port already in use", result.error)
            assertNull(result.written)
            assertNull(result.plugin)
        }

    // CONTRACT: `saveSettings(key, values, profile)` calls WsMethods.PLUGINS_MANAGE with {"action": "settings", "key": key, "values": values} and decodes result (error case).
    @Test
    fun `saveSettings throws SerializationException when response is malformed`() =
        runTest {
            val repository =
                pluginRepo { _, _ ->
                    OkHttpProvider.json.parseToJsonElement("{\"ok\": \"not-a-boolean\"}")
                }

            try {
                repository.saveSettings("camofox", emptyMap())
                fail("Expected SerializationException when response JSON is malformed")
            } catch (_: SerializationException) {
                // Expected
            }
        }

    // CONTRACT: `updateAll(profile)` calls WsMethods.PLUGINS_MANAGE with {"action": "update_all"} and
    // decodes results/skipped/current; an explicit profile travels with the request.
    @Test
    fun `updateAll sends update_all and decodes the per plugin rows`() =
        runTest {
            var capturedMethod = ""
            var capturedParams: Map<String, Any?> = emptyMap()
            val repository =
                pluginRepo { method, params ->
                    capturedMethod = method
                    capturedParams = params
                    OkHttpProvider.json.parseToJsonElement(
                        """
                        {
                            "results": [
                                {"name": "snyk", "status": "updated", "detail": ""},
                                {"name": "camofox", "status": "needs_review", "detail": "adds a tool"}
                            ],
                            "skipped": [{"name": "pinned", "reason": "pinned to a commit"}],
                            "current": 2
                        }
                        """.trimIndent(),
                    )
                }

            val result = repository.updateAll(profile = "work")

            assertEquals(WsMethods.PLUGINS_MANAGE, capturedMethod)
            assertEquals(mapOf("action" to "update_all", "profile" to "work"), capturedParams)
            assertEquals(2, result.results.size)
            assertEquals("updated", result.results[0].status)
            assertEquals("needs_review", result.results[1].status)
            assertEquals("pinned", result.skipped.single().name)
            assertEquals(2, result.current)
        }

    @Test
    fun `updateAll without a profile omits the profile key`() =
        runTest {
            var capturedParams: Map<String, Any?> = emptyMap()
            val repository =
                pluginRepo { _, params ->
                    capturedParams = params
                    OkHttpProvider.json.parseToJsonElement("{}")
                }

            repository.updateAll()

            assertEquals(mapOf("action" to "update_all"), capturedParams)
        }

    // CONTRACT: `autoUpdateState(profile)` calls WsMethods.PLUGINS_MANAGE with {"action": "auto_update"}
    // and decodes {auto_apply, check_hours}; the write adds `enable`.
    @Test
    fun `autoUpdateState reads the switch without writing it`() =
        runTest {
            var capturedMethod = ""
            var capturedParams: Map<String, Any?> = emptyMap()
            val repository =
                pluginRepo { method, params ->
                    capturedMethod = method
                    capturedParams = params
                    OkHttpProvider.json.parseToJsonElement("{\"auto_apply\": true, \"check_hours\": 6}")
                }

            val state = repository.autoUpdateState(profile = "staging")

            assertEquals(WsMethods.PLUGINS_MANAGE, capturedMethod)
            assertEquals(mapOf("action" to "auto_update", "profile" to "staging"), capturedParams)
            assertTrue(state.autoApply)
            assertEquals(6, state.checkHours)
        }

    @Test
    fun `setAutoUpdate writes enable and decodes the resulting state`() =
        runTest {
            var capturedParams: Map<String, Any?> = emptyMap()
            val repository =
                pluginRepo { _, params ->
                    capturedParams = params
                    OkHttpProvider.json.parseToJsonElement("{\"auto_apply\": false, \"check_hours\": 24}")
                }

            val state = repository.setAutoUpdate(enabled = false, profile = "work")

            assertEquals(
                mapOf("action" to "auto_update", "enable" to false, "profile" to "work"),
                capturedParams,
            )
            assertFalse(state.autoApply)
            assertEquals(24, state.checkHours)
        }
}
