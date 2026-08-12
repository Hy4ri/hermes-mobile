package com.m57.hermescontrol.data.update

import com.m57.hermescontrol.data.local.AuthManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Launch update check (issue #890): the once-per-version guard, the
 * cache write, and the persisted latest tag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateNoticeManagerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val currentVersion = "1.21.0"

    private fun updateInfo(tag: String = "v1.22.0"): UpdateInfo =
        UpdateInfo(
            tagName = tag,
            assets =
                listOf(
                    UpdateInfo.Asset(
                        name = "hermes-mobile-v1.22.0.apk",
                        size = 12345678L,
                        browserDownloadUrl = "https://example.com/hermes-mobile-v1.22.0.apk",
                    ),
                ),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        AppUpdateCache.reset()
        // Unit tests run against the debug variant where the feature is
        // disabled by default — flip the knob on so the behavior is testable.
        UpdateNoticeManager.enabled = true
        mockkObject(AuthManager)
        every { AuthManager.getUpdateCheckDoneForVersion() } returns null
        every { AuthManager.setUpdateCheckDoneForVersion(any()) } returns Unit
        every { AuthManager.getLastKnownLatestTag() } returns null
        every { AuthManager.setLastKnownLatestTag(any()) } returns Unit
    }

    @After
    fun tearDown() {
        UpdateNoticeManager.enabled = false
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun checkOnLaunch_runsOnceWhenNeverChecked() =
        runTest {
            val checker = mockk<AppUpdateChecker>()
            coEvery { checker.fetchLatestRelease() } returns updateInfo()

            UpdateNoticeManager.checkOnLaunch(checker, currentVersion, testDispatcher)
            advanceUntilIdle()

            assertTrue(AppUpdateCache.state.value is AppUpdateState.UpdateAvailable)
            assertEquals(
                "v1.22.0",
                (AppUpdateCache.state.value as AppUpdateState.UpdateAvailable).latestTag,
            )
            verify(exactly = 1) { AuthManager.setUpdateCheckDoneForVersion(currentVersion) }
            verify(exactly = 1) { AuthManager.setLastKnownLatestTag("v1.22.0") }
        }

    @Test
    fun checkOnLaunch_skipsWhenAlreadyCheckedForThisVersion() =
        runTest {
            every { AuthManager.getUpdateCheckDoneForVersion() } returns currentVersion
            val checker = mockk<AppUpdateChecker>()
            coEvery { checker.fetchLatestRelease() } returns updateInfo()

            UpdateNoticeManager.checkOnLaunch(checker, currentVersion, testDispatcher)
            advanceUntilIdle()

            coVerify(exactly = 0) { checker.fetchLatestRelease() }
            assertEquals(AppUpdateState.Idle, AppUpdateCache.state.value)
            verify(exactly = 0) { AuthManager.setUpdateCheckDoneForVersion(currentVersion) }
        }

    @Test
    fun checkOnLaunch_upToDateWhenSameVersion() =
        runTest {
            val checker = mockk<AppUpdateChecker>()
            coEvery { checker.fetchLatestRelease() } returns updateInfo(tag = "v1.21.0")

            UpdateNoticeManager.checkOnLaunch(checker, currentVersion, testDispatcher)
            advanceUntilIdle()

            assertEquals(AppUpdateState.UpToDate("v1.21.0"), AppUpdateCache.state.value)
            verify(exactly = 1) { AuthManager.setLastKnownLatestTag("v1.21.0") }
        }

    @Test
    fun checkOnLaunch_networkFailure_leavesCacheIdle() =
        runTest {
            val checker = mockk<AppUpdateChecker>()
            coEvery { checker.fetchLatestRelease() } throws IOException("boom")

            UpdateNoticeManager.checkOnLaunch(checker, currentVersion, testDispatcher)
            advanceUntilIdle()

            assertEquals(AppUpdateState.Idle, AppUpdateCache.state.value)
            // Still marked done so a dead network can't spam the API per launch.
            verify(exactly = 1) { AuthManager.setUpdateCheckDoneForVersion(currentVersion) }
        }

    @Test
    fun checkOnLaunch_noRelease_leavesCacheIdle() =
        runTest {
            val checker = mockk<AppUpdateChecker>()
            coEvery { checker.fetchLatestRelease() } returns null

            UpdateNoticeManager.checkOnLaunch(checker, currentVersion, testDispatcher)
            advanceUntilIdle()

            assertEquals(AppUpdateState.Idle, AppUpdateCache.state.value)
            assertFalse(AppUpdateCache.dismissed)
        }

    // ── noticeTag (dismissed/restart banner return) ─────────────────────

    @Test
    fun noticeTag_prefersFreshCacheResult() {
        AppUpdateCache.update(
            AppUpdateState.UpdateAvailable(
                latestTag = "v1.22.0",
                apkUrl = "https://example.com/apk",
                sizeBytes = 1L,
            ),
        )
        every { AuthManager.getLastKnownLatestTag() } returns "v0.9.0"

        assertEquals("v1.22.0", UpdateNoticeManager.noticeTag(currentVersion))
    }

    @Test
    fun noticeTag_returnsPersistedNewerTagWhenCacheIdle() {
        every { AuthManager.getLastKnownLatestTag() } returns "v1.22.0"

        assertEquals("v1.22.0", UpdateNoticeManager.noticeTag(currentVersion))
    }

    @Test
    fun noticeTag_nullWhenPersistedTagNotNewer() {
        every { AuthManager.getLastKnownLatestTag() } returns "v1.21.0"

        assertNull(UpdateNoticeManager.noticeTag(currentVersion))
    }

    @Test
    fun noticeTag_nullWhenNothingKnown() {
        assertNull(UpdateNoticeManager.noticeTag(currentVersion))
    }

    // ── debug builds: feature disabled by default (BuildConfig.DEBUG) ───

    @Test
    fun checkOnLaunch_disabled_makesNoNetworkCallAndLeavesCacheIdle() =
        runTest {
            UpdateNoticeManager.enabled = false
            val checker = mockk<AppUpdateChecker>()
            coEvery { checker.fetchLatestRelease() } returns updateInfo()

            UpdateNoticeManager.checkOnLaunch(checker, currentVersion, testDispatcher)
            advanceUntilIdle()

            coVerify(exactly = 0) { checker.fetchLatestRelease() }
            verify(exactly = 0) { AuthManager.setUpdateCheckDoneForVersion(any()) }
            verify(exactly = 0) { AuthManager.setLastKnownLatestTag(any()) }
            assertEquals(AppUpdateState.Idle, AppUpdateCache.state.value)
        }

    @Test
    fun noticeTag_disabled_returnsNullEvenWhenNewerTagPersisted() {
        UpdateNoticeManager.enabled = false
        AppUpdateCache.update(
            AppUpdateState.UpdateAvailable(
                latestTag = "v1.22.0",
                apkUrl = "https://example.com/apk",
                sizeBytes = 1L,
            ),
        )
        every { AuthManager.getLastKnownLatestTag() } returns "v1.22.0"

        assertNull(UpdateNoticeManager.noticeTag(currentVersion))
    }
}
