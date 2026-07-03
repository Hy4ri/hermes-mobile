package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.ToggleSkillRequest
import kotlinx.coroutines.test.runTest
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
import retrofit2.converter.gson.GsonConverterFactory

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
                .addConverterFactory(GsonConverterFactory.create())
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
    fun testGetSkills_missingFieldsInResponse() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""[{"description":"info"}]"""),
            )

            val response = apiService.getSkills()
            assertTrue(response.isSuccessful)
            val skills = response.body()
            assertEquals(1, skills?.size)
            val skill = skills?.get(0)
            org.junit.jupiter.api.Assertions
                .assertNull(skill?.name)
            assertEquals(false, skill?.enabled)
        }

    @Test
    fun testGetCronJobs_missingFieldsInResponse() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""[{"schedule":"* * * * *"}]"""),
            )

            val response = apiService.getCronJobs()
            assertTrue(response.isSuccessful)
            val jobs = response.body()
            assertEquals(1, jobs?.size)
            val job = jobs?.get(0)
            org.junit.jupiter.api.Assertions
                .assertNull(job?.id)
            org.junit.jupiter.api.Assertions
                .assertNull(job?.name)
        }

    @Test
    fun testGetSessions_missingSessionsField() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{}"""),
            )

            val response = apiService.getSessions()
            assertTrue(response.isSuccessful)
            org.junit.jupiter.api.Assertions
                .assertNull(response.body()?.sessions)
        }

    @Test
    fun testGetSessionMessages_apiReturnsMalformedJson_throwsException() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"messages": ["""),
        )

        assertThrows(java.io.EOFException::class.java) {
            runTest {
                apiService.getSessionMessages("session_1")
            }
        }
    }

    @Test
    fun testGetSessionMessages_missingMessagesField() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{}"""),
            )

            val response = apiService.getSessionMessages("session_1")
            assertTrue(response.isSuccessful)
            org.junit.jupiter.api.Assertions
                .assertNull(response.body()?.messages)
        }

    @Test
    fun testGetSkills_malformedJsonResponse_throwsException() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""[{"name":"weather""""),
            )

            org.junit.jupiter.api.Assertions.assertThrows(java.io.EOFException::class.java) {
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

            org.junit.jupiter.api.Assertions.assertThrows(com.google.gson.JsonSyntaxException::class.java) {
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
    fun testGetSystemStats_nonNumericPercent_returnsNull() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"cpu_percent":"not_a_number"}"""),
            )

            val response = apiService.getSystemStats()
            assertTrue(response.isSuccessful)
            val stats = response.body()
            assertNotNull(stats)
            assertNull(stats?.cpu_percent)
        }

    @Test
    fun testGetSystemStats_typeMismatch_cpuPercentArray_returnsNull() =
        runTest {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"cpu_percent":[]}"""),
            )

            val response = apiService.getSystemStats()
            assertTrue(response.isSuccessful)
            val stats = response.body()
            assertNotNull(stats)
            assertNull(stats?.cpu_percent)
        }
}
