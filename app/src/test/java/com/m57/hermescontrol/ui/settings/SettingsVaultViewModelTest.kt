package com.m57.hermescontrol.ui.settings

import com.m57.hermescontrol.data.model.VaultItem
import com.m57.hermescontrol.data.model.VaultSource
import com.m57.hermescontrol.data.ws.VaultRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsVaultViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockRepo: VaultRepository

    private val sampleSources =
        listOf(
            VaultSource(
                name = "local",
                displayName = "Hermes vault",
                enabled = true,
                unlocked = true,
                installed = true,
            ),
            VaultSource(
                name = "onepassword",
                displayName = "1Password",
                enabled = true,
                needsUnlock = true,
                unlocked = false,
                installed = true,
            ),
        )

    private val sampleItems =
        listOf(
            VaultItem(id = "item-1", backend = "local", label = "GitHub", identifier = "user@example.com"),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockRepo = mockk()
        coEvery { mockRepo.getSources() } returns sampleSources
        coEvery { mockRepo.listItems() } returns sampleItems
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadVaultData loads sources and items successfully`() {
        val viewModel = SettingsVaultViewModel(vaultRepo = mockRepo, ioDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(2, viewModel.uiState.value.sources.size)
        assertEquals(1, viewModel.uiState.value.items.size)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `toggleSource updates enabled state and reloads sources`() {
        coEvery { mockRepo.setSourceEnabled("onepassword", false) } returns false
        coEvery { mockRepo.getSources() } returns
            listOf(
                sampleSources[0],
                sampleSources[1].copy(enabled = false),
            )

        val viewModel = SettingsVaultViewModel(vaultRepo = mockRepo, ioDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleSource(sampleSources[1], false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { mockRepo.setSourceEnabled("onepassword", false) }
        assertFalse(
            viewModel.uiState.value.sources[1]
                .enabled,
        )
    }

    @Test
    fun `unlockSource calls repo and updates state`() {
        coEvery { mockRepo.unlockSource("onepassword", "mypassword") } returns true
        coEvery { mockRepo.getSources() } returns
            listOf(
                sampleSources[0],
                sampleSources[1].copy(unlocked = true),
            )

        val viewModel = SettingsVaultViewModel(vaultRepo = mockRepo, ioDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.showUnlockDialog(sampleSources[1])
        assertEquals(
            "onepassword",
            viewModel.uiState.value.unlockDialogSource
                ?.name,
        )

        viewModel.unlockSource("onepassword", "mypassword")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { mockRepo.unlockSource("onepassword", "mypassword") }
        assertNull(viewModel.uiState.value.unlockDialogSource)
        assertTrue(
            viewModel.uiState.value.sources[1]
                .unlocked,
        )
        assertEquals("Unlocked onepassword", viewModel.uiState.value.toastMessage)
    }

    @Test
    fun `lockSource locks all and notifies`() {
        coEvery { mockRepo.lockSource(null) } returns true
        coEvery { mockRepo.getSources() } returns sampleSources

        val viewModel = SettingsVaultViewModel(vaultRepo = mockRepo, ioDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.lockSource(null)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { mockRepo.lockSource(null) }
        assertEquals("All password managers locked", viewModel.uiState.value.toastMessage)
    }
}
