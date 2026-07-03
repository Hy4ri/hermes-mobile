package com.m57.hermescontrol.data.model

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelSerializationTest {
    private val gson = Gson()

    @Test
    fun testSkillDeserialization_allFields() {
        val json =
            """
            {
                "name": "weather",
                "description": "Get current weather info",
                "category": "utility",
                "enabled": true
            }
            """.trimIndent()
        val skill = gson.fromJson(json, Skill::class.java)
        assertEquals("weather", skill.name)
        assertEquals("Get current weather info", skill.description)
        assertEquals("utility", skill.category)
        assertEquals(true, skill.enabled)
    }

    @Test
    fun testSkillDeserialization_optionalFieldsNull() {
        val json =
            """
            {
                "name": "light_control",
                "description": null,
                "category": null,
                "enabled": false
            }
            """.trimIndent()
        val skill = gson.fromJson(json, Skill::class.java)
        assertEquals("light_control", skill.name)
        assertNull(skill.description)
        assertNull(skill.category)
        assertEquals(false, skill.enabled)
    }

    @Test
    fun testCronJobDeserialization_allFields() {
        val json =
            """
            {
                "id": "job-123",
                "name": "Daily Backup",
                "schedule": "0 0 * * *",
                "state": "active",
                "last_run_status": "success",
                "next_run": "2026-06-16T00:00:00Z"
            }
            """.trimIndent()
        val job = gson.fromJson(json, CronJob::class.java)
        assertEquals("job-123", job.id)
        assertEquals("Daily Backup", job.name)
        assertEquals("0 0 * * *", job.schedule)
        assertEquals("active", job.state)
        assertEquals("success", job.last_run_status)
        assertEquals("2026-06-16T00:00:00Z", job.next_run)
    }

    @Test
    fun testCronJobDeserialization_optionalFieldsNull() {
        val json =
            """
            {
                "id": "job-456",
                "name": "One-off Task",
                "schedule": null,
                "state": null,
                "last_run_status": null,
                "next_run": null
            }
            """.trimIndent()
        val job = gson.fromJson(json, CronJob::class.java)
        assertEquals("job-456", job.id)
        assertEquals("One-off Task", job.name)
        assertNull(job.schedule)
        assertNull(job.state)
        assertNull(job.last_run_status)
        assertNull(job.next_run)
    }

    @Test
    fun testToggleSkillRequestSerialization() {
        val request = ToggleSkillRequest(name = "weather", enabled = false)
        val json = gson.toJson(request)
        // Ensure serialization produces correct JSON structure
        val parsed = gson.fromJson(json, Map::class.java)
        assertEquals("weather", parsed["name"])
        assertEquals(false, parsed["enabled"])
    }

    @Test
    fun testSkillDeserialization_missingRequiredFields() {
        val json = "{}"
        val skill = gson.fromJson(json, Skill::class.java)
        // Verify null-safety issue: Gson allows non-nullable 'name' to be null at runtime
        assertNull(skill.name)
        // Primitive boolean defaults to false in Gson/JVM when missing
        assertEquals(false, skill.enabled)
    }

    @Test
    fun testSkillDeserialization_explicitNullForNonNullable() {
        val json =
            """
            {
                "name": null,
                "enabled": null
            }
            """.trimIndent()
        val skill = gson.fromJson(json, Skill::class.java)
        assertNull(skill.name)
        assertEquals(false, skill.enabled)
    }

    @Test
    fun testCronJobDeserialization_missingRequiredFields() {
        val json = "{}"
        val job = gson.fromJson(json, CronJob::class.java)
        assertNull(job.id)
        assertNull(job.name)
    }

    @Test
    fun testSessionListResponseDeserialization_missingRequiredFields() {
        val json = "{}"
        val response = gson.fromJson(json, SessionListResponse::class.java)
        assertNull(response.sessions)
    }

    @Test
    fun testSessionInfoDeserialization_missingRequiredFields() {
        val json = "{}"
        val info = gson.fromJson(json, SessionInfo::class.java)
        assertNull(info.id)
        assertNull(info.title)
    }

    @Test
    fun testSessionMessagesResponseDeserialization_missingRequiredFields() {
        val json = "{}"
        val response = gson.fromJson(json, SessionMessagesResponse::class.java)
        assertNull(response.messages)
    }

    @Test
    fun testSkillDeserialization_malformedJson() {
        val json = "{\"name\": \"weather\","
        org.junit.jupiter.api.Assertions.assertThrows(com.google.gson.JsonSyntaxException::class.java) {
            gson.fromJson(json, Skill::class.java)
        }
    }

    @Test
    fun testSkillDeserialization_typeMismatchObject() {
        val json =
            """
            {
                "name": "weather",
                "enabled": {}
            }
            """.trimIndent()
        org.junit.jupiter.api.Assertions.assertThrows(com.google.gson.JsonSyntaxException::class.java) {
            gson.fromJson(json, Skill::class.java)
        }
    }

    @Test
    fun testSkillDeserialization_typeMismatchArray() {
        val json =
            """
            {
                "name": "weather",
                "enabled": []
            }
            """.trimIndent()
        org.junit.jupiter.api.Assertions.assertThrows(com.google.gson.JsonSyntaxException::class.java) {
            gson.fromJson(json, Skill::class.java)
        }
    }

    @Test
    fun testSkillDeserialization_missingName_causesNullOnNonNullableField() {
        val json =
            """
            {
                "description": "Get current weather info",
                "category": "utility",
                "enabled": true
            }
            """.trimIndent()
        val skill = gson.fromJson(json, Skill::class.java)
        // Gson bypasses Kotlin null-safety constraints, resulting in null on non-nullable field
        val nameNullable: String? = skill.name
        assertNull(nameNullable)
    }

    @Test
    fun testSkillDeserialization_nullName_causesNullOnNonNullableField() {
        val json =
            """
            {
                "name": null,
                "description": "Get current weather info",
                "category": "utility",
                "enabled": true
            }
            """.trimIndent()
        val skill = gson.fromJson(json, Skill::class.java)
        val nameNullable: String? = skill.name
        assertNull(nameNullable)
    }

    @Test
    fun testSkillDeserialization_missingEnabled_usesJvmDefaultValue() {
        val json =
            """
            {
                "name": "weather",
                "description": "Get current weather info",
                "category": "utility"
            }
            """.trimIndent()
        val skill = gson.fromJson(json, Skill::class.java)
        // Boolean primitive defaults to false
        assertEquals(false, skill.enabled)
    }

    @Test
    fun testCronJobDeserialization_missingId_causesNullOnNonNullableField() {
        val json =
            """
            {
                "name": "Daily Backup",
                "schedule": "0 0 * * *",
                "state": "active"
            }
            """.trimIndent()
        val job = gson.fromJson(json, CronJob::class.java)
        val idNullable: String? = job.id
        assertNull(idNullable)
    }

    @Test
    fun testSessionListResponseDeserialization_missingSessions_causesNullOnNonNullableField() {
        val json = "{}"
        val response = gson.fromJson(json, SessionListResponse::class.java)
        val sessionsNullable: List<SessionInfo>? = response.sessions
        assertNull(sessionsNullable)
    }

    @Test
    fun testSessionInfoDeserialization_missingId_causesNullOnNonNullableField() {
        val json =
            """
            {
                "title": "My Session",
                "created_at": "2026-06-15T12:00:00Z"
            }
            """.trimIndent()
        val info = gson.fromJson(json, SessionInfo::class.java)
        val idNullable: String? = info.id
        assertNull(idNullable)
    }

    @Test
    fun testSessionMessagesResponseDeserialization_missingMessages_causesNullOnNonNullableField() {
        val json = "{}"
        val response = gson.fromJson(json, SessionMessagesResponse::class.java)
        val messagesNullable: List<SessionMessage>? = response.messages
        assertNull(messagesNullable)
    }

    @Test
    fun testDeserialization_malformedJson_throwsJsonSyntaxException() {
        val json = """{"name": "weather", "enabled": """
        org.junit.jupiter.api.Assertions.assertThrows(com.google.gson.JsonSyntaxException::class.java) {
            gson.fromJson(json, Skill::class.java)
        }
    }

    @Test
    fun testStatusResponseDeserialization_allFields() {
        val json =
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
        val response = gson.fromJson(json, StatusResponse::class.java)
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
        val json = "{}"
        val response = gson.fromJson(json, StatusResponse::class.java)
        assertNull(response.version)
        assertNull(response.gateway_running)
        assertNull(response.active_sessions)
        assertNull(response.auth_required)
        assertNull(response.gateway_platforms)
    }

    @Test
    fun testStatusResponseDeserialization_gatewayPlatformsNullValue() {
        val json =
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
        val response = gson.fromJson(json, StatusResponse::class.java)
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
        val json =
            """
            {
                "gateway_platforms": "not_a_map"
            }
            """.trimIndent()
        org.junit.jupiter.api.Assertions.assertThrows(com.google.gson.JsonSyntaxException::class.java) {
            gson.fromJson(json, StatusResponse::class.java)
        }
    }

    @Test
    fun testSystemStatsResponse_deserialization() {
        val json =
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
        val response = gson.fromJson(json, SystemStatsResponse::class.java)
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
        assertEquals(8388608, response.memory?.total)
        assertEquals(50.0, response.disk?.percent ?: 0.0, 0.01)
    }

    @Test
    fun testToggleSkillRequestDeserialization_missingRequiredFields() {
        val json = """{"enabled":true}"""
        val request = gson.fromJson(json, ToggleSkillRequest::class.java)
        val nameNullable: String? = request.name
        assertNull(nameNullable)
        assertEquals(true, request.enabled)
    }

    @Test
    fun testToggleSkillRequestDeserialization_explicitNull() {
        val json = """{"name":null,"enabled":null}"""
        val request = gson.fromJson(json, ToggleSkillRequest::class.java)
        val nameNullable: String? = request.name
        assertNull(nameNullable)
        assertEquals(false, request.enabled)
    }

    @Test
    fun testToggleSkillRequestSerialization_withNullName() {
        val jsonInput = """{"name":null,"enabled":true}"""
        val request = gson.fromJson(jsonInput, ToggleSkillRequest::class.java)

        val jsonOutput = gson.toJson(request)
        val parsed = gson.fromJson(jsonOutput, Map::class.java)
        assertFalse(parsed.containsKey("name"))
        assertEquals(true, parsed["enabled"])
    }
}
