package com.m57.hermescontrol.ui.settings

import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.theme.BottomNavDisplayMode
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Tests for [SettingsViewModel] profile operations.
 *
 * TEST-05 (issue #292)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private var storedSelectedProfileId: String? = null

    private val testProfiles =
        listOf(
            ConnectionProfile(id = "prof-1", name = "Work", baseUrl = "http://10.0.0.1:9119/"),
            ConnectionProfile(id = "prof-2", name = "Home", baseUrl = "http://10.0.0.2:9220/"),
        )

    private fun createViewModel(): SettingsViewModel {
        val vm = SettingsViewModel(ioDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockkObject(AuthManager)
        mockkObject(ApiClient)

        // Default stubs
        storedSelectedProfileId = null

        every { AuthManager.getHost() } returns "127.0.0.1"
        every { AuthManager.getPort() } returns 9119
        every { AuthManager.getBaseUrl() } returns "https://127.0.0.1:9119/"
        every { AuthManager.getAppLanguage() } returns "system"
        every { AuthManager.getToken() } returns ""
        every { AuthManager.isAutoReconnect() } returns true
        every { AuthManager.getThemePreference() } returns ThemePreference.SYSTEM
        every { AuthManager.isUseDynamicColors() } returns true
        every { AuthManager.getThemePreset() } returns ThemePreset.DEFAULT
        every { AuthManager.getBottomNavItems() } returns emptyList()
        every { AuthManager.isTypingEffectEnabled() } returns true
        every { AuthManager.getTypingEffectDelayMs() } returns 30
        every { AuthManager.getConnectionProfiles() } returns emptyList()
        every { AuthManager.getSelectedProfileId() } answers { storedSelectedProfileId }
        every { AuthManager.getBottomNavDisplayMode() } returns BottomNavDisplayMode.ICON_AND_TEXT
        every { AuthManager.baseUrl() } returns "http://127.0.0.1:9119/"
        every { AuthManager.setHost(any()) } returns Unit
        every { AuthManager.setPort(any()) } returns Unit
        every { AuthManager.setBaseUrl(any()) } returns Unit
        every { AuthManager.setToken(any()) } returns Unit
        every { AuthManager.setAutoReconnect(any()) } returns Unit
        every { AuthManager.setThemePreference(any()) } returns Unit
        every { AuthManager.setUseDynamicColors(any()) } returns Unit
        every { AuthManager.setThemePreset(any()) } returns Unit
        every { AuthManager.setBottomNavDisplayMode(any()) } returns Unit
        every { AuthManager.setTypingEffectEnabled(any()) } returns Unit
        every { AuthManager.setTypingEffectDelayMs(any()) } returns Unit
        every { AuthManager.setSelectedProfileId(any()) } answers {
            storedSelectedProfileId = firstArg()
        }
        every { AuthManager.saveConnectionProfiles(any()) } returns Unit
        every { AuthManager.setProfileToken(any(), any()) } returns Unit
        every { AuthManager.getProfileToken(any()) } returns null
        every { AuthManager.ensureDefaultProfile() } returns Unit

        // Ensure ApiClient.rebuild() is stubbed (used by selectProfile)
        every { ApiClient.rebuild() } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun testLoadSettings_loadsProfiles() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles
        storedSelectedProfileId = "prof-1"

        val viewModel = createViewModel()
        val state = viewModel.uiState.value

        assertEquals(2, state.profiles.size)
        assertEquals("prof-1", state.selectedProfileId)
        assertEquals("Work", state.renameProfileName)
    }

    @Test
    fun testLoadSettings_noSelectedProfile_renameEmpty() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles

        val viewModel = createViewModel()
        assertEquals("", viewModel.uiState.value.renameProfileName)
    }

    @Test
    fun testSelectProfile_updatesAndRebuildsApi() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles

        val viewModel = createViewModel()

        viewModel.selectProfile("prof-2")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { AuthManager.setSelectedProfileId("prof-2") }
        verify { ApiClient.rebuild() }
        assertEquals("prof-2", viewModel.uiState.value.selectedProfileId)
        assertEquals("Home", viewModel.uiState.value.renameProfileName)
    }

    @Test
    fun testSelectProfile_nullFallsBackToDefault() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles
        storedSelectedProfileId = "prof-1"

        val viewModel = createViewModel()

        viewModel.selectProfile(AuthManager.DEFAULT_PROFILE_ID)
        testDispatcher.scheduler.advanceUntilIdle()

        verify { AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID) }
        verify { ApiClient.rebuild() }
        assertEquals(AuthManager.DEFAULT_PROFILE_ID, viewModel.uiState.value.selectedProfileId)
    }

    @Test
    fun testOnRenameProfileNameChange_updatesState() {
        val viewModel = createViewModel()
        viewModel.onRenameProfileNameChange("New Name")
        assertEquals("New Name", viewModel.uiState.value.renameProfileName)
        assertFalse(viewModel.uiState.value.isSaved)
    }

    @Test
    fun testRenameProfile_updatesProfileName() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles
        every { AuthManager.getSelectedProfileId() } returns "prof-1"

        val viewModel = createViewModel()
        viewModel.onRenameProfileNameChange("Renamed Work")
        viewModel.renameProfile()

        verify { AuthManager.saveConnectionProfiles(any()) }
    }

    @Test
    fun testRenameProfile_withBlankName_doesNothing() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles
        every { AuthManager.getSelectedProfileId() } returns "prof-1"

        val viewModel = createViewModel()
        viewModel.onRenameProfileNameChange("   ")
        viewModel.renameProfile()

        verify(exactly = 0) { AuthManager.saveConnectionProfiles(any()) }
    }

    @Test
    fun testRenameProfile_noSelectedProfile_doesNothing() {
        val viewModel = createViewModel()
        viewModel.onRenameProfileNameChange("New Name")
        viewModel.renameProfile()

        verify(exactly = 0) { AuthManager.saveConnectionProfiles(any()) }
    }

    @Test
    fun testDeleteProfile_removesProfileAndToken() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles
        every { AuthManager.getSelectedProfileId() } returns "prof-1"

        val viewModel = createViewModel()

        viewModel.deleteProfile("prof-1")

        verify { AuthManager.saveConnectionProfiles(any()) }
        verify { AuthManager.setProfileToken("prof-1", null) }
        verify { AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID) }
        verify { ApiClient.rebuild() }
    }

    @Test
    fun testDeleteProfile_nonSelected_doesNotChangeSelectedId() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles
        every { AuthManager.getSelectedProfileId() } returns "prof-1"

        val viewModel = createViewModel()

        viewModel.deleteProfile("prof-2")

        verify { AuthManager.saveConnectionProfiles(any()) }
        verify { AuthManager.setProfileToken("prof-2", null) }
        // Selected profile (prof-1) was NOT deleted — should NOT change selection
        verify(exactly = 0) { AuthManager.setSelectedProfileId(any()) }
        verify { ApiClient.rebuild() }
    }

    @Test
    fun testSaveProfileFromDialog_addsNewProfileAndTriggersLoginRedirection() {
        every { AuthManager.getConnectionProfiles() } returns emptyList()
        val viewModel = createViewModel()

        viewModel.openAddProfile()
        viewModel.onDialogProfileNameChange("Test Profile")
        viewModel.onDialogProfileBaseUrlChange("https://192.168.1.100:9999/")

        viewModel.saveProfileFromDialog()
        testDispatcher.scheduler.advanceUntilIdle()

        verify { AuthManager.saveConnectionProfiles(any()) }
        verify { AuthManager.setSelectedProfileId(any()) }
        assertEquals(true, viewModel.uiState.value.navigateToLogin)

        viewModel.clearNavigateToLogin()
        assertEquals(false, viewModel.uiState.value.navigateToLogin)
    }
}
