package com.m57.hermescontrol.ui.settings

import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.BusySendMode
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test
    fun busySendDefaultLoadsAndSaves() {
        every { AuthManager.getBusySendMode() } returns BusySendMode.GUIDE
        val viewModel = createViewModel()
        assertEquals(BusySendMode.GUIDE, viewModel.uiState.value.busySendMode)

        viewModel.onBusySendModeChange(BusySendMode.QUEUE)
        assertEquals(BusySendMode.QUEUE, viewModel.uiState.value.busySendMode)
        verify(exactly = 1) { AuthManager.setBusySendMode(BusySendMode.QUEUE) }
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
        every { AuthManager.isRestoreLastSession() } returns false
        every { AuthManager.setRestoreLastSession(any()) } returns Unit
        every { AuthManager.clearLastOpenedSessionIdsForConnection(any()) } returns Unit
        every { AuthManager.clearLastOpenedSessionId() } returns Unit
        every { AuthManager.getThemePreference() } returns ThemePreference.SYSTEM
        every { AuthManager.isUseDynamicColors() } returns true
        every { AuthManager.getThemePreset() } returns ThemePreset.DEFAULT
        every { AuthManager.isTypingEffectEnabled() } returns true
        every { AuthManager.getBusySendMode() } returns com.m57.hermescontrol.data.model.BusySendMode.CORRECT
        every { AuthManager.setBusySendMode(any()) } returns Unit
        every { AuthManager.getTypingEffectDelayMs() } returns 30
        every { AuthManager.getChatFontScale() } returns 1.0f
        every { AuthManager.isMessageStatsEnabled() } returns false
        every { AuthManager.isUserMessageTokensEnabled() } returns true
        every { AuthManager.isAssistantMessageTokensEnabled() } returns true
        every { AuthManager.isTokensPerSecondEnabled() } returns true
        every { AuthManager.isModelProviderShown() } returns false
        every { AuthManager.isKeepConnectedInBackground() } returns false
        every { AuthManager.getConnectionProfiles() } returns emptyList()
        every { AuthManager.getSelectedProfileId() } answers { storedSelectedProfileId }
        every { AuthManager.baseUrl() } returns "http://127.0.0.1:9119/"
        every { AuthManager.setBaseUrl(any()) } returns Unit
        every { AuthManager.setToken(any()) } returns Unit
        every { AuthManager.setAutoReconnect(any()) } returns Unit
        every { AuthManager.setThemePreference(any()) } returns Unit
        every { AuthManager.setUseDynamicColors(any()) } returns Unit
        every { AuthManager.setThemePreset(any()) } returns Unit
        every { AuthManager.setTypingEffectEnabled(any()) } returns Unit
        every { AuthManager.setTypingEffectDelayMs(any()) } returns Unit
        every { AuthManager.setChatFontScale(any()) } returns Unit
        every { AuthManager.setMessageStatsEnabled(any()) } returns Unit
        every { AuthManager.setUserMessageTokensEnabled(any()) } returns Unit
        every { AuthManager.setAssistantMessageTokensEnabled(any()) } returns Unit
        every { AuthManager.setTokensPerSecondEnabled(any()) } returns Unit
        every { AuthManager.setModelProviderShown(any()) } returns Unit
        every { AuthManager.setKeepConnectedInBackground(any()) } returns Unit
        every { AuthManager.setSelectedProfileId(any()) } answers {
            storedSelectedProfileId = firstArg()
        }
        every { AuthManager.saveConnectionProfiles(any()) } returns Unit
        every { AuthManager.setProfileToken(any(), any()) } returns Unit
        every { AuthManager.getProfileToken(any()) } returns null
        every { AuthManager.ensureDefaultProfile() } returns Unit

        // selectProfile/deleteProfile route the switch through the coordinator
        // (selection + Retrofit + WebSocket re-dial). Mirror the selection
        // side-effect like the real coordinator's setSelectedProfileId does,
        // so loadSettings() observes the new selection.
        mockkObject(ProfileSwitchCoordinator)
        coEvery { ProfileSwitchCoordinator.switchConnectionProfile(any()) } answers {
            storedSelectedProfileId = firstArg()
            Unit
        }
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
    fun testLoadSettings_loadsAllProperties() =
        runTest {
            every { AuthManager.getSelectedProfileId() } answers { "prof-2" }
            every { AuthManager.getBaseUrl() } returns "https://192.168.1.10:9119/"
            every { AuthManager.getToken() } returns "dummy_token"
            every { AuthManager.isAutoReconnect() } returns false
            every { AuthManager.getThemePreference() } returns ThemePreference.DARK
            every { AuthManager.isUseDynamicColors() } returns false
            every { AuthManager.getThemePreset() } returns ThemePreset.CATPPUCCIN
            every { AuthManager.isTypingEffectEnabled() } returns false
            every { AuthManager.getTypingEffectDelayMs() } returns 15
            every { AuthManager.getChatFontScale() } returns 1.30f
            every { AuthManager.getConnectionProfiles() } returns testProfiles
            every { AuthManager.getAppLanguage() } returns "fr"

            val viewModel = SettingsViewModel(ioDispatcher = testDispatcher)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value

            assertEquals("https://192.168.1.10:9119/", state.baseUrl)
            assertEquals("dummy_token", state.token)
            assertEquals(false, state.autoReconnect)
            assertEquals(ThemePreference.DARK, state.themePreference)
            assertEquals(false, state.useDynamicColors)
            assertEquals(ThemePreset.CATPPUCCIN, state.themePreset)
            assertEquals(false, state.typingEffectEnabled)
            assertEquals(15, state.typingEffectDelayMs)
            assertEquals(1.30f, state.chatFontScale)
            assertEquals(testProfiles, state.profiles)
            assertEquals("prof-2", state.selectedProfileId)
            assertEquals("Home", state.renameProfileName)
            assertEquals("fr", state.appLanguage)
        }

    @Test
    fun testOnChatFontScaleChange_updatesStateAndAuthManager() {
        val viewModel = createViewModel()

        viewModel.onChatFontScaleChange(1.15f)

        assertEquals(1.15f, viewModel.uiState.value.chatFontScale)
        verify { AuthManager.setChatFontScale(1.15f) }
    }

    @Test
    fun testMessageStats_loadsAndTogglesIndependently() =
        runTest {
            every { AuthManager.isMessageStatsEnabled() } returns false
            every { AuthManager.isUserMessageTokensEnabled() } returns false
            every { AuthManager.isAssistantMessageTokensEnabled() } returns true
            every { AuthManager.isTokensPerSecondEnabled() } returns false

            val viewModel = SettingsViewModel(ioDispatcher = testDispatcher)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.messageStatsEnabled)
            assertEquals(false, viewModel.uiState.value.showUserMessageTokens)
            assertEquals(true, viewModel.uiState.value.showAssistantMessageTokens)
            assertEquals(false, viewModel.uiState.value.showTokensPerSecond)

            viewModel.onMessageStatsEnabledChange(true)
            assertEquals(true, viewModel.uiState.value.messageStatsEnabled)
            verify { AuthManager.setMessageStatsEnabled(true) }
            verify(exactly = 0) { AuthManager.setUserMessageTokensEnabled(any()) }
            verify(exactly = 0) { AuthManager.setAssistantMessageTokensEnabled(any()) }
            verify(exactly = 0) { AuthManager.setTokensPerSecondEnabled(any()) }

            viewModel.onUserMessageTokensChange(true)
            viewModel.onAssistantMessageTokensChange(false)
            viewModel.onTokensPerSecondChange(true)
            assertEquals(true, viewModel.uiState.value.showUserMessageTokens)
            assertEquals(false, viewModel.uiState.value.showAssistantMessageTokens)
            assertEquals(true, viewModel.uiState.value.showTokensPerSecond)
            verify { AuthManager.setUserMessageTokensEnabled(true) }
            verify { AuthManager.setAssistantMessageTokensEnabled(false) }
            verify { AuthManager.setTokensPerSecondEnabled(true) }

            viewModel.onMessageStatsEnabledChange(false)
            assertEquals(false, viewModel.uiState.value.messageStatsEnabled)
            assertEquals(true, viewModel.uiState.value.showUserMessageTokens)
            assertEquals(false, viewModel.uiState.value.showAssistantMessageTokens)
            assertEquals(true, viewModel.uiState.value.showTokensPerSecond)
            verify(exactly = 2) { AuthManager.setMessageStatsEnabled(any()) }
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

        coVerify { ProfileSwitchCoordinator.switchConnectionProfile("prof-2") }
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

        coVerify { ProfileSwitchCoordinator.switchConnectionProfile(AuthManager.DEFAULT_PROFILE_ID) }
        assertEquals(AuthManager.DEFAULT_PROFILE_ID, viewModel.uiState.value.selectedProfileId)
    }

    @Test
    fun testDeleteProfile_removesProfileAndToken() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles
        every { AuthManager.getSelectedProfileId() } returns "prof-1"

        val viewModel = createViewModel()

        viewModel.deleteProfile("prof-1")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { AuthManager.saveConnectionProfiles(any()) }
        verify { AuthManager.setProfileToken("prof-1", null) }
        // The deleted profile was selected — the fallback switch re-homes
        // everything (selection + Retrofit + WebSocket) to default.
        coVerify { ProfileSwitchCoordinator.switchConnectionProfile(AuthManager.DEFAULT_PROFILE_ID) }
    }

    @Test
    fun testDeleteProfile_nonSelected_doesNotChangeSelectedId() {
        every { AuthManager.getConnectionProfiles() } returns testProfiles
        every { AuthManager.getSelectedProfileId() } returns "prof-1"

        val viewModel = createViewModel()

        viewModel.deleteProfile("prof-2")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { AuthManager.saveConnectionProfiles(any()) }
        verify { AuthManager.setProfileToken("prof-2", null) }
        // Selected profile (prof-1) was NOT deleted — should NOT change selection
        verify(exactly = 0) { AuthManager.setSelectedProfileId(any()) }
        coVerify(exactly = 0) { ProfileSwitchCoordinator.switchConnectionProfile(any()) }
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
    }

    @Test
    fun testRestoreLastSession_loadsAndUpdatesState() {
        every { AuthManager.isRestoreLastSession() } returns true
        val viewModel = createViewModel()

        assertEquals(true, viewModel.uiState.value.restoreLastSession)

        viewModel.onRestoreLastSessionChange(false)
        assertEquals(false, viewModel.uiState.value.restoreLastSession)
        verify { AuthManager.setRestoreLastSession(false) }
    }

    @Test
    fun testDeleteProfile_clearsStoredSessionIdsForConnection() {
        val viewModel = createViewModel()

        viewModel.deleteProfile("prof-1")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { AuthManager.clearLastOpenedSessionIdsForConnection("prof-1") }
    }

    @Test
    fun testShowModelProvider_loadsAndTogglesImmediately() =
        runTest {
            every { AuthManager.isModelProviderShown() } returns false

            val viewModel = SettingsViewModel(ioDispatcher = testDispatcher)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.showModelProvider)

            viewModel.onShowModelProviderChange(true)
            assertEquals(true, viewModel.uiState.value.showModelProvider)
            assertEquals(false, viewModel.uiState.value.isSaved)
            verify { AuthManager.setModelProviderShown(true) }

            viewModel.onShowModelProviderChange(false)
            assertEquals(false, viewModel.uiState.value.showModelProvider)
            verify { AuthManager.setModelProviderShown(false) }
        }

    @Test
    fun testSave_persistsShowModelProvider() =
        runTest {
            val viewModel = SettingsViewModel(ioDispatcher = testDispatcher)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.onShowModelProviderChange(true)
            viewModel.save()
            testDispatcher.scheduler.advanceUntilIdle()

            verify { AuthManager.setModelProviderShown(true) }
            assertEquals(true, viewModel.uiState.value.isSaved)
        }

    @Test
    fun testKeepConnectedInBackground_loadsAndToggles() =
        runTest {
            every { AuthManager.isKeepConnectedInBackground() } returns false

            val viewModel = SettingsViewModel(ioDispatcher = testDispatcher)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(false, viewModel.uiState.value.keepConnectedInBackground)

            viewModel.onKeepConnectedInBackgroundChange(true)
            assertEquals(true, viewModel.uiState.value.keepConnectedInBackground)
            verify { AuthManager.setKeepConnectedInBackground(true) }

            viewModel.onKeepConnectedInBackgroundChange(false)
            assertEquals(false, viewModel.uiState.value.keepConnectedInBackground)
            verify { AuthManager.setKeepConnectedInBackground(false) }
        }

    @Test
    fun testKeepConnectedInBackground_refreshesBothDirectionsFromPersistedSetting() =
        runTest {
            var persisted = false
            every { AuthManager.isKeepConnectedInBackground() } answers { persisted }
            every { AuthManager.setKeepConnectedInBackground(any()) } answers {
                persisted = firstArg()
            }
            val viewModel = SettingsViewModel(ioDispatcher = testDispatcher)
            testDispatcher.scheduler.advanceUntilIdle()

            // An edit from Settings becomes visible when chat opens its menu.
            persisted = true
            viewModel.refreshKeepConnectedInBackground()
            assertEquals(true, viewModel.uiState.value.keepConnectedInBackground)

            // Both menu directions use the existing Settings action and persistence path.
            viewModel.onKeepConnectedInBackgroundChange(false)
            assertEquals(false, persisted)
            assertEquals(false, viewModel.uiState.value.keepConnectedInBackground)
            viewModel.onKeepConnectedInBackgroundChange(true)
            assertEquals(true, persisted)

            persisted = false
            viewModel.refreshKeepConnectedInBackground()
            assertEquals(false, viewModel.uiState.value.keepConnectedInBackground)
        }

    @Test
    fun testSave_persistsKeepConnectedInBackground() =
        runTest {
            val viewModel = SettingsViewModel(ioDispatcher = testDispatcher)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.onKeepConnectedInBackgroundChange(true)
            viewModel.save()
            testDispatcher.scheduler.advanceUntilIdle()

            verify { AuthManager.setKeepConnectedInBackground(true) }
            assertEquals(true, viewModel.uiState.value.isSaved)
        }
}
