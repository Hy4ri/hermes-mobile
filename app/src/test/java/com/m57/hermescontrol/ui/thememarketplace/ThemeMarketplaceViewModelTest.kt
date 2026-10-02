package com.m57.hermescontrol.ui.thememarketplace

import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.theme.import.ThemeApplier
import com.m57.hermescontrol.data.theme.import.VsixThemeParser
import com.m57.hermescontrol.data.theme.marketplace.MarketplaceThemeEntry
import com.m57.hermescontrol.data.theme.marketplace.ThemeAssets
import com.m57.hermescontrol.data.theme.marketplace.ThemeMarketplaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Offline proof for the marketplace browser requirements that do not need a
 * device: fake [ThemeMarketplaceRepository] + fake [VsixThemeParser], no
 * network, no `ThemeApplier` side effects.
 *
 * Covers spec M3 (initial load, debounce, pagination, error mapping), M4
 * (select does NOT apply; only `applyTheme` does) and M5 (active badge follows
 * `activeCustomThemeId`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeMarketplaceViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val activeId = MutableStateFlow<String?>(null)
    private val activeName = MutableStateFlow<String?>(null)
    private val applyError = MutableStateFlow<String?>(null)

    private val repository = mockk<ThemeMarketplaceRepository>(relaxed = true)
    private val parser = mockk<VsixThemeParser>(relaxed = true)

    private val dracula =
        MarketplaceThemeEntry(
            extensionId = "dracula-theme.theme-dracula",
            displayName = "Dracula Official",
            publisher = "dracula-theme",
            description = "Dark theme",
            installs = 9_000_000,
        )
    private val nord =
        MarketplaceThemeEntry(
            extensionId = "arcticicestudio.nord",
            displayName = "Nord",
            publisher = "arcticicestudio",
            description = "An arctic, north-bluish color palette",
            installs = 1_200,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ThemeApplier)
        every { ThemeApplier.activeCustomThemeId } returns activeId
        every { ThemeApplier.activeCustomThemeName } returns activeName
        every { ThemeApplier.applyError } returns applyError
        coEvery { repository.search(any(), any(), any()) } returns
            NetworkResult.Success(listOf(dracula, nord))
        coEvery { repository.resolveAssets(any()) } returns
            NetworkResult.Success(ThemeAssets(downloadUrl = "https://example.invalid/a.vsix"))
    }

    @After
    fun tearDown() {
        // resetMain() before unmockkAll(), matching E2eIntegrationTest: unmockkAll()
        // can drop stubs other classes installed on the global Dispatchers object,
        // and a Main that hands back an already-reset TestMainDispatcher throws on
        // dispatch for every later test in this JVM.
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel() =
        ThemeMarketplaceViewModel(
            repository = repository,
            vsixParser = parser,
            applier = ThemeApplier,
        )

    @Test
    fun `initial load lists the catalog without applying anything`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(
                listOf(dracula.extensionId, nord.extensionId),
                vm.uiState.value.entries
                    .map { it.extensionId },
            )
            assertTrue(
                vm.uiState.value.canLoadMore
                    .not(),
            )
            assertNull(vm.uiState.value.selectedEntry)
            verify(exactly = 0) { ThemeApplier.applyFamily(any(), any(), any()) }
        }

    @Test
    fun `tapping a row selects it and does not change the theme`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.selectEntry(dracula)
            advanceUntilIdle()

            // M4: selection is observable, the applier is untouched, and no
            // download/parse happened.
            assertEquals(dracula.extensionId, vm.uiState.value.selectedEntry)
            assertNull(vm.uiState.value.applyingExtensionId)
            assertNull(ThemeApplier.activeCustomThemeId.value)
            verify(exactly = 0) { ThemeApplier.applyFamily(any(), any(), any()) }
            coVerify(exactly = 0) { parser.parseVsix(any()) }
        }

    @Test
    fun `dismissing the detail clears the selection`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.selectEntry(nord)
            advanceUntilIdle()
            vm.selectEntry(null)

            assertNull(vm.uiState.value.selectedEntry)
        }

    @Test
    fun `selecting resolves preview assets once and caches them`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.selectEntry(dracula)
            advanceUntilIdle()
            vm.selectEntry(nord)
            advanceUntilIdle()
            vm.selectEntry(dracula)
            advanceUntilIdle()

            val assets = vm.uiState.value.resolvedAssets
            assertNotNull(assets[dracula.extensionId])
            assertNotNull(assets[nord.extensionId])
            coVerify(exactly = 2) { repository.resolveAssets(any()) }
        }

    @Test
    fun `active badge follows activeCustomThemeId`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.entries
                    .none { it.extensionId == vm.activeCustomThemeId.value },
            )
            activeId.value = dracula.extensionId
            activeName.value = dracula.displayName
            advanceUntilIdle()

            assertEquals(dracula.extensionId, vm.activeCustomThemeId.value)
            assertEquals(dracula.displayName, vm.activeCustomThemeName.value)
        }

    @Test
    fun `pagination appends the next page`() =
        runTest {
            coEvery { repository.search(any(), any(), match { it == 1 }) } returns
                NetworkResult.Success(listOf(dracula, nord))
            coEvery { repository.search(any(), any(), match { it == 2 }) } returns
                NetworkResult.Success(listOf(dracula.copy(extensionId = "x.y", displayName = "More")))

            val vm = viewModel()
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.canLoadMore
                    .not(),
            ) // 2 rows < PAGE_SIZE

            // Every subsequent page returns DISTINCT rows so appending is
            // observable (no cross-page duplicates to dedup away).
            coEvery { repository.search(any(), any(), match { it == 1 }) } returns
                NetworkResult.Success(
                    List(ThemeMarketplaceViewModel.PAGE_SIZE) { dracula.copy(extensionId = "p1_$it") },
                )
            coEvery { repository.search(any(), any(), match { it == 2 }) } returns
                NetworkResult.Success(
                    List(ThemeMarketplaceViewModel.PAGE_SIZE) { dracula.copy(extensionId = "p2_$it") },
                )
            val paged = viewModel()
            advanceUntilIdle()
            assertTrue(paged.uiState.value.canLoadMore)

            paged.loadMore()
            advanceUntilIdle()

            assertEquals(ThemeMarketplaceViewModel.PAGE_SIZE * 2, paged.uiState.value.entries.size)
            assertEquals(
                "p1_0",
                paged.uiState.value.entries
                    .first()
                    .extensionId,
            )
        }

    @Test
    fun `pagination deduplicates rows that repeat across pages`() =
        runTest {
            val page1 = List(ThemeMarketplaceViewModel.PAGE_SIZE) { dracula.copy(extensionId = "p$it") }
            // Page 2 (load-more) re-delivers 15 rows already shown on page 1
            // plus 5 genuinely new ones. The list must not show duplicates.
            val page2 = page1.take(15) + List(5) { dracula.copy(extensionId = "q$it") }
            coEvery { repository.search(any(), any(), match { it == 1 }) } returns NetworkResult.Success(page1)
            coEvery { repository.search(any(), any(), match { it == 2 }) } returns NetworkResult.Success(page2)

            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(ThemeMarketplaceViewModel.PAGE_SIZE, vm.uiState.value.entries.size)

            vm.loadMore()
            advanceUntilIdle()

            assertEquals(ThemeMarketplaceViewModel.PAGE_SIZE + 5, vm.uiState.value.entries.size)
            assertEquals(
                ThemeMarketplaceViewModel.PAGE_SIZE + 5,
                vm.uiState.value.entries
                    .map { it.extensionId }
                    .distinct()
                    .size,
            )
        }

    @Test
    fun `load failure surfaces an error and keeps the list empty`() =
        runTest {
            coEvery { repository.search(any(), any(), any()) } returns
                NetworkResult.Failure(NetworkError.Unknown("boom", RuntimeException("boom")))

            val vm = viewModel()
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.entries
                    .isEmpty(),
            )
            assertNotNull(vm.uiState.value.errorMessage)
            assertTrue(
                vm.uiState.value.isLoading
                    .not(),
            )
        }

    @Test
    fun `retry re-queries from page one`() =
        runTest {
            coEvery { repository.search(any(), any(), any()) } returns
                NetworkResult.Failure(NetworkError.Unknown("boom", RuntimeException("boom")))
            val vm = viewModel()
            advanceUntilIdle()

            coEvery { repository.search(any(), any(), any()) } returns NetworkResult.Success(listOf(dracula))
            vm.retry()
            advanceUntilIdle()

            assertEquals(
                listOf(dracula.extensionId),
                vm.uiState.value.entries
                    .map { it.extensionId },
            )
            assertNull(vm.uiState.value.errorMessage)
        }

    @Test
    fun `debounced search does not fire before the debounce window`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.setQuery("dra")
            testScheduler.advanceTimeBy(ThemeMarketplaceViewModel.SEARCH_DEBOUNCE_MS - 1)
            coVerify(exactly = 1) { repository.search(any(), any(), any()) } // only the initial load
            testScheduler.advanceTimeBy(2)
            coVerify(exactly = 2) { repository.search(any(), any(), any()) }
        }
}
