package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.BulkTasksBody
import com.m57.hermescontrol.data.model.CreateBoardBody
import com.m57.hermescontrol.data.model.CreateTaskBody
import com.m57.hermescontrol.data.model.OrchestrationSettingsUpdate
import com.m57.hermescontrol.data.model.ReassignTaskBody
import com.m57.hermescontrol.data.model.ReclaimTaskBody
import com.m57.hermescontrol.data.model.RenameBoardBody
import com.m57.hermescontrol.data.model.UpdateTaskBody
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class KanbanApiServiceTest {
    private lateinit var mockServer: MockWebServer
    private lateinit var api: KanbanApiService

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()

        api =
            Retrofit
                .Builder()
                .baseUrl(mockServer.url("/"))
                .addConverterFactory(OkHttpProvider.json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(KanbanApiService::class.java)
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun testGetBoards() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "boards": [
                                { "slug": "default", "name": "Default Board", "total": 5, "is_current": true }
                            ],
                            "current": "default"
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.getBoards(includeArchived = true)
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(1, body?.boards?.size)
            assertEquals("default", body?.boards?.get(0)?.id)
            assertEquals("Default Board", body?.boards?.get(0)?.name)
            assertEquals("default", body?.current)

            val request = mockServer.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/plugins/kanban/boards?include_archived=true", request.path)
        }

    @Test
    fun testGetBoardScoped() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "columns": [
                                { "name": "ready", "tasks": [] }
                            ],
                            "assignees": ["lead"],
                            "tenants": [],
                            "latest_event_id": 99,
                            "now": 1718000000
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.getBoard(board = "dev", includeArchived = false, tenant = "team-a")
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(1, body?.columns?.size)
            assertEquals("ready", body?.columns?.get(0)?.name)

            val request = mockServer.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.path?.contains("board=dev") == true)
            assertTrue(request.path?.contains("tenant=team-a") == true)
        }

    @Test
    fun testCreateAndPatchTask() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "task": {
                                "id": "t_new",
                                "title": "Implement feature",
                                "status": "triage"
                            },
                            "warning": "Dispatcher not running"
                        }
                        """.trimIndent(),
                    ),
            )

            val createRes =
                api.createTask(
                    board = "dev",
                    body =
                        CreateTaskBody(
                            title = "Implement feature",
                            triage = true,
                        ),
                )
            assertTrue(createRes.isSuccessful)
            assertEquals("t_new", createRes.body()?.task?.id)
            assertEquals("Dispatcher not running", createRes.body()?.warning)

            val createReq = mockServer.takeRequest()
            assertEquals("POST", createReq.method)
            assertEquals("/api/plugins/kanban/tasks?board=dev", createReq.path)
            assertTrue(createReq.body.readUtf8().contains("\"triage\":true"))

            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "task": {
                                "id": "t_new",
                                "title": "Implement feature",
                                "status": "ready"
                            }
                        }
                        """.trimIndent(),
                    ),
            )

            val patchRes =
                api.updateTask(
                    taskId = "t_new",
                    board = "dev",
                    body =
                        UpdateTaskBody(
                            status = "ready",
                        ),
                )
            assertTrue(patchRes.isSuccessful)
            assertEquals("ready", patchRes.body()?.task?.status)

            val patchReq = mockServer.takeRequest()
            assertEquals("PATCH", patchReq.method)
            assertEquals("/api/plugins/kanban/tasks/t_new?board=dev", patchReq.path)
        }

    @Test
    fun testBulkTasks() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "results": [
                                { "id": "t_1", "ok": true },
                                { "id": "t_2", "ok": false, "error": "transition refused" }
                            ]
                        }
                        """.trimIndent(),
                    ),
            )

            val res =
                api.bulkTasks(
                    board = "dev",
                    body =
                        BulkTasksBody(
                            ids = listOf("t_1", "t_2"),
                            status = "done",
                        ),
                )
            assertTrue(res.isSuccessful)
            val results = res.body()?.results
            assertEquals(2, results?.size)
            assertTrue(results?.get(0)?.ok == true)
            assertEquals("transition refused", results?.get(1)?.error)

            val req = mockServer.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/api/plugins/kanban/tasks/bulk?board=dev", req.path)
        }

    @Test
    fun testReassignAndReclaim() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{ "ok": true, "task_id": "t_1", "assignee": "reviewer" }"""),
            )

            val reassignRes =
                api.reassignTask(
                    taskId = "t_1",
                    board = "dev",
                    body = ReassignTaskBody(profile = "reviewer", reclaimFirst = true),
                )
            assertTrue(reassignRes.isSuccessful)
            assertEquals("reviewer", reassignRes.body()?.assignee)

            val reassignReq = mockServer.takeRequest()
            assertEquals("POST", reassignReq.method)
            assertEquals("/api/plugins/kanban/tasks/t_1/reassign?board=dev", reassignReq.path)

            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{ "ok": true, "task_id": "t_1" }"""),
            )

            val reclaimRes =
                api.reclaimTask(
                    taskId = "t_1",
                    board = "dev",
                    body = ReclaimTaskBody(reason = "Worker unresponsive"),
                )
            assertTrue(reclaimRes.isSuccessful)

            val reclaimReq = mockServer.takeRequest()
            assertEquals("POST", reclaimReq.method)
            assertEquals("/api/plugins/kanban/tasks/t_1/reclaim?board=dev", reclaimReq.path)
        }

    @Test
    fun testOrchestrationAndDispatchNudge() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "orchestrator_profile": "boss",
                            "default_assignee": "worker",
                            "auto_decompose": true,
                            "auto_promote_children": true,
                            "resolved_orchestrator_profile": "boss",
                            "resolved_default_assignee": "worker",
                            "active_profile": "default"
                        }
                        """.trimIndent(),
                    ),
            )

            val updateRes =
                api.updateOrchestration(
                    OrchestrationSettingsUpdate(
                        orchestratorProfile = "boss",
                        defaultAssignee = "worker",
                    ),
                )
            assertTrue(updateRes.isSuccessful)
            assertEquals("boss", updateRes.body()?.orchestratorProfile)

            val putReq = mockServer.takeRequest()
            assertEquals("PUT", putReq.method)
            assertEquals("/api/plugins/kanban/orchestration", putReq.path)

            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{ "spawned": [] }"""),
            )

            val nudgeRes = api.nudgeDispatcher(board = "dev")
            assertTrue(nudgeRes.isSuccessful)

            val nudgeReq = mockServer.takeRequest()
            assertEquals("POST", nudgeReq.method)
            assertEquals("/api/plugins/kanban/dispatch?board=dev", nudgeReq.path)
        }
}
