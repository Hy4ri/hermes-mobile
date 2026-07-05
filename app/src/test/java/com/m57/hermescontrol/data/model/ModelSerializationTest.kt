package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSerializationTest {
    private val json = OkHttpProvider.json

    @Test
    fun testSkillSerialization() {
        val skill =
            Skill(
                name = "weather",
                description = "Get current weather info",
                category = "utility",
                enabled = true,
            )
        val jsonStr = json.encodeToString(skill)
        val deserialized = json.decodeFromString<Skill>(jsonStr)
        assertEquals(skill.name, deserialized.name)
        assertEquals(skill.description, deserialized.description)
        assertEquals(skill.category, deserialized.category)
        assertEquals(skill.enabled, deserialized.enabled)
    }

    @Test
    fun testSkillDeserialization() {
        val jsonStr =
            """
            {
                "name": "weather",
                "description": "Get current weather info",
                "category": "utility",
                "enabled": true
            }
            """.trimIndent()
        val skill = json.decodeFromString<Skill>(jsonStr)
        assertEquals("weather", skill.name)
        assertEquals("Get current weather info", skill.description)
        assertEquals("utility", skill.category)
        assertEquals(true, skill.enabled)
    }

    @Test
    fun testCronJobSerialization() {
        val job =
            CronJob(
                id = "job-123",
                name = "Daily Backup",
                schedule = JsonPrimitive("0 0 * * *"),
                state = "active",
                last_run_status = null,
                next_run = null,
            )
        val jsonStr = json.encodeToString(job)
        val deserialized = json.decodeFromString<CronJob>(jsonStr)
        assertEquals(job.id, deserialized.id)
        assertEquals(job.name, deserialized.name)
        assertEquals(job.schedule, deserialized.schedule)
        assertEquals(job.state, deserialized.state)
    }

    @Test
    fun testCronJobDeserialization() {
        val jsonStr =
            """
            {
                "id": "job-123",
                "name": "Daily Backup",
                "schedule": "0 0 * * *",
                "state": "active"
            }
            """.trimIndent()
        val job = json.decodeFromString<CronJob>(jsonStr)
        assertEquals("job-123", job.id)
        assertEquals("Daily Backup", job.name)
        assertEquals("0 0 * * *", job.scheduleText)
        assertEquals("active", job.state)
    }

    @Test
    fun testToggleSkillRequestSerialization() {
        val request = ToggleSkillRequest(name = "weather", enabled = false)
        val jsonStr = json.encodeToString(request)
        // Ensure serialization produces correct JSON structure
        val parsed = json.decodeFromString<Map<String, JsonElement>>(jsonStr)
        assertEquals("weather", (parsed["name"] as? JsonPrimitive)?.content)
        assertEquals(false, (parsed["enabled"] as? JsonPrimitive)?.booleanOrNull)
    }

    @Test
    fun testSkillDeserialization_missingRequiredFields() {
        val jsonStr = "{}"
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<Skill>(jsonStr)
        }
    }

    @Test
    fun testSkillDeserialization_explicitNullForNonNullable() {
        val jsonStr =
            """
            {
                "name": null,
                "enabled": null
            }
            """.trimIndent()
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<Skill>(jsonStr)
        }
    }

    @Test
    fun testCronJobDeserialization_missingRequiredFields() {
        val jsonStr = "{}"
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<CronJob>(jsonStr)
        }
    }

    @Test
    fun testSessionListResponseDeserialization_missingRequiredFields() {
        val jsonStr = "{}"
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<SessionListResponse>(jsonStr)
        }
    }

    @Test
    fun testSessionInfoDeserialization_missingRequiredFields() {
        val jsonStr = "{}"
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<SessionInfo>(jsonStr)
        }
    }

    @Test
    fun testSessionMessagesResponseDeserialization_missingRequiredFields() {
        val jsonStr = "{}"
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<SessionMessagesResponse>(jsonStr)
        }
    }

    @Test
    fun testSkillDeserialization_malformedJson() {
        val jsonStr = "{\"name\": \"weather\","
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<Skill>(jsonStr)
        }
    }

    @Test
    fun testSkillDeserialization_typeMismatchObject() {
        val jsonStr =
            """
            {
                "name": "weather",
                "enabled": {}
            }
            """.trimIndent()
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<Skill>(jsonStr)
        }
    }

    @Test
    fun testSkillDeserialization_typeMismatchArray() {
        val jsonStr =
            """
            {
                "name": "weather",
                "enabled": []
            }
            """.trimIndent()
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<Skill>(jsonStr)
        }
    }

    @Test
    fun testSkillDeserialization_missingName_causesNullOnNonNullableField() {
        val jsonStr =
            """
            {
                "description": "Get current weather info",
                "category": "utility",
                "enabled": true
            }
            """.trimIndent()
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<Skill>(jsonStr)
        }
    }

    @Test
    fun testSkillDeserialization_nullName_causesNullOnNonNullableField() {
        val jsonStr =
            """
            {
                "name": null,
                "description": "Get current weather info",
                "category": "utility",
                "enabled": true
            }
            """.trimIndent()
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<Skill>(jsonStr)
        }
    }

    @Test
    fun testSkillDeserialization_missingEnabled_usesJvmDefaultValue() {
        val jsonStr =
            """
            {
                "name": "weather",
                "description": "Get current weather info",
                "category": "utility"
            }
            """.trimIndent()
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<Skill>(jsonStr)
        }
    }

    @Test
    fun testCronJobDeserialization_missingId_causesNullOnNonNullableField() {
        val jsonStr =
            """
            {
                "name": "Daily Backup",
                "schedule": "0 0 * * *",
                "state": "active"
            }
            """.trimIndent()
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<CronJob>(jsonStr)
        }
    }

    @Test
    fun testSessionListResponseDeserialization_missingSessions_causesNullOnNonNullableField() {
        val jsonStr = "{}"
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<SessionListResponse>(jsonStr)
        }
    }

    @Test
    fun testSessionInfoDeserialization_missingId_causesNullOnNonNullableField() {
        val jsonStr =
            """
            {
                "title": "My Session",
                "created_at": "2026-06-15T12:00:00Z"
            }
            """.trimIndent()
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<SessionInfo>(jsonStr)
        }
    }

    @Test
    fun testSessionMessagesResponseDeserialization_missingMessages_causesNullOnNonNullableField() {
        val jsonStr = "{}"
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<SessionMessagesResponse>(jsonStr)
        }
    }

    @Test
    fun testDeserialization_malformedJson_throwsJsonSyntaxException() {
        val jsonStr = """{"name": "weather", "enabled": """
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<Skill>(jsonStr)
        }
    }

    @Test
    fun testStatusResponseDeserialization_allFields() {
        val jsonStr =
            """
            {
                "version": "1.0.0",
                "gateway_running": true,
                "active_sessions": 5,
                "auth_required": false,
                "gateway_platforms": {
                    "platform1": {
                        "state": "connected",
                        "error_code": null
                    },
                    "platform2": {
                        "state": "error",
                        "error_code": "ERR_CONN"
                    }
                }
            }
            """.trimIndent()
        val response = json.decodeFromString<StatusResponse>(jsonStr)
        assertEquals("1.0.0", response.version)
        assertEquals(true, response.gateway_running)
        assertEquals(5, response.active_sessions)
        assertEquals(false, response.auth_required)
        assertEquals(2, response.gateway_platforms?.size)
        assertEquals("connected", response.gateway_platforms?.get("platform1")?.state)
        assertNull(response.gateway_platforms?.get("platform1")?.error_code)
        assertEquals("error", response.gateway_platforms?.get("platform2")?.state)
        assertEquals("ERR_CONN", response.gateway_platforms?.get("platform2")?.error_code)
    }

    @Test
    fun testStatusResponseDeserialization_missingFields() {
        val jsonStr = "{}"
        val response = json.decodeFromString<StatusResponse>(jsonStr)
        assertNull(response.version)
        assertNull(response.gateway_running)
        assertNull(response.active_sessions)
        assertNull(response.auth_required)
        assertNull(response.gateway_platforms)
    }

    @Test
    fun testStatusResponseDeserialization_gatewayPlatformsNullValue() {
        val jsonStr =
            """
            {
                "gateway_platforms": {
                    "android": null,
                    "ios": {
                        "state": null
                    }
                }
            }
            """.trimIndent()
        val response = json.decodeFromString<StatusResponse>(jsonStr)
        val platforms = response.gateway_platforms
        assertNotNull(platforms)
        assertNull(platforms!!["android"])
        val iosPlatform = platforms["ios"]
        assertNotNull(iosPlatform)
        assertNull(iosPlatform!!.state)
        assertNull(iosPlatform.error_code)
    }

    @Test
    fun testStatusResponseDeserialization_typeMismatchInGatewayPlatforms() {
        val jsonStr =
            """
            {
                "gateway_platforms": "not_a_map"
            }
            """.trimIndent()
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<StatusResponse>(jsonStr)
        }
    }

    @Test
    fun testSystemStatsResponse_deserialization() {
        val jsonStr =
            """
            {
                "os": "Linux",
                "os_release": "5.4.274",
                "arch": "aarch64",
                "hostname": "hermes-box",
                "python_version": "3.12.0",
                "python_impl": "CPython",
                "hermes_version": "1.2.3",
                "cpu_count": 8,
                "cpu_percent": 45.2,
                "psutil": true,
                "load_avg": [1.5, 1.2, 0.9],
                "uptime_seconds": 86400.0,
                "memory": {
                    "total": 8388608,
                    "available": 4194304,
                    "used": 4194304,
                    "percent": 50.0
                },
                "disk": {
                    "total": 1073741824,
                    "used": 536870912,
                    "free": 536870912,
                    "percent": 50.0
                }
            }
            """.trimIndent()
        val response = json.decodeFromString<SystemStatsResponse>(jsonStr)
        assertEquals("Linux", response.os)
        assertEquals("aarch64", response.arch)
        assertEquals("hermes-box", response.hostname)
        assertEquals("1.2.3", response.hermes_version)
        assertEquals(45.2, response.cpu_percent)
        assertEquals(8, response.cpu_count)
        assertTrue(response.psutil ?: false)
        assertEquals(1.5, response.load_avg?.get(0) ?: 0.0, 0.01)
        assertEquals(86400.0, response.uptime_seconds ?: 0.0, 0.01)
        assertEquals(50.0, response.memory?.percent ?: 0.0, 0.01)
        assertEquals(8388608L, response.memory?.total)
        assertEquals(50.0, response.disk?.percent ?: 0.0, 0.01)
    }

    @Test
    fun testToggleSkillRequestDeserialization_missingRequiredFields() {
        val jsonStr = """{"enabled":true}"""
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<ToggleSkillRequest>(jsonStr)
        }
    }

    @Test
    fun testToggleSkillRequestDeserialization_explicitNull() {
        val jsonStr = """{"name":null,"enabled":null}"""
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<ToggleSkillRequest>(jsonStr)
        }
    }

    @Test
    fun testToggleSkillRequestSerialization_withNullName() {
        val jsonInput = """{"name":null,"enabled":true}"""
        org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<ToggleSkillRequest>(jsonInput)
        }
    }
}
