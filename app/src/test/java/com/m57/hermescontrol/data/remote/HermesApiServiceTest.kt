package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.ToggleSkillRequest
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class HermesApiServiceTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiService: HermesApiService

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(mockWebServer.url("/"))
                .addConverterFactory(OkHttpProvider.json.asConverterFactory("application/json".toMediaType()))
                .build()

        apiService = retrofit.create(HermesApiService::class.java)
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testGetSkills_requestsCorrectUrlAndMethod() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""[{"name":"weather","description":"info","category":"utils","enabled":true}]"""),
            )

            val response = apiService.getSkills()
            assertTrue(response.isSuccessful)
            assertEquals(1, response.body()?.size)
            assertEquals("weather", response.body()?.get(0)?.name)

            val recordedRequest = mockWebServer.takeRequest()
            assertEquals("/api/skills", recordedRequest.path)
            assertEquals("GET", recordedRequest.method)
        }

    @Test
    fun testToggleSkill_postsCorrectBody() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(""),
            )

            val response = apiService.toggleSkill(ToggleSkillRequest(name = "weather", enabled = true))
            assertTrue(response.isSuccessful)

            val recordedRequest = mockWebServer.takeRequest()
            assertEquals("/api/skills/toggle", recordedRequest.path)
            assertEquals("PUT", recordedRequest.method)
            assertEquals("""{"name":"weather","enabled":true}""", recordedRequest.body.readUtf8())
        }

    @Test
    fun testGetCronJobs_requestsCorrectUrlAndMethod() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        "[{\"id\":\"job_1\",\"name\":\"backup\",\"schedule\":\"* * * * *\"," +
                            "\"state\":\"active\",\"last_run_status\":null,\"next_run\":null}]",
                    ),
            )

            val response = apiService.getCronJobs()
            assertTrue(response.isSuccessful)
            assertEquals(1, response.body()?.size)
            assertEquals("job_1", response.body()?.get(0)?.id)

            val recordedRequest = mockWebServer.takeRequest()
            assertEquals("/api/cron/jobs", recordedRequest.path)
            assertEquals("GET", recordedRequest.method)
        }

    @Test
    fun testPauseCronJob_sendsPostRequest() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(""))

            val response = apiService.pauseCronJob("job_1")
            assertTrue(response.isSuccessful)

            val recordedRequest = mockWebServer.takeRequest()
            assertEquals("/api/cron/jobs/job_1/pause", recordedRequest.path)
            assertEquals("POST", recordedRequest.method)
        }

    @Test
    fun testResumeCronJob_sendsPostRequest() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(""))

            val response = apiService.resumeCronJob("job_1")
            assertTrue(response.isSuccessful)

            val recordedRequest = mockWebServer.takeRequest()
            assertEquals("/api/cron/jobs/job_1/resume", recordedRequest.path)
            assertEquals("POST", recordedRequest.method)
        }

    @Test
    fun testTriggerCronJob_sendsPostRequest() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(""))

            val response = apiService.triggerCronJob("job_1")
            assertTrue(response.isSuccessful)

            val recordedRequest = mockWebServer.takeRequest()
            assertEquals("/api/cron/jobs/job_1/trigger", recordedRequest.path)
            assertEquals("POST", recordedRequest.method)
        }

    @Test
    fun testDeleteCronJob_sendsDeleteRequest() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(""))

            val response = apiService.deleteCronJob("job_1")
            assertTrue(response.isSuccessful)

            val recordedRequest = mockWebServer.takeRequest()
            assertEquals("/api/cron/jobs/job_1", recordedRequest.path)
            assertEquals("DELETE", recordedRequest.method)
        }

    @Test
    fun testGetSkills_missingFieldsInResponse() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""[{"description":"info"}]"""),
        )

        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            runTest {
                apiService.getSkills()
            }
        }
    }

    @Test
    fun testGetCronJobs_missingFieldsInResponse() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""[{"schedule":"* * * * *"}]"""),
        )

        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            runTest {
                apiService.getCronJobs()
            }
        }
    }

    @Test
    fun testGetSessions_missingSessionsField() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{}"""),
        )

        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            runTest {
                apiService.getSessions()
            }
        }
    }

    @Test
    fun testGetSessionMessages_apiReturnsMalformedJson_throwsException() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"messages": ["""),
        )

        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            runTest {
                apiService.getSessionMessages("session_1")
            }
        }
    }

    @Test
    fun testGetSessionMessages_missingMessagesField() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{}"""),
        )

        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            runTest {
                apiService.getSessionMessages("session_1")
            }
        }
    }

    @Test
    fun testGetSkills_malformedJsonResponse_throwsException() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""[{"name":"weather""""),
            )

            org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
                kotlinx.coroutines.runBlocking {
                    apiService.getSkills()
                }
            }
        }

    @Test
    fun testGetSkills_typeMismatchInResponse_throwsException() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""[{"name":"weather","enabled":{}}]"""),
            )

            org.junit.jupiter.api.Assertions.assertThrows(kotlinx.serialization.SerializationException::class.java) {
                kotlinx.coroutines.runBlocking {
                    apiService.getSkills()
                }
            }
        }

    @Test
    fun testGetStatus_missingFieldsInResponse() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("{}"),
            )

            val response = apiService.getStatus()
            assertTrue(response.isSuccessful)
            val status = response.body()
            assertNotNull(status)
            assertNull(status?.version)
            assertNull(status?.gateway_running)
            assertNull(status?.active_sessions)
            assertNull(status?.auth_required)
            assertNull(status?.gateway_platforms)
        }

    @Test
    fun testGetStatus_gatewayPlatformsNullValue() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"gateway_platforms": {"android": null}}"""),
            )

            val response = apiService.getStatus()
            assertTrue(response.isSuccessful)
            val status = response.body()
            assertNotNull(status)
            val platforms = status?.gateway_platforms
            assertNotNull(platforms)
            assertNull(platforms!!["android"])
        }

    @Test
    fun testGetSystemStats_missingFields() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("{}"),
            )

            val response = apiService.getSystemStats()
            assertTrue(response.isSuccessful)
            val stats = response.body()
            assertNotNull(stats)
            assertNull(stats?.cpu_percent)
            assertNull(stats?.memory?.percent)
        }

    @Test
    fun testGetAnalytics_requestsCorrectPathAndQuery() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """{"daily":[],"by_model":[""" +
                            """{"model":"gpt-4o","input_tokens":10,"output_tokens":20,""" +
                            """"estimated_cost":0.01,"sessions":1,"api_calls":2}],""" +
                            """"totals":{"total_input":10,"total_output":20,""" +
                            """"total_estimated_cost":0.01,"total_sessions":1,"total_api_calls":2},""" +
                            """"skills":{"summary":{"total_skill_loads":0,"distinct_skills_used":0},""" +
                            """"top_skills":[]}}""",
                    ),
            )

            val response = apiService.getAnalytics(30, null)
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(1, body?.by_model?.size)
            assertEquals("gpt-4o", body?.by_model?.first()?.model)
            assertEquals(0.01, body?.totals?.total_estimated_cost)

            val recorded = mockWebServer.takeRequest()
            assertEquals("/api/analytics/usage?days=30", recorded.path)
            assertEquals("GET", recorded.method)
        }

    @Test
    fun testGetModelsAnalytics_forwardsProfileParam() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """{"models":[""" +
                            """{"model":"claude-opus","provider":"anthropic","input_tokens":5,"output_tokens":7,""" +
                            """"cache_read_tokens":1,"reasoning_tokens":2,"estimated_cost":0.05,"actual_cost":0.04,""" +
                            """"sessions":3,"api_calls":9,"tool_calls":1,""" +
                            """"last_used_at":1700000000.0,"avg_tokens_per_session":4.0}],""" +
                            """"totals":{"distinct_models":1,"total_input":5,"total_output":7,"total_cache_read":1,""" +
                            """"total_reasoning":2,"total_estimated_cost":0.05,"total_actual_cost":0.04,""" +
                            """"total_sessions":3,"total_api_calls":9},"period_days":30}""",
                    ),
            )

            val response = apiService.getModelsAnalytics(30, "work")
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(1, body?.models?.size)
            assertEquals("claude-opus", body?.models?.first()?.model)
            assertEquals("anthropic", body?.models?.first()?.provider)
            assertEquals(30, body?.period_days)

            val recorded = mockWebServer.takeRequest()
            assertEquals("/api/analytics/models?days=30&profile=work", recorded.path)
        }

    @Test
    fun testGetAnalytics_missingTotalsUsesDefaults_notNullable() =
        runTest {
            // Backend omits `totals` and most daily fields in some responses;
            // the model uses non-null defaults, so parsing must NOT fail or
            // produce nulls (counter-check to a review claim that fields are
            // nullable). Verifies kotlinx.serialization fills defaults.
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """{"daily":[{"day":"2026-07-01"}],"by_model":[]}""",
                    ),
            )

            val response = apiService.getAnalytics(7, null)
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            // totals defaults (non-null), not null
            assertNotNull(body?.totals)
            assertEquals(0, body?.totals?.total_input)
            assertEquals(0.0, body?.totals?.total_estimated_cost)
            // daily entry sparse fields default (non-null)
            assertEquals(1, body?.daily?.size)
            assertEquals("2026-07-01", body?.daily?.first()?.day)
            assertEquals(0L, body?.daily?.first()?.input_tokens)
        }

    @Test
    fun testGetAnalytics_parsesExactBackendShape() =
        runTest {
            // Exact shape produced by hermes_cli/web_server.py get_usage_analytics,
            // including the extra top-level `period_days` and `tools` keys (the
            // mobile model ignores `tools` and captures `period_days`).
            val json =
                """{"daily":[""" +
                    """{"day":"2026-07-01","input_tokens":100,"output_tokens":200,"api_calls":7}],""" +
                    """"by_model":[{"model":"gpt-4o","input_tokens":100,"api_calls":7}],""" +
                    """"totals":{"total_input":100,"total_estimated_cost":0.03,"total_api_calls":7},""" +
                    """"period_days":7,"skills":{"summary":{"distinct_skills_used":2},""" +
                    """"top_skills":[{"skill":"x","total_count":1,"percentage":50.0,"last_used_at":1700000000.0}]},""" +
                    """"tools":[{"name":"web_search"}]}"""
            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody(json),
            )

            val response = apiService.getAnalytics(7, null)
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(7, body?.period_days)
            assertEquals(1, body?.daily?.size)
            assertEquals(100L, body?.daily?.first()?.input_tokens)
            assertEquals(0.03, body?.totals?.total_estimated_cost)
            assertEquals(2, body?.skills?.summary?.distinct_skills_used)
            assertEquals("x", body?.skills?.top_skills?.first()?.skill)
            assertEquals(1, body?.tools?.size) // extra key parsed, not dropped
        }
}
