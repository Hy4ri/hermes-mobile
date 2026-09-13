package com.m57.hermescontrol.ui.plugins

import com.m57.hermescontrol.data.model.AgentPluginInstallBody
import com.m57.hermescontrol.data.model.PluginCatalogCapabilities
import com.m57.hermescontrol.data.model.PluginCatalogEntry
import com.m57.hermescontrol.data.model.PluginCatalogResponse
import com.m57.hermescontrol.data.model.PluginInfo
import com.m57.hermescontrol.data.model.PluginsHubResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class PluginsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService

    private val sampleCatalogEntry =
        PluginCatalogEntry(
            name = "snyk",
            catalogName = "snyk",
            repo = "https://github.com/NousResearch/hermes-plugin-snyk",
            sha = "30e0adfeaa181190bc1f81cf434a94aa6a56015f",
            shaShort = "30e0adf",
            description = "Security scanning for dependencies and code.",
            maintainer = "NousResearch",
            tier = "official",
            capabilities =
                PluginCatalogCapabilities(
                    providesTools = listOf("snyk_test"),
                ),
            installed = false,
            updateAvailable = false,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockApi = mockk()
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `switch tab to CATALOG automatically triggers catalog load`() {
        coEvery { mockApi.getPluginCatalog() } returns
            Response.success(PluginCatalogResponse(entries = listOf(sampleCatalogEntry)))

        val viewModel = PluginsViewModel()
        viewModel.setTab(PluginsTab.CATALOG)

        assertEquals(PluginsTab.CATALOG, viewModel.uiState.value.selectedTab)
        assertTrue(viewModel.uiState.value.isCatalogLoading)

        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isCatalogLoading)
        assertEquals(1, viewModel.uiState.value.catalogEntries.size)
        assertEquals(
            "snyk",
            viewModel.uiState.value.catalogEntries
                .first()
                .name,
        )
        assertTrue(
            viewModel.uiState.value.catalogEntries
                .first()
                .isOfficial,
        )
        assertNull(viewModel.uiState.value.catalogErrorMessage)

        coVerify(exactly = 1) { mockApi.getPluginCatalog() }
    }

    @Test
    fun `loadCatalog handles api error gracefully`() {
        coEvery { mockApi.getPluginCatalog() } returns
            Response.error(500, "Internal Server Error".toResponseBody())

        val viewModel = PluginsViewModel()
        viewModel.loadCatalog()

        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isCatalogLoading)
        assertTrue(
            viewModel.uiState.value.catalogEntries
                .isEmpty(),
        )
        assertTrue(
            viewModel.uiState.value.catalogErrorMessage
                ?.contains("500") == true,
        )
    }

    @Test
    fun `filter and query updates affect state`() {
        val viewModel = PluginsViewModel()
        viewModel.setCatalogQuery("security")
        viewModel.setCatalogTierFilter("official")

        assertEquals("security", viewModel.uiState.value.catalogQuery)
        assertEquals("official", viewModel.uiState.value.catalogTierFilter)
    }

    @Test
    fun `installCatalogPlugin executes install and refreshes lists`() {
        coEvery {
            mockApi.installPlugin(
                AgentPluginInstallBody(
                    identifier = sampleCatalogEntry.repo ?: "",
                    catalogName = sampleCatalogEntry.name,
                    force = false,
                    enable = true,
                ),
            )
        } returns Response.success(Unit)

        coEvery { mockApi.getPlugins() } returns
            Response.success(PluginsHubResponse(plugins = listOf(PluginInfo(name = "snyk", runtimeStatus = "enabled"))))
        coEvery { mockApi.getPluginCatalog() } returns
            Response.success(
                PluginCatalogResponse(
                    entries = listOf(sampleCatalogEntry.copy(installed = true)),
                ),
            )

        val viewModel = PluginsViewModel()
        viewModel.installCatalogPlugin(sampleCatalogEntry)

        assertEquals("snyk", viewModel.uiState.value.catalogInstallingName)

        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.catalogInstallingName)
        assertEquals("Plugin \"snyk\" installed successfully", viewModel.uiState.value.toastMessage)
        assertEquals(1, viewModel.uiState.value.plugins.size)
        assertTrue(
            viewModel.uiState.value.catalogEntries
                .first()
                .installed,
        )

        coVerify(exactly = 1) {
            mockApi.installPlugin(
                match { it.catalogName == "snyk" && it.enable && !it.force },
            )
        }
    }

    @Test
    fun `installCatalogPlugin failure sets toast error`() {
        coEvery {
            mockApi.installPlugin(any())
        } returns Response.error(400, "Clone failed".toResponseBody())

        val viewModel = PluginsViewModel()
        viewModel.installCatalogPlugin(sampleCatalogEntry)

        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.catalogInstallingName)
        assertTrue(
            viewModel.uiState.value.toastMessage
                ?.contains("Failed to install") == true,
        )
    }
}
