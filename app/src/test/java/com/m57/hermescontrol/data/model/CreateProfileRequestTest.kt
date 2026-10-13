package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blind tests for R1549: CreateProfileRequest payload serialization and contract invariants.
 *
 * Requirements from contracts.md (R1549) / #1549:
 * - CreateProfileRequest.keep_skills becomes List<String>? = null
 * - append no_skills: Boolean? = null at end
 * - Real OkHttpProvider.json (or app wire Json) serialization:
 *   - Default builder: both keep_skills and no_skills omitted (null)
 *   - Opt-out (useDefaultSkills = false): no_skills = true, keep_skills omitted (null)
 *   - Explicit false: no_skills = false is preserved (not omitted if explicitly false or test explicit false)
 *   - Retained non-empty list: keep_skills = listOf("skill-a", "skill-b") encoded as JSON array of strings
 *   - Empty list: keep_skills = emptyList() is encoded as empty array [] and never interpreted as no_skills
 *   - Hub skills / clone_from / MCP options / copy_model / provider / etc. preserved across payloads
 *   - Opt-out + hub-skills combination preserved: no_skills = true and hub_skills = listOf(...)
 */
class CreateProfileRequestTest {
    private val json: Json = OkHttpProvider.json

    @Test
    fun defaultBuilder_omitsBothKeepSkillsAndNoSkills() {
        val request = CreateProfileRequest(name = "minimal-profile")
        val jsonElement = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals("minimal-profile", jsonElement["name"]?.jsonPrimitive?.content)
        assertFalse(
            "keep_skills should be omitted when null (default)",
            jsonElement.containsKey("keep_skills"),
        )
        assertFalse(
            "no_skills should be omitted when null (default)",
            jsonElement.containsKey("no_skills"),
        )
    }

    @Test
    fun optOut_noSkillsTrue_keepSkillsOmitted() {
        val request =
            CreateProfileRequest(
                name = "no-skills-profile",
                no_skills = true,
                keep_skills = null,
            )
        val jsonElement = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals("no-skills-profile", jsonElement["name"]?.jsonPrimitive?.content)
        assertEquals(true, jsonElement["no_skills"]?.jsonPrimitive?.booleanOrNull)
        assertFalse(
            "keep_skills should be omitted when null",
            jsonElement.containsKey("keep_skills"),
        )
    }

    @Test
    fun explicitFalse_noSkillsFalse_isPreservedInJson() {
        val request =
            CreateProfileRequest(
                name = "explicit-false-profile",
                no_skills = false,
            )
        val jsonElement = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals("explicit-false-profile", jsonElement["name"]?.jsonPrimitive?.content)
        assertTrue("no_skills key must exist when explicitly set to false", jsonElement.containsKey("no_skills"))
        assertEquals(false, jsonElement["no_skills"]?.jsonPrimitive?.booleanOrNull)
        assertFalse("keep_skills must be omitted when null", jsonElement.containsKey("keep_skills"))
    }

    @Test
    fun retainedNonEmptySubset_serializesAsJsonArray() {
        val retained = listOf("memory", "web-search", "coding")
        val request =
            CreateProfileRequest(
                name = "curated-profile",
                keep_skills = retained,
                no_skills = null,
            )
        val jsonElement = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals("curated-profile", jsonElement["name"]?.jsonPrimitive?.content)
        assertTrue("keep_skills array must be present", jsonElement.containsKey("keep_skills"))
        val array = jsonElement["keep_skills"]?.jsonArray
        assertEquals(3, array?.size)
        assertEquals("memory", array?.get(0)?.jsonPrimitive?.content)
        assertEquals("web-search", array?.get(1)?.jsonPrimitive?.content)
        assertEquals("coding", array?.get(2)?.jsonPrimitive?.content)
        assertFalse("no_skills must be omitted when null", jsonElement.containsKey("no_skills"))
    }

    @Test
    fun emptyList_neverInterpretedAsNoSkills_serializesAsEmptyArray() {
        val request =
            CreateProfileRequest(
                name = "empty-keep-profile",
                keep_skills = emptyList(),
                no_skills = null,
            )
        val jsonElement = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals("empty-keep-profile", jsonElement["name"]?.jsonPrimitive?.content)
        assertTrue(
            "keep_skills must be serialized if explicitly passed as emptyList",
            jsonElement.containsKey("keep_skills"),
        )
        val array = jsonElement["keep_skills"]?.jsonArray
        assertEquals(0, array?.size)
        assertFalse("no_skills must remain omitted and not coerced to true", jsonElement.containsKey("no_skills"))
    }

    @Test
    fun optOutCombinedWithHubSkills_preservesBothOptions() {
        val request =
            CreateProfileRequest(
                name = "opt-out-plus-hub",
                hub_skills = listOf("community/research-tool", "org/git-helper"),
                no_skills = true,
                keep_skills = null,
            )
        val jsonElement = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals("opt-out-plus-hub", jsonElement["name"]?.jsonPrimitive?.content)
        assertEquals(true, jsonElement["no_skills"]?.jsonPrimitive?.booleanOrNull)
        assertFalse("keep_skills must be omitted", jsonElement.containsKey("keep_skills"))
        assertTrue("hub_skills must be present", jsonElement.containsKey("hub_skills"))
        val hubList = jsonElement["hub_skills"]?.jsonArray
        assertEquals(2, hubList?.size)
        assertEquals("community/research-tool", hubList?.get(0)?.jsonPrimitive?.content)
        assertEquals("org/git-helper", hubList?.get(1)?.jsonPrimitive?.content)
    }

    @Test
    fun fullOptionsPreserved_cloneFrom_mcp_provider() {
        val request =
            CreateProfileRequest(
                name = "complete-profile",
                description = "A comprehensive profile",
                clone_from = "base-profile",
                clone_all = false,
                model = "gpt-4o",
                provider = "openai",
                clone_from_default = false,
                hub_skills = listOf("skill-1"),
                mcp_servers = listOf(McpServerConfigInput("server-a", "stdio", command = "node")),
                keep_skills = listOf("preserved-skill"),
                no_skills = false,
            )
        val jsonElement = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals("complete-profile", jsonElement["name"]?.jsonPrimitive?.content)
        assertEquals("A comprehensive profile", jsonElement["description"]?.jsonPrimitive?.content)
        assertEquals("base-profile", jsonElement["clone_from"]?.jsonPrimitive?.content)
        assertEquals(false, jsonElement["clone_all"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("gpt-4o", jsonElement["model"]?.jsonPrimitive?.content)
        assertEquals("openai", jsonElement["provider"]?.jsonPrimitive?.content)
        assertEquals(false, jsonElement["clone_from_default"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(1, jsonElement["hub_skills"]?.jsonArray?.size)
        assertEquals(1, jsonElement["mcp_servers"]?.jsonArray?.size)
        val server = jsonElement["mcp_servers"]!!.jsonArray.single().jsonObject
        assertEquals("server-a", server["name"]?.jsonPrimitive?.content)
        assertEquals("stdio", server["transport"]?.jsonPrimitive?.content)
        assertEquals("node", server["command"]?.jsonPrimitive?.content)
        assertEquals(1, jsonElement["keep_skills"]?.jsonArray?.size)
        assertEquals(
            "preserved-skill",
            jsonElement["keep_skills"]
                ?.jsonArray
                ?.get(0)
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(false, jsonElement["no_skills"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun roundTripDeserialization_handlesKeepSkillsAndNoSkills() {
        val rawJson =
            """
            {
                "name": "round-trip-profile",
                "keep_skills": ["a", "b"],
                "no_skills": true
            }
            """.trimIndent()

        val decoded = json.decodeFromString<CreateProfileRequest>(rawJson)
        assertEquals("round-trip-profile", decoded.name)
        assertEquals(listOf("a", "b"), decoded.keep_skills)
        assertEquals(true, decoded.no_skills)
    }

    @Test
    fun roundTripDeserialization_missingKeepSkillsAndNoSkills_defaultsToNull() {
        val rawJson =
            """
            {
                "name": "bare-profile"
            }
            """.trimIndent()

        val decoded = json.decodeFromString<CreateProfileRequest>(rawJson)
        assertEquals("bare-profile", decoded.name)
        assertNull(decoded.keep_skills)
        assertNull(decoded.no_skills)
    }
}
