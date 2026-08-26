package com.m57.hermescontrol.ui.bots

import com.m57.hermescontrol.data.model.ActiveProfileResponse
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.model.BotRosterMeta
import com.m57.hermescontrol.data.model.CanonicalSessionInfo
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.ProfileWorkerSummary
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.ws.HermesWsClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class BotsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        mockkObject(HermesWsClient)
        every { HermesWsClient.send(any(), any(), any()) } returns "req-1"
    }

    @After
    fun tearDown() {
        unmockkObject(ApiClient)
        unmockkObject(HermesWsClient)
        Dispatchers.resetMain()
    }

    @Test
    fun testLoadBotsAndFilterActiveNow() =
        runTest(testDispatcher) {
            val nowSeconds = System.currentTimeMillis() / 1000

            val bot1Meta =
                BotRosterMeta(
                    title = "Literature Scout",
                    description = "Arxiv searcher",
                    avatar = BotAvatarMeta(shape = "hexagon", color = "#4A90E2", icon = "science"),
                    hidden = false,
                )
            val bot2Meta =
                BotRosterMeta(
                    title = "Code Reviewer",
                    description = "Kotlin linter",
                    avatar = BotAvatarMeta(shape = "square", color = "#FF5C5C", icon = "code"),
                    hidden = true,
                )

            val profiles =
                listOf(
                    ProfileInfo(
                        name = "default",
                        is_default = true,
                        canonical_session =
                            CanonicalSessionInfo(
                                id = "canon-def",
                                last_active = nowSeconds - 20,
                            ),
                    ),
                    ProfileInfo(
                        name = "scout",
                        ui_meta = mapOf("hermes-bots" to json.encodeToJsonElement(bot1Meta)),
                        worker_session =
                            ProfileWorkerSummary(
                                id = "worker-1",
                                source = "kanban",
                                last_active = nowSeconds - 10,
                            ),
                    ),
                    ProfileInfo(
                        name = "reviewer",
                        ui_meta = mapOf("hermes-bots" to json.encodeToJsonElement(bot2Meta)),
                        canonical_session =
                            CanonicalSessionInfo(
                                id = "canon-rev",
                                last_active = nowSeconds - 500,
                            ),
                    ),
                )

            coEvery { mockApi.getProfiles() } returns Response.success(ProfilesResponse(profiles))
            coEvery { mockApi.getActiveProfile() } returns Response.success(ActiveProfileResponse(active = "default"))

            val viewModel = BotsViewModel(ioDispatcher = testDispatcher, autoLoad = false)
            viewModel.loadBots()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertEquals(3, state.profiles.size)
            assertEquals("default", state.activeProfileName)
            assertTrue(state.hasHiddenBots)

            // Active now includes default (active profile) and scout (active worker)
            val activeNowNames = state.activeNowBots.map { it.name }
            assertTrue(activeNowNames.contains("default"))
            assertTrue(activeNowNames.contains("scout"))
            assertFalse(activeNowNames.contains("reviewer"))

            // Hidden filtering: reviewer is hidden by default
            val displayed = state.displayProfiles.map { it.name }
            assertEquals(listOf("default", "scout"), displayed)

            // Toggle show hidden (sorted: default (active), scout (worker active now), reviewer (canonical last_active))
            viewModel.toggleShowHidden()
            val displayedWithHidden = viewModel.uiState.value.displayProfiles.map { it.name }
            assertEquals(listOf("default", "reviewer", "scout"), displayedWithHidden)

            // Search filter
            viewModel.setSearchQuery("arxiv")
            val searchResults = viewModel.uiState.value.displayProfiles.map { it.name }
            assertEquals(listOf("scout"), searchResults)
        }

    @Test
    fun testCreateBot_callsApiAndConfiguresMeta() =
        runTest {
            coEvery { mockApi.createProfile(any()) } returns Response.success(Unit)
            coEvery { mockApi.getProfiles() } returns Response.success(ProfilesResponse(emptyList()))
            coEvery { mockApi.getActiveProfile() } returns Response.success(ActiveProfileResponse(active = "default"))

            val viewModel = BotsViewModel(ioDispatcher = testDispatcher, autoLoad = false)
            var successCalled = false
            viewModel.createBot(
                name = "researcher",
                title = "Research Bot",
                description = "Deep research agent",
                shape = "hexagon",
                color = "#4F46E5",
                onSuccess = { successCalled = true },
            )
            advanceUntilIdle()

            assertTrue(successCalled)
            coVerify(exactly = 1) { mockApi.createProfile(match { it.name == "researcher" }) }
        }
}
