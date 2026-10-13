package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.SkillContentResponse
import com.m57.hermescontrol.data.model.SkillHubPreviewResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Blind R1548 producer-shaped Retrofit decoding regression tests.
 *
 * Verifies contract:
 * - SkillHubPreviewResponse(name: String, skillMd: String, description: String = "", identifier: String = "", files: List<String> = emptyList())
 * - name + skillMd are REQUIRED; serialized field is "skill_md"
 * - Full metadata + empty provided skill_md is valid
 * - Missing skill_md fails rather than hiding incorrect contract
 * - Missing name fails
 * - Installed skill DTO (SkillContentResponse) remains unchanged with required content
 * - Retrofit HermesApiService.previewHubSkill(identifier) route and converter execution
 */
class SkillHubPreviewConverterTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HermesApiService

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(OkHttpProvider.json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(HermesApiService::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun jsonResponse(
        body: String,
        code: Int = 200,
    ) = server.enqueue(
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body),
    )

    @Test
    fun previewHubSkill_decodesFullMetadataProducerShape() =
        runBlocking {
            val json =
                """
                {
                    "name": "super-agent",
                    "skill_md": "# Super Agent\nInstructions here.",
                    "description": "An awesome skill",
                    "identifier": "community:super-agent",
                    "files": ["references/guide.md", "templates/config.yaml"]
                }
                """.trimIndent()
            jsonResponse(json)

            val response = api.previewHubSkill("community:super-agent")
            assertTrue("Expected successful Retrofit response", response.isSuccessful)

            val recordedRequest = server.takeRequest()
            assertEquals("GET", recordedRequest.method)
            assertTrue(
                "Request path should contain identifier: ${recordedRequest.path}",
                recordedRequest.path?.contains("community%3Asuper-agent") == true ||
                    recordedRequest.path?.contains("community:super-agent") == true,
            )

            val body = response.body()
            assertNotNull("Body should not be null", body)
            assertEquals("super-agent", body?.name)
            assertEquals("# Super Agent\nInstructions here.", body?.skillMd)
            assertEquals("An awesome skill", body?.description)
            assertEquals("community:super-agent", body?.identifier)
            assertEquals(listOf("references/guide.md", "templates/config.yaml"), body?.files)
        }

    @Test
    fun previewHubSkill_decodesWithEmptyProvidedSkillMdAndDefaults() =
        runBlocking {
            val json =
                """
                {
                    "name": "minimal-skill",
                    "skill_md": ""
                }
                """.trimIndent()
            jsonResponse(json)

            val response = api.previewHubSkill("minimal-skill")
            assertTrue("Empty provided skill_md is legitimate and must succeed", response.isSuccessful)

            val body = response.body()
            assertNotNull("Body should not be null", body)
            assertEquals("minimal-skill", body?.name)
            assertEquals("", body?.skillMd)
            assertEquals("", body?.description)
            assertEquals("", body?.identifier)
            assertEquals(emptyList<String>(), body?.files)
        }

    @Test
    fun previewHubSkill_failsWhenSkillMdIsMissing() =
        runBlocking {
            // Missing skill_md must fail rather than hiding incorrect contract
            val json =
                """
                {
                    "name": "broken-skill",
                    "description": "Missing markdown content",
                    "identifier": "hub:broken-skill"
                }
                """.trimIndent()
            jsonResponse(json)

            try {
                api.previewHubSkill("hub:broken-skill")
                fail("Expected SerializationException or IllegalArgumentException due to missing required skill_md")
            } catch (e: SerializationException) {
                // Expected: missing required field
                assertTrue(
                    "Error should mention skill_md or missing field: ${e.message}",
                    e.message?.contains("skill_md") == true ||
                        e.message?.contains("skillMd") == true ||
                        e.message?.contains("missing") == true ||
                        e.message?.contains("Field") == true,
                )
            } catch (e: Exception) {
                // Any deserialization failure caused by missing required property
                assertTrue(
                    "Expected serialization failure, got $e",
                    e is SerializationException || e.cause is SerializationException,
                )
            }
        }

    @Test
    fun previewHubSkill_failsWhenNameIsMissing() =
        runBlocking {
            val json =
                """
                {
                    "skill_md": "# Unnamed skill content"
                }
                """.trimIndent()
            jsonResponse(json)

            try {
                api.previewHubSkill("unnamed")
                fail("Expected SerializationException due to missing required name")
            } catch (e: SerializationException) {
                assertTrue(
                    "Error should mention name or missing field: ${e.message}",
                    e.message?.contains("name") == true ||
                        e.message?.contains("missing") == true ||
                        e.message?.contains("Field") == true,
                )
            } catch (e: Exception) {
                assertTrue(
                    "Expected serialization failure, got $e",
                    e is SerializationException || e.cause is SerializationException,
                )
            }
        }

    @Test
    fun installedSkillContentDTO_remainsUnchangedWithRequiredContent() =
        runBlocking {
            // Verify SkillContentResponse contract invariant: name and content are required
            val validInstalledJson =
                """
                {
                    "name": "installed-weather",
                    "content": "# Weather Skill\nDoes weather forecasting."
                }
                """.trimIndent()
            jsonResponse(validInstalledJson)

            val response = api.getSkillContent("installed-weather")
            assertTrue("Valid installed skill content must succeed", response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals("installed-weather", body?.name)
            assertEquals("# Weather Skill\nDoes weather forecasting.", body?.content)

            // Missing content field must fail for installed skill
            val missingContentJson =
                """
                {
                    "name": "installed-weather"
                }
                """.trimIndent()
            jsonResponse(missingContentJson)

            try {
                api.getSkillContent("installed-weather")
                fail("Expected SerializationException when installed skill content is missing")
            } catch (e: SerializationException) {
                assertTrue(
                    "Error should mention content: ${e.message}",
                    e.message?.contains("content") == true || e.message?.contains("missing") == true,
                )
            } catch (e: Exception) {
                assertTrue(
                    "Expected serialization failure, got $e",
                    e is SerializationException || e.cause is SerializationException,
                )
            }
        }
}
