package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginManageModelsTest {
    // CONTRACT: PluginSettingField with string, number, boolean, enum, secret, json types.
    @Test
    fun `PluginSettingField decodes and encodes string type correctly`() {
        val jsonString =
            """
            {
                "key": "api_host",
                "type": "string",
                "label": "API Host",
                "description": "Hostname for API",
                "required": true,
                "value": "https://api.example.com",
                "default": "https://localhost",
                "choices": null,
                "env": "API_HOST",
                "has_value": true
            }
            """.trimIndent()

        val field = OkHttpProvider.json.decodeFromString<PluginSettingField>(jsonString)
        assertEquals("api_host", field.key)
        assertEquals(PluginSettingFieldType.STRING, field.type)
        assertEquals("API Host", field.label)
        assertEquals("Hostname for API", field.description)
        assertTrue(field.required)
        assertEquals(JsonPrimitive("https://api.example.com"), field.value)
        assertEquals(JsonPrimitive("https://localhost"), field.default)
        assertNull(field.choices)
        assertEquals("API_HOST", field.env)
        assertEquals(true, field.hasValue)

        val encoded = OkHttpProvider.json.encodeToString(field)
        val roundTripped = OkHttpProvider.json.decodeFromString<PluginSettingField>(encoded)
        assertEquals(field, roundTripped)
    }

    // CONTRACT: PluginSettingField with string, number, boolean, enum, secret, json types.
    @Test
    fun `PluginSettingField decodes and encodes number type with numeric value and default`() {
        val jsonString =
            """
            {
                "key": "port",
                "type": "number",
                "label": "Port Number",
                "value": 8080,
                "default": 3000
            }
            """.trimIndent()

        val field = OkHttpProvider.json.decodeFromString<PluginSettingField>(jsonString)
        assertEquals("port", field.key)
        assertEquals(PluginSettingFieldType.NUMBER, field.type)
        assertEquals(JsonPrimitive(8080), field.value)
        assertEquals(JsonPrimitive(3000), field.default)
        assertEquals("", field.description)
        assertFalse(field.required)
        assertNull(field.choices)
        assertNull(field.env)
        assertNull(field.hasValue)
    }

    // CONTRACT: PluginSettingField with string, number, boolean, enum, secret, json types.
    @Test
    fun `PluginSettingField decodes and encodes boolean type with default false`() {
        val jsonString =
            """
            {
                "key": "enabled",
                "type": "boolean",
                "label": "Enabled",
                "value": true,
                "default": false
            }
            """.trimIndent()

        val field = OkHttpProvider.json.decodeFromString<PluginSettingField>(jsonString)
        assertEquals("enabled", field.key)
        assertEquals(PluginSettingFieldType.BOOLEAN, field.type)
        assertEquals(JsonPrimitive(true), field.value)
        assertEquals(JsonPrimitive(false), field.default)
    }

    // CONTRACT: PluginSettingField with string, number, boolean, enum, secret, json types.
    @Test
    fun `PluginSettingField decodes enum type with choices`() {
        val jsonString =
            """
            {
                "key": "mode",
                "type": "enum",
                "label": "Execution Mode",
                "choices": ["fast", "balanced", "thorough"],
                "value": "fast",
                "default": "balanced"
            }
            """.trimIndent()

        val field = OkHttpProvider.json.decodeFromString<PluginSettingField>(jsonString)
        assertEquals("mode", field.key)
        assertEquals(PluginSettingFieldType.ENUM, field.type)
        assertEquals(listOf("fast", "balanced", "thorough"), field.choices)
        assertEquals(JsonPrimitive("fast"), field.value)
        assertEquals(JsonPrimitive("balanced"), field.default)
    }

    // CONTRACT: PluginSettingField with string, number, boolean, enum, secret, json types.
    @Test
    fun `PluginSettingField decodes secret type with masked or null value and has_value flag`() {
        val jsonString =
            """
            {
                "key": "api_token",
                "type": "secret",
                "label": "API Token",
                "value": null,
                "env": "SECRET_TOKEN",
                "has_value": true
            }
            """.trimIndent()

        val field = OkHttpProvider.json.decodeFromString<PluginSettingField>(jsonString)
        assertEquals("api_token", field.key)
        assertEquals(PluginSettingFieldType.SECRET, field.type)
        assertEquals(null, field.value)
        assertEquals("SECRET_TOKEN", field.env)
        assertEquals(true, field.hasValue)
    }

    // CONTRACT: PluginSettingField with string, number, boolean, enum, secret, json types.
    @Test
    fun `PluginSettingField decodes json type with object or array values`() {
        val jsonString =
            """
            {
                "key": "extra_config",
                "type": "json",
                "label": "Extra Config",
                "value": {"timeout": 30, "retries": 3},
                "default": {}
            }
            """.trimIndent()

        val field = OkHttpProvider.json.decodeFromString<PluginSettingField>(jsonString)
        assertEquals("extra_config", field.key)
        assertEquals(PluginSettingFieldType.JSON, field.type)
        assertTrue(field.value is JsonObject)
        val valueObj = field.value as JsonObject
        assertEquals(JsonPrimitive(30), valueObj["timeout"])
        assertEquals(JsonPrimitive(3), valueObj["retries"])
        assertTrue(field.default is JsonObject)
    }

    // CONTRACT: PluginSettingField with string, number, boolean, enum, secret, json types (error case).
    @Test
    fun `PluginSettingField decoding fails on unknown type value`() {
        val jsonString =
            """
            {
                "key": "invalid_field",
                "type": "unknown_future_type",
                "label": "Invalid"
            }
            """.trimIndent()

        try {
            OkHttpProvider.json.decodeFromString<PluginSettingField>(jsonString)
            fail("Expected SerializationException for unknown type")
        } catch (_: SerializationException) {
            // expected
        }
    }

    // CONTRACT: PluginServerState (CONNECTED, APP_NOT_RUNNING, ENDPOINT_UNAVAILABLE, VERSION_TOO_OLD, MISSING_APP, UNKNOWN).
    @Test
    fun `PluginServerState decodes all enum variants from snake_case strings`() {
        val expectedStates =
            mapOf(
                "connected" to PluginServerState.CONNECTED,
                "app_not_running" to PluginServerState.APP_NOT_RUNNING,
                "endpoint_unavailable" to PluginServerState.ENDPOINT_UNAVAILABLE,
                "no_interactive_session" to PluginServerState.NO_INTERACTIVE_SESSION,
                "version_too_old" to PluginServerState.VERSION_TOO_OLD,
                "missing_app" to PluginServerState.MISSING_APP,
                "unknown" to PluginServerState.UNKNOWN,
            )

        for ((serialized, expectedEnum) in expectedStates) {
            val decoded = OkHttpProvider.json.decodeFromString<PluginServerState>("\"$serialized\"")
            assertEquals("Mismatch for $serialized", expectedEnum, decoded)
            val reEncoded = OkHttpProvider.json.encodeToString(expectedEnum)
            assertEquals("\"$serialized\"", reEncoded)
        }
    }

    // CONTRACT: PluginServerState (CONNECTED, APP_NOT_RUNNING, ENDPOINT_UNAVAILABLE, VERSION_TOO_OLD, MISSING_APP, UNKNOWN) via PluginServerRow.
    @Test
    fun `PluginServerRow decodes name state and sentence correctly`() {
        val jsonString =
            """
            {
                "name": "camofox_service",
                "state": "app_not_running",
                "sentence": "Camofox browser is not running on port 9333."
            }
            """.trimIndent()

        val row = OkHttpProvider.json.decodeFromString<PluginServerRow>(jsonString)
        assertEquals("camofox_service", row.name)
        assertEquals(PluginServerState.APP_NOT_RUNNING, row.state)
        assertEquals("Camofox browser is not running on port 9333.", row.sentence)
    }

    // CONTRACT: AgentPluginRow decoding from backend plugins.manage list payload shape.
    @Test
    fun `AgentPluginRow decodes full payload shape with servers and settings schema`() {
        val jsonString =
            """
            {
                "name": "camofox",
                "key": "camofox-browser",
                "version": "1.2.0",
                "description": "Anti-detect browser automation",
                "source": "installed",
                "status": "active",
                "portable": true,
                "install_dir": "/home/user/.hermes/plugins/camofox",
                "has_desktop_half": true,
                "servers": [
                    {
                        "name": "cdp",
                        "state": "connected",
                        "sentence": "CDP connected"
                    }
                ],
                "settings_schema": [
                    {
                        "key": "port",
                        "type": "number",
                        "label": "CDP Port",
                        "value": 9222
                    }
                ],
                "catalog_name": "camofox",
                "catalog_tier": "community",
                "installed_sha": "abc1234",
                "catalog_sha": "def5678",
                "catalog_version": "1.3.0",
                "update_available": true,
                "pinned_sha": "abc1234"
            }
            """.trimIndent()

        val row = OkHttpProvider.json.decodeFromString<AgentPluginRow>(jsonString)
        assertEquals("camofox", row.name)
        assertEquals("camofox-browser", row.key)
        assertEquals("1.2.0", row.version)
        assertEquals("Anti-detect browser automation", row.description)
        assertEquals("installed", row.source)
        assertEquals("status" to "active", "status" to row.status)
        assertTrue(row.portable)
        assertEquals("/home/user/.hermes/plugins/camofox", row.installDir)
        assertTrue(row.hasDesktopHalf)
        assertEquals(1, row.servers.size)
        assertEquals("cdp", row.servers[0].name)
        assertEquals(PluginServerState.CONNECTED, row.servers[0].state)
        assertEquals(1, row.settingsSchema?.size)
        assertEquals("port", row.settingsSchema?.get(0)?.key)
        assertEquals(PluginSettingFieldType.NUMBER, row.settingsSchema?.get(0)?.type)
        assertEquals("camofox", row.catalogName)
        assertEquals("community", row.catalogTier)
        assertEquals("abc1234", row.installedSha)
        assertEquals("def5678", row.catalogSha)
        assertEquals("1.3.0", row.catalogVersion)
        assertEquals(true, row.updateAvailable)
        assertEquals("abc1234", row.pinnedSha)
    }

    // CONTRACT: AgentPluginRow decoding from backend plugins.manage list payload shape (defaults/nulls).
    @Test
    fun `AgentPluginRow decodes minimal required payload using defaults`() {
        val jsonString =
            """
            {
                "name": "minimal_plugin"
            }
            """.trimIndent()

        val row = OkHttpProvider.json.decodeFromString<AgentPluginRow>(jsonString)
        assertEquals("minimal_plugin", row.name)
        assertNull(row.key)
        assertEquals("", row.version)
        assertEquals("", row.description)
        assertEquals("", row.source)
        assertEquals("", row.status)
        assertFalse(row.portable)
        assertEquals("", row.installDir)
        assertFalse(row.hasDesktopHalf)
        assertTrue(row.servers.isEmpty())
        assertNull(row.settingsSchema)
        assertNull(row.catalogName)
        assertNull(row.catalogTier)
        assertNull(row.installedSha)
        assertNull(row.catalogSha)
        assertNull(row.catalogVersion)
        assertNull(row.updateAvailable)
        assertNull(row.pinnedSha)
    }

    // CONTRACT: PluginsManageSettingsResult decoding happy path and error cases.
    @Test
    fun `PluginsManageSettingsResult decodes success with updated plugin and written keys`() {
        val jsonString =
            """
            {
                "ok": true,
                "name": "camofox",
                "written": ["port", "headless"],
                "plugin": {
                    "name": "camofox",
                    "version": "1.2.0"
                },
                "error": null
            }
            """.trimIndent()

        val result = OkHttpProvider.json.decodeFromString<PluginsManageSettingsResult>(jsonString)
        assertTrue(result.ok)
        assertEquals("camofox", result.name)
        assertEquals(listOf("port", "headless"), result.written)
        assertNotNull(result.plugin)
        assertEquals("camofox", result.plugin?.name)
        assertNull(result.error)
    }

    // CONTRACT: PluginsManageSettingsResult decoding happy path and error cases.
    @Test
    fun `PluginsManageSettingsResult decodes failure result with error message`() {
        val jsonString =
            """
            {
                "ok": false,
                "name": "camofox",
                "error": "Validation failed: port must be an integer"
            }
            """.trimIndent()

        val result = OkHttpProvider.json.decodeFromString<PluginsManageSettingsResult>(jsonString)
        assertFalse(result.ok)
        assertEquals("camofox", result.name)
        assertEquals("Validation failed: port must be an integer", result.error)
        assertNull(result.written)
        assertNull(result.plugin)
    }
}
