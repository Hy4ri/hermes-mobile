package com.m57.hermescontrol.ui.plugins

import com.m57.hermescontrol.data.model.AgentPluginInstallBody
import com.m57.hermescontrol.data.model.PluginCatalogCapabilities
import com.m57.hermescontrol.data.model.PluginCatalogEntry
import com.m57.hermescontrol.data.model.PluginCatalogResponse
import com.m57.hermescontrol.data.model.PluginInfo
import com.m57.hermescontrol.data.model.PluginsHubResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.ws.PluginRemovalRepository
import com.m57.hermescontrol.data.ws.PluginRemovalResult
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
    private val removablePlugin =
        PluginInfo(name = "snyk", runtimeStatus = "inactive", canRemove = true, source = "user")

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

    @Test
    fun `inactive hub plugin enables without invoking install`() {
        coEvery { mockApi.enablePlugin("snyk") } returns Response.success(Unit)
        coEvery { mockApi.getPlugins() } returns
            Response.success(PluginsHubResponse(plugins = listOf(removablePlugin.copy(runtimeStatus = "enabled"))))
        val viewModel = PluginsViewModel()

        viewModel.activatePlugin(removablePlugin)
        assertEquals("snyk", viewModel.uiState.value.rowBusy)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.plugins
                .single()
                .enabled,
        )
        assertNull(viewModel.uiState.value.rowBusy)
        coVerify(exactly = 1) { mockApi.enablePlugin("snyk") }
        coVerify(exactly = 0) { mockApi.installPlugin(any()) }
    }

    @Test
    fun `inactive plugin can be removed after confirmation and verified refresh`() {
        coEvery { mockApi.getPlugins() } returnsMany
            listOf(
                Response.success(PluginsHubResponse(plugins = listOf(removablePlugin))),
                Response.success(PluginsHubResponse(plugins = emptyList())),
            )
        val removal = mockk<PluginRemovalRepository>()
        coEvery { removal.remove("snyk") } returns PluginRemovalResult(ok = true, name = "snyk")
        val viewModel = PluginsViewModel(removal)
        viewModel.loadPlugins()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.requestRemovePlugin("snyk")
        assertEquals("snyk", viewModel.uiState.value.removeConfirmPlugin)
        viewModel.confirmRemovePlugin()
        assertEquals("snyk", viewModel.uiState.value.rowBusy)
        assertEquals(1, viewModel.uiState.value.plugins.size)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.plugins
                .isEmpty(),
        )
        assertEquals("Plugin uninstalled successfully", viewModel.uiState.value.toastMessage)
        assertNull(viewModel.uiState.value.rowBusy)
        coVerify(exactly = 1) { removal.remove("snyk") }
        coVerify(exactly = 2) { mockApi.getPlugins() }
    }

    @Test
    fun `built in plugin cannot enter removal flow even if asked directly`() {
        coEvery { mockApi.getPlugins() } returns
            Response.success(
                PluginsHubResponse(
                    plugins = listOf(PluginInfo(name = "builtin", source = "bundled", canRemove = true)),
                ),
            )
        val removal = mockk<PluginRemovalRepository>()
        val viewModel = PluginsViewModel(removal)
        viewModel.loadPlugins()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.requestRemovePlugin("builtin")
        viewModel.confirmRemovePlugin()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.removeConfirmPlugin)
        coVerify(exactly = 0) { removal.remove(any()) }
    }

    @Test
    fun `gateway refusal and disconnection leave plugin in list with reason`() {
        coEvery { mockApi.getPlugins() } returns
            Response.success(PluginsHubResponse(plugins = listOf(removablePlugin)))
        val removal = mockk<PluginRemovalRepository>()
        coEvery { removal.remove("snyk") } returns PluginRemovalResult(ok = false, error = "not a user install")
        val viewModel = PluginsViewModel(removal)
        viewModel.loadPlugins()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.requestRemovePlugin("snyk")
        viewModel.confirmRemovePlugin()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.plugins.size)
        assertTrue(
            viewModel.uiState.value.toastMessage!!
                .contains("not a user install"),
        )

        coEvery { removal.remove("snyk") } throws IllegalStateException("socket disconnected")
        viewModel.requestRemovePlugin("snyk")
        viewModel.confirmRemovePlugin()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.plugins.size)
        assertTrue(
            viewModel.uiState.value.toastMessage!!
                .contains("socket disconnected"),
        )
        coVerify(exactly = 1) { mockApi.getPlugins() }
    }

    @Test
    fun `success reply without verified removal retains row and reports mismatch`() {
        coEvery { mockApi.getPlugins() } returns
            Response.success(PluginsHubResponse(plugins = listOf(removablePlugin)))
        val removal = mockk<PluginRemovalRepository>()
        coEvery { removal.remove("snyk") } returns PluginRemovalResult(ok = true, name = "snyk")
        val viewModel = PluginsViewModel(removal)
        viewModel.loadPlugins()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.requestRemovePlugin("snyk")
        viewModel.confirmRemovePlugin()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.plugins.size)
        assertTrue(
            viewModel.uiState.value.toastMessage!!
                .contains("still appears"),
        )
    }

    @Test
    fun `failed verification preserves row without success toast`() {
        coEvery { mockApi.getPlugins() } returnsMany
            listOf(
                Response.success(PluginsHubResponse(plugins = listOf(removablePlugin))),
                Response.error(503, "offline".toResponseBody()),
            )
        val removal = mockk<PluginRemovalRepository>()
        coEvery { removal.remove("snyk") } returns PluginRemovalResult(ok = true, name = "snyk")
        val viewModel = PluginsViewModel(removal)
        viewModel.loadPlugins()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.requestRemovePlugin("snyk")
        viewModel.confirmRemovePlugin()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.plugins.size)
        assertTrue(
            viewModel.uiState.value.toastMessage!!
                .contains("could not verify"),
        )
    }

    @Test
    fun `scope switch discards late remove reply`() {
        coEvery { mockApi.getPlugins() } returns
            Response.success(PluginsHubResponse(plugins = listOf(removablePlugin)))
        val reply = CompletableDeferred<PluginRemovalResult>()
        val removal = mockk<PluginRemovalRepository>()
        coEvery { removal.remove("snyk") } coAnswers { reply.await() }
        val viewModel = PluginsViewModel(removal)
        viewModel.loadPlugins()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.requestRemovePlugin("snyk")
        viewModel.confirmRemovePlugin()
        testDispatcher.scheduler.runCurrent()
        viewModel.clearScopeOwnedState()
        reply.complete(PluginRemovalResult(ok = true, name = "snyk"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.plugins
                .isEmpty(),
        )
        assertNull(viewModel.uiState.value.toastMessage)
        coVerify(exactly = 1) { mockApi.getPlugins() }
    }
}
