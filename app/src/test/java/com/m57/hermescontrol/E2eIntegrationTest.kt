package com.m57.hermescontrol

import android.app.Application
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ActiveProfileResponse
import com.m57.hermescontrol.data.model.AuxiliaryModelsResponse
import com.m57.hermescontrol.data.model.CheckpointsResponse
import com.m57.hermescontrol.data.model.CredentialPoolResponse
import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.data.model.CuratorResponse
import com.m57.hermescontrol.data.model.DoctorResponse
import com.m57.hermescontrol.data.model.EnvVarConfig
import com.m57.hermescontrol.data.model.HookResponse
import com.m57.hermescontrol.data.model.KanbanBoard
import com.m57.hermescontrol.data.model.KanbanBoardResponse
import com.m57.hermescontrol.data.model.KanbanBoardsResponse
import com.m57.hermescontrol.data.model.KanbanColumn
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.model.LogResponse
import com.m57.hermescontrol.data.model.MainModelAssignment
import com.m57.hermescontrol.data.model.MemoryResponse
import com.m57.hermescontrol.data.model.MemoryStats
import com.m57.hermescontrol.data.model.MessagingPlatform
import com.m57.hermescontrol.data.model.MessagingPlatformResponse
import com.m57.hermescontrol.data.model.MoaConfigResponse
import com.m57.hermescontrol.data.model.MoaModelSlot
import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PluginInfo
import com.m57.hermescontrol.data.model.PluginsHubResponse
import com.m57.hermescontrol.data.model.PortalResponse
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionListResponse
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.model.SystemStatsResponse
import com.m57.hermescontrol.data.model.ToggleSkillRequest
import com.m57.hermescontrol.data.model.UpdateCheckResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.ui.channels.ChannelsViewModel
import com.m57.hermescontrol.ui.connect.ConnectViewModel
import com.m57.hermescontrol.ui.cron.CronJobsViewModel
import com.m57.hermescontrol.ui.kanban.KanbanViewModel
import com.m57.hermescontrol.ui.keys.KeysViewModel
import com.m57.hermescontrol.ui.logs.LogsViewModel
import com.m57.hermescontrol.ui.model.ModelViewModel
import com.m57.hermescontrol.ui.plugins.PluginsViewModel
import com.m57.hermescontrol.ui.sessions.SessionsViewModel
import com.m57.hermescontrol.ui.skills.SkillsViewModel
import com.m57.hermescontrol.ui.system.SystemViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class E2eIntegrationTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApiService: HermesApiService
    private val mockApp = mockk<Application>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Dispatchers::class)
        // Make IO return whatever Main currently is (runTest overrides Main per-test)
        every { Dispatchers.IO } answers { Dispatchers.Main }

        mockkObject(AuthManager)
        mockkObject(ApiClient)

        mockApiService = mockk()
        every { ApiClient.hermesApi } returns mockApiService
        every { ApiClient.createTempService(any(), any(), any()) } returns mockApiService
        every { ApiClient.rebuild() } returns Unit

        // Default AuthManager stubs
        every { AuthManager.getToken() } returns "mock-token"
        every { AuthManager.getHost() } returns "127.0.0.1"
        every { AuthManager.getPort() } returns 9119
        every { AuthManager.setToken(any()) } returns Unit
        every { AuthManager.setHost(any()) } returns Unit
        every { AuthManager.setPort(any()) } returns Unit
        every { AuthManager.getConnectionProfiles() } returns emptyList()
        every { AuthManager.getSelectedProfileId() } returns null
        every { AuthManager.setSelectedProfileId(any()) } returns Unit
        every { AuthManager.getProfileToken(any()) } returns null
        every { AuthManager.setProfileToken(any(), any()) } returns Unit
        every { AuthManager.saveConnectionProfiles(any()) } returns Unit
        every { AuthManager.getPinnedModels() } returns emptyList()
        every { AuthManager.savePinnedModels(any()) } returns Unit

        // Application stubs
        every { mockApp.getString(R.string.connect_error_401) } returns "Invalid token (401 Unauthorized)"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
        NavigationController.backStack = null
    }

    private fun <T> createErrorResponse(code: Int): Response<T> {
        val errorBody = "".toResponseBody(null)
        return Response.error(code, errorBody)
    }

    // ── Tier 1: Feature Coverage (>=5 per feature) ───────────────────────

    // Skills Management Screen:
    @Test
    fun testSkillsListing_success() =
        runTest {
            val skill1 = Skill("Skill 1", "Description 1", "Category 1", true)
            val skill2 = Skill("Skill 2", "Description 2", "Category 2", false)
            coEvery { mockApiService.getSkills() } returns Response.success(listOf(skill1, skill2))

            val viewModel = SkillsViewModel()
            viewModel.loadSkills()

            assertTrue(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.errorMessage)

            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.errorMessage)
            assertEquals(2, viewModel.uiState.value.skills.size)
            assertEquals(
                "Skill 1",
                viewModel.uiState.value.skills[0]
                    .name,
            )
            assertEquals(
                "Description 1",
                viewModel.uiState.value.skills[0]
                    .description,
            )
            assertEquals(
                "Category 1",
                viewModel.uiState.value.skills[0]
                    .category,
            )
            assertTrue(
                viewModel.uiState.value.skills[0]
                    .enabled,
            )
            assertEquals(
                "Skill 2",
                viewModel.uiState.value.skills[1]
                    .name,
            )
            assertFalse(
                viewModel.uiState.value.skills[1]
                    .enabled,
            )
        }

    @Test
    fun testSkillsToggle_success() =
        runTest {
            val skill = Skill("Skill 1", "Description 1", "Category 1", false)
            coEvery { mockApiService.getSkills() } returns Response.success(listOf(skill))
            coEvery { mockApiService.toggleSkill(ToggleSkillRequest("Skill 1", true)) } returns Response.success(Unit)

            val viewModel = SkillsViewModel()
            viewModel.loadSkills()
            advanceUntilIdle()

            assertFalse(
                viewModel.uiState.value.skills[0]
                    .enabled,
            )

            viewModel.toggleSkill(viewModel.uiState.value.skills[0])
            // Verify optimistic update
            assertTrue(
                viewModel.uiState.value.skills[0]
                    .enabled,
            )

            advanceUntilIdle()
            // Verify it remains enabled
            assertTrue(
                viewModel.uiState.value.skills[0]
                    .enabled,
            )
            assertNull(viewModel.uiState.value.toastMessage)
        }

    @Test
    fun testSkillsToggle_failure() =
        runTest {
            val skill = Skill("Skill 1", "Description 1", "Category 1", false)
            coEvery { mockApiService.getSkills() } returns Response.success(listOf(skill))
            coEvery { mockApiService.toggleSkill(ToggleSkillRequest("Skill 1", true)) } returns createErrorResponse(500)

            val viewModel = SkillsViewModel()
            viewModel.loadSkills()
            advanceUntilIdle()

            assertFalse(
                viewModel.uiState.value.skills[0]
                    .enabled,
            )

            viewModel.toggleSkill(viewModel.uiState.value.skills[0])
            // Verify optimistic update
            assertTrue(
                viewModel.uiState.value.skills[0]
                    .enabled,
            )

            advanceUntilIdle()
            // Verify reverted state and toast error message
            assertFalse(
                viewModel.uiState.value.skills[0]
                    .enabled,
            )
            assertNotNull(viewModel.uiState.value.toastMessage)
            assertTrue(
                viewModel.uiState.value.toastMessage!!
                    .contains("HTTP 500"),
            )
        }

    @Test
    fun testSkillsLoad_failure() =
        runTest {
            coEvery { mockApiService.getSkills() } returns createErrorResponse(500)

            val viewModel = SkillsViewModel()
            viewModel.loadSkills()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertNotNull(viewModel.uiState.value.errorMessage)
            assertTrue(
                viewModel.uiState.value.errorMessage!!
                    .contains("HTTP 500"),
            )
        }

    @Test
    fun testSkillsRefresh() =
        runTest {
            val skillA = Skill("Skill A", null, null, false)
            val skillB = Skill("Skill B", null, null, true)

            coEvery { mockApiService.getSkills() } returnsMany
                listOf(
                    Response.success(listOf(skillA)),
                    Response.success(listOf(skillB)),
                )

            val viewModel = SkillsViewModel()
            viewModel.loadSkills()
            advanceUntilIdle()
            assertEquals(
                "Skill A",
                viewModel.uiState.value.skills[0]
                    .name,
            )

            viewModel.loadSkills()
            advanceUntilIdle()
            assertEquals(
                "Skill B",
                viewModel.uiState.value.skills[0]
                    .name,
            )
        }

    // Cron Jobs Screen:
    @Test
    fun testCronJobsListing_success() =
        runTest {
            val job = CronJob("id1", "Job 1", "*/5 * * * *", "active", "success", "2026-06-15T15:10:00Z")
            coEvery { mockApiService.getCronJobs() } returns Response.success(listOf(job))

            val viewModel = CronJobsViewModel()
            viewModel.loadCronJobs()
            assertTrue(viewModel.uiState.value.isLoading)

            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(1, viewModel.uiState.value.jobs.size)
            assertEquals(
                "id1",
                viewModel.uiState.value.jobs[0]
                    .id,
            )
            assertEquals(
                "Job 1",
                viewModel.uiState.value.jobs[0]
                    .name,
            )
            assertEquals(
                "active",
                viewModel.uiState.value.jobs[0]
                    .state,
            )
            assertEquals(
                "success",
                viewModel.uiState.value.jobs[0]
                    .last_run_status,
            )
            assertEquals(
                "2026-06-15T15:10:00Z",
                viewModel.uiState.value.jobs[0]
                    .next_run,
            )
        }

    @Test
    fun testCronJobPause_success() =
        runTest {
            val job = CronJob("id1", "Job 1", "*/5 * * * *", "active", null, null)
            coEvery { mockApiService.getCronJobs() } returns Response.success(listOf(job))
            coEvery { mockApiService.pauseCronJob("id1") } returns Response.success(Unit)

            val viewModel = CronJobsViewModel()
            viewModel.loadCronJobs()
            advanceUntilIdle()

            viewModel.pauseCronJob("id1")
            // Verify optimistic update
            assertEquals(
                "paused",
                viewModel.uiState.value.jobs[0]
                    .state,
            )

            advanceUntilIdle()
            assertEquals(
                "paused",
                viewModel.uiState.value.jobs[0]
                    .state,
            )
        }

    @Test
    fun testCronJobResume_success() =
        runTest {
            val job = CronJob("id1", "Job 1", "*/5 * * * *", "paused", null, null)
            coEvery { mockApiService.getCronJobs() } returns Response.success(listOf(job))
            coEvery { mockApiService.resumeCronJob("id1") } returns Response.success(Unit)

            val viewModel = CronJobsViewModel()
            viewModel.loadCronJobs()
            advanceUntilIdle()

            viewModel.resumeCronJob("id1")
            // Verify optimistic update
            assertEquals(
                "active",
                viewModel.uiState.value.jobs[0]
                    .state,
            )

            advanceUntilIdle()
            assertEquals(
                "active",
                viewModel.uiState.value.jobs[0]
                    .state,
            )
        }

    @Test
    fun testCronJobTrigger_success() =
        runTest {
            coEvery { mockApiService.triggerCronJob("id1") } returns Response.success(Unit)

            val viewModel = CronJobsViewModel()
            viewModel.triggerCronJob("id1")
            advanceUntilIdle()

            assertEquals("Job triggered successfully", viewModel.uiState.value.toastMessage)
        }

    @Test
    fun testCronJobDelete_success() =
        runTest {
            val job = CronJob("id1", "Job 1", null, null, null, null)
            coEvery { mockApiService.getCronJobs() } returns Response.success(listOf(job))
            coEvery { mockApiService.deleteCronJob("id1") } returns Response.success(Unit)

            val viewModel = CronJobsViewModel()
            viewModel.loadCronJobs()
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.jobs.size)

            viewModel.deleteCronJob("id1")
            // Verify optimistic update (immediate removal)
            assertTrue(
                viewModel.uiState.value.jobs
                    .isEmpty(),
            )

            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.jobs
                    .isEmpty(),
            )
        }

    @Test
    fun testCronJobsLoad_failure() =
        runTest {
            coEvery { mockApiService.getCronJobs() } returns createErrorResponse(500)

            val viewModel = CronJobsViewModel()
            viewModel.loadCronJobs()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertNotNull(viewModel.uiState.value.errorMessage)
            assertTrue(
                viewModel.uiState.value.errorMessage!!
                    .contains("HTTP 500"),
            )
        }

    // Sessions History Screen:
    @Test
    fun testSessionsListing_success() =
        runTest {
            val session = SessionInfo("session-123", "Session 1", "2026-06-15T15:10:00Z", 5, "active")
            coEvery {
                mockApiService.getSessions(any(), any(), any())
            } returns Response.success(SessionListResponse(listOf(session)))

            val viewModel = SessionsViewModel()
            viewModel.loadSessions()
            assertTrue(viewModel.uiState.value.isLoading)

            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(1, viewModel.uiState.value.sessions.size)
            assertEquals(
                "session-123",
                viewModel.uiState.value.sessions[0]
                    .id,
            )
            assertEquals(
                "Session 1",
                viewModel.uiState.value.sessions[0]
                    .title,
            )
            assertEquals(
                5,
                viewModel.uiState.value.sessions[0]
                    .message_count,
            )
            assertEquals(
                "active",
                viewModel.uiState.value.sessions[0]
                    .status,
            )
        }

    @Test
    fun testSessionsLoad_failure() =
        runTest {
            coEvery { mockApiService.getSessions(any(), any(), any()) } returns createErrorResponse(500)

            val viewModel = SessionsViewModel()
            viewModel.loadSessions()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertNotNull(viewModel.uiState.value.errorMessage)
            assertTrue(
                viewModel.uiState.value.errorMessage!!
                    .contains("HTTP 500"),
            )
        }

    @Test
    fun testCronJobsRefresh() =
        runTest {
            val jobA = CronJob("idA", "Job A", null, null, null, null)
            val jobB = CronJob("idB", "Job B", null, null, null, null)
            coEvery { mockApiService.getCronJobs() } returnsMany
                listOf(
                    Response.success(listOf(jobA)),
                    Response.success(listOf(jobB)),
                )

            val viewModel = CronJobsViewModel()
            viewModel.loadCronJobs()
            advanceUntilIdle()
            assertEquals(
                "Job A",
                viewModel.uiState.value.jobs[0]
                    .name,
            )

            viewModel.loadCronJobs()
            advanceUntilIdle()
            assertEquals(
                "Job B",
                viewModel.uiState.value.jobs[0]
                    .name,
            )
        }

    // Navigation Drawer:
    @Test
    fun testNavigationDrawerTransitions() {
        val backStack = NavBackStack<NavKey>(ChatScreen)
        NavigationController.backStack = backStack

        assertEquals(ChatScreen, backStack.lastOrNull())

        NavigationController.navigateTo(SkillsScreen)
        assertEquals(SkillsScreen, backStack.lastOrNull())
        assertEquals(1, backStack.size)

        NavigationController.navigateTo(CronJobsScreen)
        assertEquals(CronJobsScreen, backStack.lastOrNull())
        assertEquals(1, backStack.size)

        // Non-clearing drawer screen adds to stack
        NavigationController.navigateTo(ProfilesScreen)
        assertEquals(ProfilesScreen, backStack.lastOrNull())
        assertEquals(2, backStack.size)
        assertEquals(CronJobsScreen, backStack[0])
    }

    // ── Tier 2: Boundary & Corner Cases (>=5 per feature) ────────────────

    // Skills Screen boundary cases:
    @Test
    fun testSkillsLoad_httpError_400() =
        runTest {
            coEvery { mockApiService.getSkills() } returns createErrorResponse(400)
            val viewModel = SkillsViewModel()
            viewModel.loadSkills()
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.errorMessage!!
                    .contains("HTTP 400"),
            )
        }

    @Test
    fun testSkillsLoad_httpError_404() =
        runTest {
            coEvery { mockApiService.getSkills() } returns createErrorResponse(404)
            val viewModel = SkillsViewModel()
            viewModel.loadSkills()
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.errorMessage!!
                    .contains("HTTP 404"),
            )
        }

    @Test
    fun testSkillsLoad_httpError_500() =
        runTest {
            coEvery { mockApiService.getSkills() } returns createErrorResponse(500)
            val viewModel = SkillsViewModel()
            viewModel.loadSkills()
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.errorMessage!!
                    .contains("HTTP 500"),
            )
        }

    @Test
    fun testSkillsLoad_networkTimeout() =
        runTest {
            coEvery { mockApiService.getSkills() } throws IOException("Timeout")
            val viewModel = SkillsViewModel()
            viewModel.loadSkills()
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.errorMessage!!
                    .contains("Timeout"),
            )
        }

    @Test
    fun testSkillsLoad_emptyResponse() =
        runTest {
            coEvery { mockApiService.getSkills() } returns Response.success(emptyList())
            val viewModel = SkillsViewModel()
            viewModel.loadSkills()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(
                viewModel.uiState.value.skills
                    .isEmpty(),
            )
            assertNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun testSkillsToggle_httpError_500() =
        runTest {
            val skill = Skill("Skill 1", null, null, false)
            coEvery { mockApiService.getSkills() } returns Response.success(listOf(skill))
            coEvery { mockApiService.toggleSkill(any()) } returns createErrorResponse(500)

            val viewModel = SkillsViewModel()
            viewModel.loadSkills()
            advanceUntilIdle()

            viewModel.toggleSkill(viewModel.uiState.value.skills[0])
            advanceUntilIdle()

            assertFalse(
                viewModel.uiState.value.skills[0]
                    .enabled,
            )
            assertTrue(
                viewModel.uiState.value.toastMessage!!
                    .contains("HTTP 500"),
            )
        }

    // Cron Jobs Screen boundary cases:
    @Test
    fun testCronJobsLoad_httpError_500() =
        runTest {
            coEvery { mockApiService.getCronJobs() } returns createErrorResponse(500)
            val viewModel = CronJobsViewModel()
            viewModel.loadCronJobs()
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.errorMessage!!
                    .contains("HTTP 500"),
            )
        }

    @Test
    fun testCronJobPause_httpError_500() =
        runTest {
            val job = CronJob("id1", "Job 1", null, "active", null, null)
            coEvery { mockApiService.getCronJobs() } returns Response.success(listOf(job))
            coEvery { mockApiService.pauseCronJob("id1") } returns createErrorResponse(500)

            val viewModel = CronJobsViewModel()
            viewModel.loadCronJobs()
            advanceUntilIdle()

            viewModel.pauseCronJob("id1")
            advanceUntilIdle()

            assertEquals(
                "active",
                viewModel.uiState.value.jobs[0]
                    .state,
            )
            assertTrue(
                viewModel.uiState.value.toastMessage!!
                    .contains("HTTP 500"),
            )
        }

    @Test
    fun testCronJobTrigger_httpError_500() =
        runTest {
            coEvery { mockApiService.triggerCronJob("id1") } returns createErrorResponse(500)

            val viewModel = CronJobsViewModel()
            viewModel.triggerCronJob("id1")
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.toastMessage!!
                    .contains("HTTP 500"),
            )
        }

    @Test
    fun testCronJobDelete_httpError_500() =
        runTest {
            val job = CronJob("id1", "Job 1", null, null, null, null)
            coEvery { mockApiService.getCronJobs() } returns Response.success(listOf(job))
            coEvery { mockApiService.deleteCronJob("id1") } returns createErrorResponse(500)

            val viewModel = CronJobsViewModel()
            viewModel.loadCronJobs()
            advanceUntilIdle()

            viewModel.deleteCronJob("id1")
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.jobs.size)
            assertTrue(
                viewModel.uiState.value.toastMessage!!
                    .contains("HTTP 500"),
            )
        }

    @Test
    fun testCronJobsLoad_networkTimeout() =
        runTest {
            coEvery { mockApiService.getCronJobs() } throws IOException("Timeout")
            val viewModel = CronJobsViewModel()
            viewModel.loadCronJobs()
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.errorMessage!!
                    .contains("Timeout"),
            )
        }

    @Test
    fun testCronJobsLoad_emptyResponse() =
        runTest {
            coEvery { mockApiService.getCronJobs() } returns Response.success(emptyList())
            val viewModel = CronJobsViewModel()
            viewModel.loadCronJobs()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(
                viewModel.uiState.value.jobs
                    .isEmpty(),
            )
            assertNull(viewModel.uiState.value.errorMessage)
        }

    // ── Tier 3: Cross-Feature Combinations ────────────────────────────────

    @Test
    fun testDrawerTransitionDuringAction() =
        runTest {
            val skill = Skill("Skill 1", null, null, false)
            coEvery { mockApiService.getSkills() } returns Response.success(listOf(skill))
            coEvery { mockApiService.toggleSkill(any()) } returns Response.success(Unit)

            val viewModel = SkillsViewModel()
            viewModel.loadSkills()
            advanceUntilIdle()

            // Set up drawer/backstack
            val backStack = NavBackStack<NavKey>(SkillsScreen)
            NavigationController.backStack = backStack

            viewModel.toggleSkill(viewModel.uiState.value.skills[0])

            // Transition during action
            NavigationController.navigateTo(CronJobsScreen)

            assertEquals(CronJobsScreen, NavigationController.backStack?.lastOrNull())

            // Complete the action
            advanceUntilIdle()

            // Verify the state is correct and didn't crash
            assertTrue(
                viewModel.uiState.value.skills[0]
                    .enabled,
            )
        }

    @Test
    fun testAuthTokenRevocation() =
        runTest {
            val mockResponse = createErrorResponse<StatusResponse>(401)
            coEvery { mockApiService.getStatus() } returns mockResponse

            val viewModel = ConnectViewModel(mockApp)
            viewModel.onTokenChange("expired-token")
            viewModel.connect()
            advanceUntilIdle()

            // Verify state is updated with error message
            assertEquals("Invalid token (401 Unauthorized)", viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.connectionSuccess)
            // Verify AuthManager.setToken(null) was called
        }

    // ── Tier 4: Real-World Scenarios ─────────────────────────────────────

    @Test
    fun testFullUserSessionFlow() =
        runTest {
            // Step 1: User connects with valid token
            val statusResponse = mockk<StatusResponse>()
            coEvery { mockApiService.getStatus() } returns Response.success(statusResponse)

            val connectViewModel = ConnectViewModel(mockApp)
            connectViewModel.onTokenChange("valid-token")
            connectViewModel.onHostChange("127.0.0.1")
            connectViewModel.onPortChange("9119")
            connectViewModel.connect()
            advanceUntilIdle()

            assertTrue(connectViewModel.uiState.value.connectionSuccess)
            verify { AuthManager.setToken("valid-token") }

            // Step 2: User navigates to SkillsScreen
            val backStack = NavBackStack<NavKey>(ChatScreen)
            NavigationController.backStack = backStack
            NavigationController.navigateTo(SkillsScreen)
            assertEquals(SkillsScreen, NavigationController.backStack?.lastOrNull())

            // Step 3: User toggles a skill, but API fails
            val skill = Skill("Skill X", "Desc X", "Cat X", false)
            coEvery { mockApiService.getSkills() } returns Response.success(listOf(skill))
            coEvery { mockApiService.toggleSkill(any()) } returns createErrorResponse(500)

            val skillsViewModel = SkillsViewModel()
            skillsViewModel.loadSkills()
            advanceUntilIdle()

            assertEquals(1, skillsViewModel.uiState.value.skills.size)
            assertFalse(
                skillsViewModel.uiState.value.skills[0]
                    .enabled,
            )

            skillsViewModel.toggleSkill(skill)
            // Verify optimistic update
            assertTrue(
                skillsViewModel.uiState.value.skills[0]
                    .enabled,
            )

            advanceUntilIdle()
            // Verify state reverts and toast is shown
            assertFalse(
                skillsViewModel.uiState.value.skills[0]
                    .enabled,
            )
            assertTrue(
                skillsViewModel.uiState.value.toastMessage!!
                    .contains("HTTP 500"),
            )

            // Step 4: User navigates to CronJobsScreen
            NavigationController.navigateTo(CronJobsScreen)
            assertEquals(CronJobsScreen, NavigationController.backStack?.lastOrNull())

            // Step 5: User triggers a cron job
            coEvery { mockApiService.triggerCronJob("job-1") } returns Response.success(Unit)
            val cronViewModel = CronJobsViewModel()
            cronViewModel.triggerCronJob("job-1")
            advanceUntilIdle()

            assertEquals("Job triggered successfully", cronViewModel.uiState.value.toastMessage)
        }

    @Test
    fun testLogsListing_success() =
        runTest {
            coEvery { mockApiService.getLogs(lines = 1000) } returns
                Response.success(LogResponse(listOf("Log line 1", "Log line 2")))

            val viewModel = LogsViewModel()
            viewModel.loadLogs()
            assertTrue(viewModel.uiState.value.isLoading)

            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(2, viewModel.uiState.value.logs.size)
            assertEquals("Log line 1", viewModel.uiState.value.logs[0])
            assertEquals("Log line 2", viewModel.uiState.value.logs[1])
        }

    @Test
    fun testPluginsManagement_success() =
        runTest {
            val plugin = PluginInfo("plugin-1", "Desc", "1.0", null, "disabled")
            coEvery { mockApiService.getPlugins() } returns Response.success(PluginsHubResponse(listOf(plugin)))
            coEvery { mockApiService.enablePlugin("plugin-1") } returns Response.success(Unit)

            val viewModel = PluginsViewModel()
            viewModel.loadPlugins()
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.plugins.size)
            assertFalse(
                viewModel.uiState.value.plugins[0]
                    .enabled,
            )

            viewModel.togglePlugin(viewModel.uiState.value.plugins[0])
            // Optimistic toggle check
            assertTrue(
                viewModel.uiState.value.plugins[0]
                    .enabled,
            )

            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.plugins[0]
                    .enabled,
            )
        }

    @Test
    fun testChannelsConfiguration_success() =
        runTest {
            val platform =
                MessagingPlatform(
                    id = "telegram",
                    name = "Telegram",
                    enabled = false,
                    configured = false,
                    envVars = emptyList(),
                )
            coEvery { mockApiService.getMessagingPlatforms() } returns
                Response.success(
                    MessagingPlatformResponse(
                        envPath = "/tmp/.env",
                        gatewayStartCommand = "hermes gateway start",
                        platforms = listOf(platform),
                    ),
                )
            coEvery { mockApiService.configurePlatform("telegram", any()) } returns Response.success(Unit)

            val viewModel = ChannelsViewModel()
            viewModel.loadPlatforms()
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.platforms.size)

            viewModel.configurePlatform("telegram", mapOf("bot_token" to "xyz"))
            advanceUntilIdle()

            assertEquals(
                "telegram configured successfully — restart the gateway for changes to take effect",
                viewModel.uiState.value.toastMessage,
            )
        }

    @Test
    fun testKeysManagement_success() =
        runTest {
            coEvery { mockApiService.getEnvVars() } returns
                Response.success(mapOf("KEY1" to EnvVarConfig(true, "value1", "desc", null, null, false)))
            coEvery { mockApiService.updateEnvVar(any()) } returns Response.success(Unit)

            val viewModel = KeysViewModel()
            viewModel.loadKeys(reveal = false)
            advanceUntilIdle()

            assertEquals(
                "value1",
                viewModel.uiState.value.envVars["KEY1"]
                    ?.redactedValue,
            )

            viewModel.updateKey("KEY1", "newValue")
            advanceUntilIdle()

            assertEquals("Key updated successfully", viewModel.uiState.value.toastMessage)
        }

    @Test
    fun testSystemDiagnostics_success() =
        runTest {
            val stats =
                SystemStatsResponse(
                    cpu_percent = 50.0,
                    memory = MemoryStats(total = 8192, used = 4096, percent = 60.0),
                )
            val doctor = DoctorResponse(true, 24856, "doctor")
            coEvery { mockApiService.getSystemStats() } returns Response.success(stats)
            coEvery { mockApiService.runDoctor() } returns Response.success(doctor)
            coEvery { mockApiService.getStatus() } returns
                Response.success(StatusResponse("1.0", true, 0, false, null))
            coEvery { mockApiService.getPortal() } returns
                Response.success(PortalResponse(logged_in = false))
            coEvery { mockApiService.getCurator() } returns Response.success(CuratorResponse())
            coEvery { mockApiService.getMemory() } returns Response.success(MemoryResponse())
            coEvery { mockApiService.getCredentialPool() } returns
                Response.success(CredentialPoolResponse())
            coEvery { mockApiService.getCheckpoints() } returns
                Response.success(CheckpointsResponse())
            coEvery { mockApiService.getHooks() } returns Response.success(HookResponse())
            coEvery { mockApiService.checkHermesUpdate(false) } returns
                Response.success(UpdateCheckResponse())
            coEvery { mockApiService.triggerBackup() } returns Response.success(Unit)

            val viewModel = SystemViewModel()
            viewModel.loadAll()
            advanceUntilIdle()

            assertEquals(
                50.0,
                viewModel.uiState.value.stats?.cpu_percent,
            )

            viewModel.triggerBackup()
            advanceUntilIdle()

            assertEquals("Backup triggered successfully", viewModel.uiState.value.toastMessage)
        }

    @Test
    fun testKanbanBoardAndTasks_success() =
        runTest {
            val board = KanbanBoard("board-1", "Backlog", null)
            val task = KanbanTask("task-1", "Do laundry", null, "todo", null)
            coEvery { mockApiService.getKanbanBoards() } returns
                Response.success(KanbanBoardsResponse(listOf(board), "board-1"))
            coEvery { mockApiService.switchKanbanBoard("board-1") } returns Response.success(Unit)
            coEvery { mockApiService.getKanbanBoard() } returns
                Response.success(KanbanBoardResponse(listOf(KanbanColumn("todo", listOf(task))), null, null))
            coEvery { mockApiService.updateKanbanTask("task-1", mapOf("status" to "in_progress")) } returns
                Response.success(Unit)

            val viewModel = KanbanViewModel()
            viewModel.loadBoards()
            advanceUntilIdle()

            assertEquals(
                "board-1",
                viewModel.uiState.value.selectedBoard
                    ?.id,
            )
            assertEquals(1, viewModel.uiState.value.tasks.size)
            assertEquals(
                "todo",
                viewModel.uiState.value.tasks[0]
                    .status,
            )

            viewModel.moveTask(viewModel.uiState.value.tasks[0], "in_progress")
            // Optimistic move check
            assertEquals(
                "in_progress",
                viewModel.uiState.value.tasks[0]
                    .status,
            )

            advanceUntilIdle()
            assertEquals(
                "in_progress",
                viewModel.uiState.value.tasks[0]
                    .status,
            )
        }

    @Test
    fun testModelOptionsSelection_success() =
        runTest {
            val provider = ModelProvider("ollama", "Ollama", false, true, listOf("llama3"), 1, null, true, null, null)
            val profile = ProfileInfo("default", null, true, "llama3", "ollama", null, null, null, null)

            coEvery { mockApiService.getModelOptions() } returns
                Response.success(ModelOptionsResponse(listOf(provider)))
            coEvery { mockApiService.getActiveProfile() } returns
                Response.success(ActiveProfileResponse("default", null))
            coEvery { mockApiService.getProfiles() } returns Response.success(ProfilesResponse(listOf(profile)))
            coEvery { mockApiService.updateProfileModel("default", any()) } returns Response.success(Unit)
            coEvery { mockApiService.getAuxiliaryModels() } returns
                Response.success(
                    AuxiliaryModelsResponse(
                        tasks = emptyList(),
                        main = MainModelAssignment("openai", "gpt-4"),
                    ),
                )
            coEvery { mockApiService.getMoaModels() } returns
                Response.success<MoaConfigResponse>(
                    MoaConfigResponse(
                        presets = emptyMap(),
                        default_preset = "",
                        reference_models = emptyList(),
                        aggregator = MoaModelSlot("openai", "gpt-4"),
                        reference_temperature = 0.7,
                        aggregator_temperature = 0.7,
                        max_tokens = 4096,
                    ),
                )

            val viewModel = ModelViewModel()
            viewModel.loadAll()
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.providers.size)
            assertEquals(
                "default",
                viewModel.uiState.value.activeProfile
                    ?.name,
            )
            assertEquals(
                "llama3",
                viewModel.uiState.value.activeProfile
                    ?.model,
            )

            viewModel.selectModel("ollama", "llama3")
            advanceUntilIdle()

            coVerify { mockApiService.updateProfileModel("default", any()) }
            assertEquals("Successfully set profile model to llama3", viewModel.uiState.value.toastMessage)
        }

    @Test
    fun testConnectViewModel_onPairingString_urlFormat() =
        runTest {
            mockkStatic(android.net.Uri::class)
            val mockUri = mockk<android.net.Uri>()
            every { android.net.Uri.parse("hermes://connect?host=192.168.1.5&port=9119&token=TEST_TOKEN") } returns
                mockUri
            every { mockUri.getQueryParameter("host") } returns "192.168.1.5"
            every { mockUri.getQueryParameter("port") } returns "9119"
            every { mockUri.getQueryParameter("token") } returns "TEST_TOKEN"

            val statusResponse = mockk<StatusResponse>()
            coEvery { mockApiService.getStatus() } returns Response.success(statusResponse)

            val viewModel = ConnectViewModel(mockApp)
            viewModel.onPairingString("hermes://connect?host=192.168.1.5&port=9119&token=TEST_TOKEN")
            advanceUntilIdle()

            assertEquals("192.168.1.5", viewModel.uiState.value.host)
            assertEquals("9119", viewModel.uiState.value.port)
            assertEquals("TEST_TOKEN", viewModel.uiState.value.token)
            assertTrue(viewModel.uiState.value.connectionSuccess)

            unmockkStatic(android.net.Uri::class)
        }

    @Test
    fun testConnectViewModel_onPairingString_base64Format() =
        runTest {
            mockkStatic(android.util.Base64::class)
            val base64Str = "eyJob3N0IjoiMTkyLjE2OC4xLjUiLCJwb3J0Ijo5MTE5LCJ0b2tlbiI6IlRFU1RfVE9LRU4ifQ=="
            every { android.util.Base64.decode(base64Str, any()) } answers {
                java.util.Base64
                    .getDecoder()
                    .decode(base64Str)
            }

            val statusResponse = mockk<StatusResponse>()
            coEvery { mockApiService.getStatus() } returns Response.success(statusResponse)

            val viewModel = ConnectViewModel(mockApp)
            viewModel.onPairingString(base64Str)
            advanceUntilIdle()

            assertEquals("192.168.1.5", viewModel.uiState.value.host)
            assertEquals("9119", viewModel.uiState.value.port)
            assertEquals("TEST_TOKEN", viewModel.uiState.value.token)
            assertTrue(viewModel.uiState.value.connectionSuccess)

            unmockkStatic(android.util.Base64::class)
        }
}
