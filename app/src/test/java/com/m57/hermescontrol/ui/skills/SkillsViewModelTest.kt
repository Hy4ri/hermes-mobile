package com.m57.hermescontrol.ui.skills

import android.app.Application
import com.m57.hermescontrol.data.model.HubSkill
import com.m57.hermescontrol.data.model.SkillHubPreviewResponse
import com.m57.hermescontrol.data.model.SkillHubSource
import com.m57.hermescontrol.data.model.SkillHubSourcesResponse
import com.m57.hermescontrol.data.model.SkillScanFinding
import com.m57.hermescontrol.data.model.SkillScanResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>(relaxed = true)
    private val app = mockk<Application>(relaxed = true)

    private fun createViewModel(): SkillsViewModel = SkillsViewModel(app).also { it.ioDispatcher = testDispatcher }

    private fun settle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private val sourcesResponse =
        SkillHubSourcesResponse(
            sources =
                listOf(
                    SkillHubSource(id = "hermes-index", label = "Hermes Index", available = true),
                    SkillHubSource(id = "github", label = "GitHub", rateLimited = true),
                ),
            indexAvailable = true,
            featured =
                listOf(
                    HubSkill(
                        name = "web-research",
                        description = "Find and verify current web facts",
                        source = "hermes-index",
                        identifier = "hermes-index:web-research",
                    ),
                ),
        )

    private val scanResponse =
        SkillScanResponse(
            identifier = "hermes-index:web-research",
            name = "web-research",
            source = "hermes-index",
            trustLevel = "trusted",
            verdict = "safe",
            summary = "No issues found",
            policy = "allow",
            policyReason = "No dangerous patterns detected.",
            findings =
                listOf(
                    SkillScanFinding(
                        severity = "low",
                        category = "network",
                        file = "scripts/fetch.py",
                        line = 12,
                        description = "Uses urllib without a timeout.",
                    ),
                ),
            severityCounts = mapOf("low" to 1),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `loadHubSources stores sources and featured skills`() {
        coEvery { mockApi.getSkillHubSources() } returns Response.success(sourcesResponse)
        val vm = createViewModel()
        vm.loadHubSources()
        settle()

        assertEquals(sourcesResponse.sources, vm.uiState.value.hubSources)
        assertEquals(sourcesResponse.featured, vm.uiState.value.hubFeatured)
        assertEquals(false, vm.uiState.value.isHubSourcesLoading)
        assertNull(vm.uiState.value.hubSourcesError)
    }

    @Test
    fun `loadHubSources surfaces an error`() {
        coEvery { mockApi.getSkillHubSources() } returns
            Response.error(
                404,
                """{"detail":"Hub sources unavailable"}""".toResponseBody("application/json".toMediaTypeOrNull()),
            )
        val vm = createViewModel()
        vm.loadHubSources()
        settle()

        assertEquals(
            true,
            vm.uiState.value.hubSourcesError
                ?.contains("Failed to load hub sources"),
        )
        assertEquals(false, vm.uiState.value.isHubSourcesLoading)
    }

    @Test
    fun `setViewMode HUB triggers sources load when landing is empty`() {
        coEvery { mockApi.getSkillHubSources() } returns Response.success(sourcesResponse)
        val vm = createViewModel()
        vm.setViewMode(SkillsViewMode.HUB)
        settle()

        coVerify { mockApi.getSkillHubSources() }
        assertEquals(sourcesResponse.featured, vm.uiState.value.hubFeatured)
    }

    @Test
    fun `scanHubSkill stores the scan result for the identifier`() {
        coEvery { mockApi.scanHubSkill(identifier = "hermes-index:web-research") } returns
            Response.success(scanResponse)
        val vm = createViewModel()
        vm.scanHubSkill("hermes-index:web-research")
        settle()

        assertEquals("hermes-index:web-research", vm.uiState.value.hubScanIdentifier)
        assertEquals(scanResponse, vm.uiState.value.hubScanResult)
        assertEquals(false, vm.uiState.value.isHubScanning)
        assertNull(vm.uiState.value.hubScanError)
    }

    @Test
    fun `scanHubSkill surfaces an error`() {
        coEvery { mockApi.scanHubSkill(identifier = "broken:skill") } returns
            Response.error(
                404,
                """{"detail":"Skill not found"}""".toResponseBody("application/json".toMediaTypeOrNull()),
            )
        val vm = createViewModel()
        vm.scanHubSkill("broken:skill")
        settle()

        assertEquals("broken:skill", vm.uiState.value.hubScanIdentifier)
        assertNull(vm.uiState.value.hubScanResult)
        assertEquals(false, vm.uiState.value.isHubScanning)
        assertEquals("Skill not found", vm.uiState.value.hubScanError)
    }

    @Test
    fun `scanHubSkill ignores blank identifiers`() {
        val vm = createViewModel()
        vm.scanHubSkill(" ")
        settle()

        assertNull(vm.uiState.value.hubScanIdentifier)
        coVerify(exactly = 0) { mockApi.scanHubSkill(any()) }
    }

    @Test
    fun `clearHubScan resets scan state`() {
        coEvery { mockApi.scanHubSkill(identifier = "hermes-index:web-research") } returns
            Response.success(scanResponse)
        val vm = createViewModel()
        vm.scanHubSkill("hermes-index:web-research")
        settle()
        vm.clearHubScan()

        assertNull(vm.uiState.value.hubScanIdentifier)
        assertNull(vm.uiState.value.hubScanResult)
        assertNull(vm.uiState.value.hubScanError)
        assertEquals(false, vm.uiState.value.isHubScanning)
    }

    @Test
    fun `previewHubSkill populates hubPreviewContent on success`() {
        val previewResponse =
            SkillHubPreviewResponse(
                name = "web-research",
                skillMd = "# Web Research\nInstructions for research.",
                description = "Find and verify web facts",
                identifier = "hermes-index:web-research",
            )
        coEvery { mockApi.previewHubSkill("hermes-index:web-research") } returns
            Response.success(previewResponse)

        val vm = createViewModel()
        vm.previewHubSkill("hermes-index:web-research")
        settle()

        assertEquals("hermes-index:web-research", vm.uiState.value.hubPreviewIdentifier)
        assertEquals("# Web Research\nInstructions for research.", vm.uiState.value.hubPreviewContent)
        assertEquals(false, vm.uiState.value.isHubPreviewing)
        assertNull(vm.uiState.value.hubPreviewError)
    }

    @Test
    fun `previewHubSkill surfaces error on failure`() {
        coEvery { mockApi.previewHubSkill("missing:skill") } returns
            Response.error(
                404,
                """{"detail":"Hub skill not found"}""".toResponseBody("application/json".toMediaTypeOrNull()),
            )

        val vm = createViewModel()
        vm.previewHubSkill("missing:skill")
        settle()

        assertEquals("missing:skill", vm.uiState.value.hubPreviewIdentifier)
        assertNull(vm.uiState.value.hubPreviewContent)
        assertEquals(false, vm.uiState.value.isHubPreviewing)
        assertEquals(
            true,
            vm.uiState.value.hubPreviewError
                ?.contains("Hub skill not found"),
        )
    }

    @Test
    fun `previewHubSkill fences late preview response when identifier replaced`() {
        val firstDeferred = CompletableDeferred<Response<SkillHubPreviewResponse>>()
        val secondResponse =
            SkillHubPreviewResponse(
                name = "second-skill",
                skillMd = "# Second Skill\nContent 2",
                identifier = "hub:second",
            )

        coEvery { mockApi.previewHubSkill("hub:first") } coAnswers { firstDeferred.await() }
        coEvery { mockApi.previewHubSkill("hub:second") } returns Response.success(secondResponse)

        val vm = createViewModel()
        // Trigger first preview
        vm.previewHubSkill("hub:first")
        testDispatcher.scheduler.runCurrent()

        assertEquals("hub:first", vm.uiState.value.hubPreviewIdentifier)
        assertEquals(true, vm.uiState.value.isHubPreviewing)

        // Trigger second preview before first completes
        vm.previewHubSkill("hub:second")
        settle()

        assertEquals("hub:second", vm.uiState.value.hubPreviewIdentifier)
        assertEquals("# Second Skill\nContent 2", vm.uiState.value.hubPreviewContent)
        assertEquals(false, vm.uiState.value.isHubPreviewing)

        // Now first preview completes late
        firstDeferred.complete(
            Response.success(
                SkillHubPreviewResponse(
                    name = "first-skill",
                    skillMd = "# First Skill\nContent 1",
                    identifier = "hub:first",
                ),
            ),
        )
        settle()

        // State must retain the second skill and not be overwritten by late first response
        assertEquals("hub:second", vm.uiState.value.hubPreviewIdentifier)
        assertEquals("# Second Skill\nContent 2", vm.uiState.value.hubPreviewContent)
    }

    @Test
    fun `clearScopeOwnedState fences late preview response`() {
        val deferred = CompletableDeferred<Response<SkillHubPreviewResponse>>()
        val previewResponse =
            SkillHubPreviewResponse(
                name = "web-research",
                skillMd = "# Web Research\nContent",
                identifier = "hermes-index:web-research",
            )
        coEvery { mockApi.previewHubSkill("hermes-index:web-research") } coAnswers { deferred.await() }

        val vm = createViewModel()
        vm.previewHubSkill("hermes-index:web-research")
        testDispatcher.scheduler.runCurrent()

        assertEquals("hermes-index:web-research", vm.uiState.value.hubPreviewIdentifier)
        assertEquals(true, vm.uiState.value.isHubPreviewing)

        // Clear scope-owned state (e.g. profile/scope transition)
        vm.clearScopeOwnedState()
        assertNull(vm.uiState.value.hubPreviewIdentifier)
        assertNull(vm.uiState.value.hubPreviewContent)
        assertEquals(false, vm.uiState.value.isHubPreviewing)

        // Delayed response resolves
        deferred.complete(Response.success(previewResponse))
        settle()

        // Late response must not populate preview content
        assertNull(vm.uiState.value.hubPreviewIdentifier)
        assertNull(vm.uiState.value.hubPreviewContent)
        assertEquals(false, vm.uiState.value.isHubPreviewing)
    }
}
