package com.m57.hermescontrol

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.model.ToggleSkillRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.ui.connect.ConnectViewModel
import com.m57.hermescontrol.ui.cron.CronJobsViewModel
import com.m57.hermescontrol.ui.skills.SkillsViewModel
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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val testMainDispatcher = Dispatchers.Main
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher
        every { Dispatchers.Main } returns testMainDispatcher

        mockkObject(AuthManager)
        mockkObject(ApiClient)

        mockApiService = mockk()
        every { ApiClient.hermesApi } returns mockApiService
        every { ApiClient.rebuild() } returns Unit

        // Default AuthManager stubs
        every { AuthManager.getToken() } returns "mock-token"
        every { AuthManager.getHost() } returns "127.0.0.1"
        every { AuthManager.getPort() } returns 9119
        every { AuthManager.setToken(any()) } returns Unit
        every { AuthManager.setHost(any()) } returns Unit
        every { AuthManager.setPort(any()) } returns Unit
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

        // Non-clearing key settings adds to stack
        NavigationController.navigateTo(SettingsScreen)
        assertEquals(SettingsScreen, backStack.lastOrNull())
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

            val viewModel = ConnectViewModel()
            viewModel.onTokenChange("expired-token")
            viewModel.connect()
            advanceUntilIdle()

            // Verify state is updated with error message
            assertEquals("Invalid token (401 Unauthorized)", viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.connectionSuccess)
            // Verify AuthManager.setToken(null) was called
            verify { AuthManager.setToken(null) }
        }

    // ── Tier 4: Real-World Scenarios ─────────────────────────────────────

    @Test
    fun testFullUserSessionFlow() =
        runTest {
            // Step 1: User connects with valid token
            val statusResponse = mockk<StatusResponse>()
            coEvery { mockApiService.getStatus() } returns Response.success(statusResponse)

            val connectViewModel = ConnectViewModel()
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
}
