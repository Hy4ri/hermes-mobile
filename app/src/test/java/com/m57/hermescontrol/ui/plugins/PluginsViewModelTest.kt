package com.m57.hermescontrol.ui.plugins

import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.AgentPluginInstallBody
import com.m57.hermescontrol.data.model.PluginCatalogCapabilities
import com.m57.hermescontrol.data.model.PluginCatalogEntry
import com.m57.hermescontrol.data.model.PluginCatalogResponse
import com.m57.hermescontrol.data.model.PluginInfo
import com.m57.hermescontrol.data.model.PluginUpdateRequest
import com.m57.hermescontrol.data.model.PluginUpdateResult
import com.m57.hermescontrol.data.model.PluginsHubResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.ws.PluginAutoUpdateState
import com.m57.hermescontrol.data.ws.PluginManageRepository
import com.m57.hermescontrol.data.ws.PluginRemovalRepository
import com.m57.hermescontrol.data.ws.PluginRemovalResult
import com.m57.hermescontrol.data.ws.PluginUpdateAllResult
import com.m57.hermescontrol.data.ws.PluginUpdateAllRow
import com.m57.hermescontrol.data.ws.PluginUpdateAllSkip
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
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
    fun `update waits for consent and only accepts on explicit confirmation`() {
        coEvery { mockApi.updatePlugin("snyk", PluginUpdateRequest(false)) } returns
            Response.success(PluginUpdateResult(consentRequired = true, deltaLines = listOf("tools: scan")))
        coEvery { mockApi.updatePlugin("snyk", PluginUpdateRequest(true)) } returns
            Response.success(PluginUpdateResult(ok = true))
        coEvery { mockApi.getPlugins() } returns Response.success(PluginsHubResponse(emptyList()))
        val viewModel = PluginsViewModel()
        viewModel.updatePlugin("snyk")
        viewModel.updatePlugin("snyk")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf("tools: scan"),
            viewModel.uiState.value.updateConsent
                ?.result
                ?.capabilityDelta,
        )
        assertNull(viewModel.uiState.value.toastMessage)
        assertNull(viewModel.uiState.value.rowBusy)
        coVerify(exactly = 0) { mockApi.updatePlugin(any(), PluginUpdateRequest(true)) }
        coVerify(exactly = 0) { mockApi.getPlugins() }
        viewModel.confirmPluginUpdate()
        viewModel.confirmPluginUpdate()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.updateConsent)
        assertEquals("Plugin updated successfully", viewModel.uiState.value.toastMessage)
        coVerify(exactly = 1) { mockApi.updatePlugin("snyk", PluginUpdateRequest(false)) }
        coVerify(exactly = 1) { mockApi.updatePlugin("snyk", PluginUpdateRequest(true)) }
        coVerify(exactly = 1) { mockApi.getPlugins() }
    }

    @Test
    fun `cancel consent never retries the update`() {
        coEvery { mockApi.updatePlugin(any(), any()) } returns
            Response.success(PluginUpdateResult(consentRequired = true))
        val viewModel = PluginsViewModel()
        viewModel.updatePlugin("snyk")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.cancelPluginUpdate()
        viewModel.confirmPluginUpdate()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.updateConsent)
        assertNull(viewModel.uiState.value.toastMessage)
        coVerify(exactly = 1) { mockApi.updatePlugin(any(), any()) }
    }

    @Test
    fun `ok false is a failure even with HTTP 200 and unchanged true`() {
        coEvery { mockApi.updatePlugin(any(), any()) } returns
            Response.success(PluginUpdateResult(unchanged = true, error = "Update refused"))
        val viewModel = PluginsViewModel()
        viewModel.updatePlugin("snyk")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Failed to update plugin: Update refused", viewModel.uiState.value.toastMessage)
        assertNull(viewModel.uiState.value.rowBusy)
        coVerify(exactly = 0) { mockApi.getPlugins() }
    }

    @Test
    fun `unchanged success reports already up to date`() {
        coEvery { mockApi.updatePlugin(any(), any()) } returns
            Response.success(PluginUpdateResult(ok = true, unchanged = true))
        coEvery { mockApi.getPlugins() } returns Response.success(PluginsHubResponse(emptyList()))
        val viewModel = PluginsViewModel()
        viewModel.updatePlugin("snyk")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Plugin is already up to date", viewModel.uiState.value.toastMessage)
        assertNull(viewModel.uiState.value.rowBusy)
    }

    @Test
    fun `scope switch discards late update consent`() {
        val reply = CompletableDeferred<Response<PluginUpdateResult>>()
        coEvery { mockApi.updatePlugin(any(), any()) } coAnswers { reply.await() }
        val viewModel = PluginsViewModel()
        viewModel.updatePlugin("snyk")
        testDispatcher.scheduler.runCurrent()
        viewModel.clearScopeOwnedState()
        reply.complete(Response.success(PluginUpdateResult(consentRequired = true)))
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.confirmPluginUpdate()
        assertNull(viewModel.uiState.value.updateConsent)
        assertNull(viewModel.uiState.value.rowBusy)
        assertNull(viewModel.uiState.value.toastMessage)
        coVerify(exactly = 1) { mockApi.updatePlugin(any(), any()) }
    }

    @Test
    fun `HTTP update failure releases busy state`() {
        coEvery { mockApi.updatePlugin(any(), any()) } returns Response.error(503, "offline".toResponseBody())
        val viewModel = PluginsViewModel()
        viewModel.updatePlugin("snyk")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(
            viewModel.uiState.value.toastMessage!!
                .contains("Failed to update"),
        )
        assertNull(viewModel.uiState.value.rowBusy)
        coVerify(exactly = 0) { mockApi.getPlugins() }
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

    @Test
    fun `togglePlugin applies optimistic update and enables plugin`() {
        var enabledOnServer = false
        coEvery { mockApi.getPlugins() } answers {
            val status = if (enabledOnServer) "enabled" else "disabled"
            Response.success(PluginsHubResponse(listOf(PluginInfo("plugin-1", "Desc", "1.0", null, status))))
        }
        coEvery { mockApi.enablePlugin("plugin-1") } answers {
            enabledOnServer = true
            Response.success(Unit)
        }

        val viewModel = PluginsViewModel()
        try {
            viewModel.loadPlugins()
            settleRealIo { viewModel.uiState.value.plugins.size == 1 }
            assertFalse(
                viewModel.uiState.value.plugins[0]
                    .enabled,
            )

            viewModel.togglePlugin(viewModel.uiState.value.plugins[0])
            // The optimistic state is visible before the real IO hop returns.
            assertTrue(
                viewModel.uiState.value.plugins[0]
                    .enabled,
            )

            settleRealIo { enabledOnServer }
            assertTrue(
                viewModel.uiState.value.plugins[0]
                    .enabled,
            )
            coVerify(exactly = 1) { mockApi.enablePlugin("plugin-1") }
        } finally {
            // Real IO work must not resume after resetMain().
            runBlocking {
                viewModel.viewModelScope.coroutineContext.job.children
                    .toList()
                    .forEach { it.cancelAndJoin() }
            }
        }
    }

    @Test
    fun `update all publishes a per plugin summary and refreshes after a real update`() {
        val manage = mockk<PluginManageRepository>()
        coEvery { manage.updateAll(any()) } returns
            PluginUpdateAllResult(
                results =
                    listOf(
                        PluginUpdateAllRow(name = "snyk", status = "updated"),
                        PluginUpdateAllRow(name = "camofox", status = "needs_review", detail = "adds a tool"),
                        PluginUpdateAllRow(name = "broken", status = "failed", detail = "clone timed out"),
                    ),
                skipped = listOf(PluginUpdateAllSkip(name = "pinned", reason = "pinned to a commit")),
                current = 3,
            )
        coEvery { mockApi.getPlugins() } returns Response.success(PluginsHubResponse(emptyList()))
        val viewModel = PluginsViewModel(pluginManage = manage)

        viewModel.updateAllPlugins()
        assertTrue(viewModel.uiState.value.updateAllBusy)
        testDispatcher.scheduler.advanceUntilIdle()

        val summary = viewModel.uiState.value.updateAllSummary!!
        assertEquals(1, summary.updated)
        assertEquals(1, summary.needsReview)
        assertEquals(1, summary.failed)
        assertEquals(
            "pinned to a commit",
            summary.result.skipped
                .single()
                .reason,
        )
        assertFalse(viewModel.uiState.value.updateAllBusy)
        coVerify(exactly = 1) { manage.updateAll(any()) }
        coVerify(exactly = 1) { mockApi.getPlugins() }
    }

    @Test
    fun `update all with only review rows does not refresh the plugin list`() {
        val manage = mockk<PluginManageRepository>()
        coEvery { manage.updateAll(any()) } returns
            PluginUpdateAllResult(
                results = listOf(PluginUpdateAllRow(name = "camofox", status = "needs_review", detail = "adds a tool")),
            )
        val viewModel = PluginsViewModel(pluginManage = manage)

        viewModel.updateAllPlugins()
        testDispatcher.scheduler.advanceUntilIdle()

        val summary = viewModel.uiState.value.updateAllSummary!!
        assertEquals(0, summary.updated)
        assertEquals(1, summary.needsReview)
        coVerify(exactly = 0) { mockApi.getPlugins() }
    }

    @Test
    fun `update all with nothing to do reports it instead of opening an empty summary`() {
        val manage = mockk<PluginManageRepository>()
        coEvery { manage.updateAll(any()) } returns PluginUpdateAllResult(current = 4)
        val viewModel = PluginsViewModel(pluginManage = manage)

        viewModel.updateAllPlugins()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.updateAllSummary)
        assertEquals("All plugins are up to date", viewModel.uiState.value.toastMessage)
        assertFalse(viewModel.uiState.value.updateAllBusy)
        coVerify(exactly = 0) { mockApi.getPlugins() }
    }

    @Test
    fun `update all failure releases busy state and reports the gateway error`() {
        val manage = mockk<PluginManageRepository>()
        coEvery { manage.updateAll(any()) } throws IllegalStateException("socket disconnected")
        val viewModel = PluginsViewModel(pluginManage = manage)

        viewModel.updateAllPlugins()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.updateAllSummary)
        assertFalse(viewModel.uiState.value.updateAllBusy)
        assertTrue(
            viewModel.uiState.value.toastMessage!!
                .contains("socket disconnected"),
        )
        coVerify(exactly = 0) { mockApi.getPlugins() }
    }

    @Test
    fun `update all ignores a second request while the sweep is running`() {
        val manage = mockk<PluginManageRepository>()
        val reply = CompletableDeferred<PluginUpdateAllResult>()
        coEvery { manage.updateAll(any()) } coAnswers { reply.await() }
        val viewModel = PluginsViewModel(pluginManage = manage)

        viewModel.updateAllPlugins()
        viewModel.updateAllPlugins()
        testDispatcher.scheduler.runCurrent()
        reply.complete(PluginUpdateAllResult(results = listOf(PluginUpdateAllRow(name = "snyk", status = "current"))))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { manage.updateAll(any()) }
        assertFalse(viewModel.uiState.value.updateAllBusy)
    }

    @Test
    fun `scope switch discards a late update all result`() {
        val manage = mockk<PluginManageRepository>()
        val reply = CompletableDeferred<PluginUpdateAllResult>()
        coEvery { manage.updateAll(any()) } coAnswers { reply.await() }
        val viewModel = PluginsViewModel(pluginManage = manage)

        viewModel.updateAllPlugins()
        testDispatcher.scheduler.runCurrent()
        viewModel.clearScopeOwnedState()
        reply.complete(PluginUpdateAllResult(results = listOf(PluginUpdateAllRow(name = "snyk", status = "updated"))))
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.updateAllSummary)
        assertFalse(viewModel.uiState.value.updateAllBusy)
        assertNull(viewModel.uiState.value.toastMessage)
        coVerify(exactly = 0) { mockApi.getPlugins() }
    }

    @Test
    fun `reviewing a needs review row opens the plugin's own update`() {
        val manage = mockk<PluginManageRepository>()
        coEvery { manage.updateAll(any()) } returns
            PluginUpdateAllResult(
                results = listOf(PluginUpdateAllRow(name = "camofox", status = "needs_review", detail = "adds a tool")),
            )
        coEvery { mockApi.updatePlugin("camofox", PluginUpdateRequest(false)) } returns
            Response.success(PluginUpdateResult(consentRequired = true, deltaLines = listOf("tools: browser")))
        val viewModel = PluginsViewModel(pluginManage = manage)

        viewModel.updateAllPlugins()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.reviewPluginFromSummary("camofox")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.updateAllSummary)
        assertEquals(
            listOf("tools: browser"),
            viewModel.uiState.value.updateConsent
                ?.result
                ?.capabilityDelta,
        )
        coVerify(exactly = 1) { mockApi.updatePlugin("camofox", PluginUpdateRequest(false)) }
    }

    @Test
    fun `auto update loads the real state for the active profile`() {
        val manage = mockk<PluginManageRepository>()
        coEvery { manage.autoUpdateState(any()) } returns PluginAutoUpdateState(autoApply = true, checkHours = 6)
        val viewModel = PluginsViewModel(pluginManage = manage)

        viewModel.loadAutoUpdate()
        assertNull(viewModel.uiState.value.autoUpdate)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(PluginAutoUpdateState(autoApply = true, checkHours = 6), viewModel.uiState.value.autoUpdate)
        coVerify(exactly = 1) { manage.autoUpdateState(any()) }
    }

    @Test
    fun `auto update read failure hides the switch instead of inventing a state`() {
        val manage = mockk<PluginManageRepository>()
        coEvery { manage.autoUpdateState(any()) } throws IllegalStateException("unknown method")
        val viewModel = PluginsViewModel(pluginManage = manage)

        viewModel.loadAutoUpdate()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.autoUpdate)
        assertNull(viewModel.uiState.value.toastMessage)
    }

    @Test
    fun `auto update write applies the returned state`() {
        val manage = mockk<PluginManageRepository>()
        coEvery { manage.autoUpdateState(any()) } returns PluginAutoUpdateState(autoApply = false, checkHours = 24)
        coEvery { manage.setAutoUpdate(true, any()) } returns PluginAutoUpdateState(autoApply = true, checkHours = 24)
        val viewModel = PluginsViewModel(pluginManage = manage)
        viewModel.loadAutoUpdate()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setAutoUpdate(true)
        assertTrue(viewModel.uiState.value.autoUpdateBusy)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.autoUpdate!!
                .autoApply,
        )
        assertFalse(viewModel.uiState.value.autoUpdateBusy)
        assertNull(viewModel.uiState.value.toastMessage)
        coVerify(exactly = 1) { manage.setAutoUpdate(true, any()) }
    }

    @Test
    fun `auto update write failure keeps the previous real state`() {
        val manage = mockk<PluginManageRepository>()
        coEvery { manage.autoUpdateState(any()) } returns PluginAutoUpdateState(autoApply = false, checkHours = 24)
        coEvery { manage.setAutoUpdate(any(), any()) } throws IllegalStateException("managed by a package manager")
        val viewModel = PluginsViewModel(pluginManage = manage)
        viewModel.loadAutoUpdate()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setAutoUpdate(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(
            viewModel.uiState.value.autoUpdate!!
                .autoApply,
        )
        assertFalse(viewModel.uiState.value.autoUpdateBusy)
        assertTrue(
            viewModel.uiState.value.toastMessage!!
                .contains("managed by a package manager"),
        )
    }

    @Test
    fun `scope switch discards a late auto update write`() {
        val manage = mockk<PluginManageRepository>()
        coEvery { manage.autoUpdateState(any()) } returns PluginAutoUpdateState(autoApply = false, checkHours = 24)
        val reply = CompletableDeferred<PluginAutoUpdateState>()
        coEvery { manage.setAutoUpdate(any(), any()) } coAnswers { reply.await() }
        val viewModel = PluginsViewModel(pluginManage = manage)
        viewModel.loadAutoUpdate()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setAutoUpdate(true)
        testDispatcher.scheduler.runCurrent()
        viewModel.clearScopeOwnedState()
        reply.complete(PluginAutoUpdateState(autoApply = true, checkHours = 24))
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.autoUpdate)
        assertFalse(viewModel.uiState.value.autoUpdateBusy)
        assertNull(viewModel.uiState.value.toastMessage)
    }

    private fun settleRealIo(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (condition()) return
            Thread.sleep(10)
        }
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue("Timed out waiting for real IO work", condition())
    }
}
