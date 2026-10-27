package com.m57.hermescontrol

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.ui.connect.ConnectViewModel
import com.m57.hermescontrol.ui.cron.CronJobsViewModel
import com.m57.hermescontrol.ui.skills.SkillsViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/** Cross-screen ViewModel workflows with mocked APIs; not device or backend end-to-end tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelWorkflowTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApiService: HermesApiService
    private val mockApp = mockk<Application>(relaxed = true)
    private val viewModels = mutableListOf<ViewModel>()

    private fun <T : ViewModel> track(viewModel: T): T = viewModel.also { viewModels += it }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockkObject(AuthManager)
        mockkObject(ApiClient)

        mockApiService = mockk()
        every { ApiClient.hermesApi } returns mockApiService
        every { ApiClient.createTempService(any(), any()) } returns mockApiService
        every { ApiClient.rebuild() } returns Unit

        // Default AuthManager stubs
        every { AuthManager.getToken() } returns "mock-token"
        every { AuthManager.getBaseUrl() } returns "https://127.0.0.1:9119/"
        every { AuthManager.setToken(any()) } returns Unit
        every { AuthManager.setBaseUrl(any()) } returns Unit
        every {
            AuthManager.endpoint()
        } answers { ServerEndpoint.parse("https://127.0.0.1:9119/", CleartextPolicy.ALLOW_WITH_WARNING) }
        every { AuthManager.getConnectionProfiles() } returns emptyList()
        every { AuthManager.getSelectedProfileId() } returns null

        // Mock Application string resources (no real resources in unit tests)
        // SkillsViewModel uses hardcoded strings (not getApplication().getString()),
        // so only resources used by ConnectViewModel + enum labels need stubs.
        every { mockApp.getString(any<Int>()) } returns ""
        every { AuthManager.setSelectedProfileId(any()) } returns Unit
        every { AuthManager.ensureDefaultSelected() } returns Unit
        every { AuthManager.getProfileToken(any()) } returns null
        every { AuthManager.setProfileToken(any(), any()) } returns Unit
        every { AuthManager.saveConnectionProfiles(any()) } returns Unit
        every { AuthManager.currentDataScope() } returns null
    }

    @After
    fun tearDown() {
        try {
            viewModels.forEach { it.viewModelScope.cancel() }
            testDispatcher.scheduler.runCurrent()
        } finally {
            NavigationController.backStack = null
            Dispatchers.resetMain()
            unmockkAll()
        }
    }

    private fun <T> createErrorResponse(code: Int): Response<T> = Response.error(code, "".toResponseBody(null))

    @Test
    fun testDrawerTransitionDuringAction() =
        runTest {
            val skill = Skill("Skill 1", null, null, false)
            coEvery { mockApiService.getSkills() } returns Response.success(listOf(skill))
            coEvery { mockApiService.toggleSkill(any()) } returns Response.success(Unit)

            val viewModel = track(SkillsViewModel(mockApp).also { it.ioDispatcher = testDispatcher })
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
    fun testFullUserSessionFlow() =
        runTest {
            // Step 1: User connects with valid token
            val statusResponse = mockk<StatusResponse>()
            coEvery { mockApiService.getStatus() } returns Response.success(statusResponse)

            val connectViewModel = track(ConnectViewModel(mockApp))
            connectViewModel.onTokenChange("valid-token")
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

            val skillsViewModel = track(SkillsViewModel(mockApp).also { it.ioDispatcher = testDispatcher })
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
            val cronViewModel = track(CronJobsViewModel().also { it.ioDispatcher = testDispatcher })
            cronViewModel.triggerCronJob("job-1")
            advanceUntilIdle()

            assertEquals("Job triggered successfully", cronViewModel.uiState.value.toastMessage)
        }
}
