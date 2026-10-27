package com.m57.hermescontrol.ui.skills

import android.app.Application
import com.m57.hermescontrol.data.model.ActionResponse
import com.m57.hermescontrol.data.model.HubSkill
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.SkillHubPreviewResponse
import com.m57.hermescontrol.data.model.SkillHubSearchResponse
import com.m57.hermescontrol.data.model.SkillHubSource
import com.m57.hermescontrol.data.model.SkillHubSourcesResponse
import com.m57.hermescontrol.data.model.SkillScanFinding
import com.m57.hermescontrol.data.model.SkillScanResponse
import com.m57.hermescontrol.data.model.ToggleSkillRequest
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

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

    // ── Installed skills and Hub operations ─────────────────────────────

    @Test
    fun `loadSkills success populates skills and clears loading`() {
        val skill1 = Skill("Skill 1", "Description 1", "Category 1", true)
        val skill2 = Skill("Skill 2", "Description 2", "Category 2", false)
        coEvery { mockApi.getSkills() } returns Response.success(listOf(skill1, skill2))

        val vm = createViewModel()
        vm.loadSkills()

        assertTrue(vm.uiState.value.isLoading)
        assertNull(vm.uiState.value.errorMessage)

        settle()

        assertFalse(vm.uiState.value.isLoading)
        assertNull(vm.uiState.value.errorMessage)
        assertEquals(2, vm.uiState.value.skills.size)
        assertEquals(
            "Skill 1",
            vm.uiState.value.skills[0]
                .name,
        )
        assertEquals(
            "Description 1",
            vm.uiState.value.skills[0]
                .description,
        )
        assertEquals(
            "Category 1",
            vm.uiState.value.skills[0]
                .category,
        )
        assertTrue(
            vm.uiState.value.skills[0]
                .enabled,
        )
        assertEquals(
            "Skill 2",
            vm.uiState.value.skills[1]
                .name,
        )
        assertFalse(
            vm.uiState.value.skills[1]
                .enabled,
        )
    }

    @Test
    fun `toggleSkill success applies optimistic update and retains state`() {
        val skill = Skill("Skill 1", "Description 1", "Category 1", false)
        coEvery { mockApi.getSkills() } returns Response.success(listOf(skill))
        coEvery { mockApi.toggleSkill(ToggleSkillRequest("Skill 1", true)) } returns Response.success(Unit)

        val vm = createViewModel()
        vm.loadSkills()
        settle()

        assertFalse(
            vm.uiState.value.skills[0]
                .enabled,
        )

        vm.toggleSkill(vm.uiState.value.skills[0])
        // Verify optimistic update
        assertTrue(
            vm.uiState.value.skills[0]
                .enabled,
        )

        settle()
        // Verify it remains enabled
        assertTrue(
            vm.uiState.value.skills[0]
                .enabled,
        )
        assertNull(vm.uiState.value.toastMessage)
    }

    @Test
    fun `toggleSkill failure reverts optimistic update and surfaces toast error`() {
        val errorResponse = Response.error<Unit>(500, "".toResponseBody(null))
        val skill = Skill("Skill 1", "Description 1", "Category 1", false)
        coEvery { mockApi.getSkills() } returns Response.success(listOf(skill))
        coEvery { mockApi.toggleSkill(ToggleSkillRequest("Skill 1", true)) } returns errorResponse

        val vm = createViewModel()
        vm.loadSkills()
        settle()

        assertFalse(
            vm.uiState.value.skills[0]
                .enabled,
        )

        vm.toggleSkill(vm.uiState.value.skills[0])
        // Verify optimistic update
        assertTrue(
            vm.uiState.value.skills[0]
                .enabled,
        )

        settle()
        // Verify reverted state and toast error message
        assertFalse(
            vm.uiState.value.skills[0]
                .enabled,
        )
        assertNotNull(vm.uiState.value.toastMessage)
        assertTrue(
            vm.uiState.value.toastMessage!!
                .contains("HTTP 500"),
        )
    }

    @Test
    fun `loadSkills refresh updates state with newest listing`() {
        val skillA = Skill("Skill A", null, null, false)
        val skillB = Skill("Skill B", null, null, true)

        coEvery { mockApi.getSkills() } returnsMany
            listOf(
                Response.success(listOf(skillA)),
                Response.success(listOf(skillB)),
            )

        val vm = createViewModel()
        vm.loadSkills()
        settle()
        assertEquals(
            "Skill A",
            vm.uiState.value.skills[0]
                .name,
        )

        vm.loadSkills(forceRefresh = true)
        settle()
        assertEquals(
            "Skill B",
            vm.uiState.value.skills[0]
                .name,
        )
    }

    @Test
    fun `searchHub success populates hub results`() {
        val hubSkill = HubSkill("Test Skill", "A test hub skill", "hub", "General")
        coEvery { mockApi.searchSkillsHub(any()) } returns
            Response.success(SkillHubSearchResponse(results = listOf(hubSkill)))

        val vm = createViewModel()
        vm.searchHub("test")

        settle()
        assertFalse(vm.uiState.value.isHubSearching)
        assertEquals(1, vm.uiState.value.hubResults.size)
        assertEquals(
            "Test Skill",
            vm.uiState.value.hubResults[0]
                .name,
        )
        assertEquals(
            "hub",
            vm.uiState.value.hubResults[0]
                .source,
        )
    }

    @Test
    fun `searchHub empty result clears hub results`() {
        coEvery { mockApi.searchSkillsHub(any()) } returns
            Response.success(SkillHubSearchResponse(results = emptyList()))

        val vm = createViewModel()
        vm.searchHub("nonexistent")

        settle()
        assertFalse(vm.uiState.value.isHubSearching)
        assertTrue(
            vm.uiState.value.hubResults
                .isEmpty(),
        )
        assertNull(vm.uiState.value.hubSearchError)
    }

    @Test
    fun `searchHub error surfaces search error`() {
        val errorResponse = Response.error<SkillHubSearchResponse>(500, "".toResponseBody(null))
        coEvery { mockApi.searchSkillsHub(any()) } returns errorResponse

        val vm = createViewModel()
        vm.searchHub("test")

        settle()
        assertFalse(vm.uiState.value.isHubSearching)
        assertNotNull(vm.uiState.value.hubSearchError)
        assertTrue(
            vm.uiState.value.hubSearchError!!
                .contains("HTTP 500"),
        )
    }

    @Test
    fun `searchHub emptyQuery clears results immediately without network call`() {
        val vm = createViewModel()
        vm.searchHub("")

        assertTrue(
            vm.uiState.value.hubResults
                .isEmpty(),
        )
        assertFalse(vm.uiState.value.isHubSearching)
        assertNull(vm.uiState.value.hubSearchError)
        coVerify(exactly = 0) { mockApi.searchSkillsHub(any()) }
    }

    @Test
    fun `installSkill success installs skill and refreshes list`() {
        val installed = Skill("New Skill", "Just installed", "General", false)
        coEvery { mockApi.installHubSkill(any()) } returns
            Response.success(ActionResponse("success", "Installed"))
        coEvery { mockApi.getSkills() } returns Response.success(listOf(installed))

        val vm = createViewModel()
        vm.installSkill("new-skill")

        settle()
        assertFalse(vm.uiState.value.isInstalling)
        assertNull(vm.uiState.value.installingSkillName)
        assertNotNull(vm.uiState.value.toastMessage)
        assertTrue(
            vm.uiState.value.toastMessage!!
                .contains("Installed"),
        )
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.skills.size)
    }

    @Test
    fun `installSkill error surfaces toast error`() {
        val errorResponse = Response.error<ActionResponse>(500, "".toResponseBody(null))
        coEvery { mockApi.installHubSkill(any()) } returns errorResponse

        val vm = createViewModel()
        vm.installSkill("failing-skill")

        settle()
        assertFalse(vm.uiState.value.isInstalling)
        assertNull(vm.uiState.value.installingSkillName)
        assertNotNull(vm.uiState.value.toastMessage)
        assertTrue(
            vm.uiState.value.toastMessage!!
                .contains("HTTP 500"),
        )
    }

    @Test
    fun `uninstallSkill success uninstalls skill and refreshes list`() {
        coEvery { mockApi.uninstallHubSkill(any()) } returns
            Response.success(ActionResponse("success", "Uninstalled"))
        coEvery { mockApi.getSkills() } returns Response.success(emptyList())

        val vm = createViewModel()
        vm.uninstallSkill("hub-skill")

        settle()
        assertFalse(vm.uiState.value.isUninstalling)
        assertNull(vm.uiState.value.uninstallingSkillName)
        assertNotNull(vm.uiState.value.toastMessage)
        assertTrue(
            vm.uiState.value.toastMessage!!
                .contains("Uninstalled"),
        )
        assertEquals(0, vm.uiState.value.skills.size)
    }

    @Test
    fun `uninstallSkill error surfaces toast error`() {
        val errorResponse = Response.error<ActionResponse>(500, "".toResponseBody(null))
        coEvery { mockApi.uninstallHubSkill(any()) } returns errorResponse

        val vm = createViewModel()
        vm.uninstallSkill("failing-skill")

        settle()
        assertFalse(vm.uiState.value.isUninstalling)
        assertNull(vm.uiState.value.uninstallingSkillName)
        assertNotNull(vm.uiState.value.toastMessage)
        assertTrue(
            vm.uiState.value.toastMessage!!
                .contains("HTTP 500"),
        )
    }

    @Test
    fun `loadSkills HTTP error table verifies status code propagation`() {
        val errorCodes = listOf(400, 404, 500)
        for (code in errorCodes) {
            val errorResponse = Response.error<List<Skill>>(code, "".toResponseBody(null))
            coEvery { mockApi.getSkills() } returns errorResponse
            val vm = createViewModel()
            vm.loadSkills()
            settle()
            assertFalse("HTTP $code must finish loading", vm.uiState.value.isLoading)
            assertNotNull("HTTP $code must surface an error", vm.uiState.value.errorMessage)
            assertTrue(
                "Expected errorMessage to contain HTTP $code for code $code",
                vm.uiState.value.errorMessage!!
                    .contains("HTTP $code"),
            )
        }
    }

    @Test
    fun `loadSkills network timeout surfaces timeout in error message`() {
        coEvery { mockApi.getSkills() } throws IOException("Timeout")
        val vm = createViewModel()
        vm.loadSkills()
        settle()
        assertTrue(
            vm.uiState.value.errorMessage!!
                .contains("Timeout"),
        )
    }

    @Test
    fun `loadSkills empty response produces empty skills list without error`() {
        coEvery { mockApi.getSkills() } returns Response.success(emptyList())
        val vm = createViewModel()
        vm.loadSkills()
        settle()
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(
            vm.uiState.value.skills
                .isEmpty(),
        )
        assertNull(vm.uiState.value.errorMessage)
    }
}
