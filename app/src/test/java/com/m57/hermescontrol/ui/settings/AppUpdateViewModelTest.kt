package com.m57.hermescontrol.ui.settings

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.update.AppUpdateCache
import com.m57.hermescontrol.data.update.AppUpdateChecker
import com.m57.hermescontrol.data.update.AppUpdateState
import com.m57.hermescontrol.data.update.UpdateInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * AppUpdateViewModel state machine (issue #867): silent first-launch check,
 * check/update flows, the unknown-sources gate, and the installer launch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var packageManager: PackageManager
    private lateinit var checker: AppUpdateChecker

    private val currentVersion = "1.21.0"

    private fun updateInfo(
        tag: String = "v1.22.0",
        apkName: String = "hermes-mobile-v1.22.0.apk",
    ): UpdateInfo =
        UpdateInfo(
            tagName = tag,
            assets =
                listOf(
                    UpdateInfo.Asset(
                        name = apkName,
                        size = 12345678L,
                        browserDownloadUrl = "https://example.com/$apkName",
                    ),
                ),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        AppUpdateCache.reset()
        mockkObject(AuthManager)
        every { AuthManager.getUpdateCheckDoneForVersion() } returns null
        every { AuthManager.setUpdateCheckDoneForVersion(any()) } returns Unit
        every { AuthManager.setLastKnownLatestTag(any()) } returns Unit
        every { AuthManager.getLastUpdateCheckTimestamp() } returns 0L
        every { AuthManager.setLastUpdateCheckTimestamp(any()) } returns Unit
        every { AuthManager.getDismissedUpdateTag() } returns null
        every { AuthManager.setDismissedUpdateTag(any()) } returns Unit

        app = mockk(relaxed = true)
        every { app.cacheDir } returns File(System.getProperty("java.io.tmpdir"))
        every { app.packageName } returns "com.m57.hermescontrol"
        packageManager = mockk(relaxed = true)
        every { app.packageManager } returns packageManager
        every { packageManager.canRequestPackageInstalls() } returns true

        checker = mockk()
        coEvery { checker.fetchLatestRelease() } returns updateInfo()
        coEvery { checker.downloadApk(any(), any(), any()) } returns true
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun createViewModel(): AppUpdateViewModel = AppUpdateViewModel(app, checker, currentVersion, testDispatcher)

    // ── Silent first-launch check ───────────────────────────────────────

    @Test
    fun init_runsSilentCheckWhenNeverChecked() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            coVerify(exactly = 1) { checker.fetchLatestRelease() }
            assertEquals(
                AppUpdateState.UpdateAvailable("v1.22.0", "https://example.com/hermes-mobile-v1.22.0.apk", 12345678L),
                vm.state.value,
            )
            coVerify(exactly = 1) { AuthManager.setUpdateCheckDoneForVersion(currentVersion) }
        }

    @Test
    fun init_adoptsCachedResultWhenPresent() =
        runTest {
            AppUpdateCache.update(
                AppUpdateState.UpdateAvailable("v1.22.0", "https://example.com/apk", 123L),
            )

            val vm = createViewModel()
            advanceUntilIdle()

            coVerify(exactly = 0) { checker.fetchLatestRelease() }
            assertEquals(
                AppUpdateState.UpdateAvailable("v1.22.0", "https://example.com/apk", 123L),
                vm.state.value,
            )
        }

    @Test
    fun init_rechecksOnVersionBump() =
        runTest {
            every { AuthManager.getUpdateCheckDoneForVersion() } returns "1.20.0"

            val vm = createViewModel()
            advanceUntilIdle()

            coVerify(exactly = 1) { checker.fetchLatestRelease() }
        }

    @Test
    fun init_adoptsLaunchCheckResultWithoutNetwork() =
        runTest {
            // Issue #890: the launch check already ran for this version and
            // found an update — the About tab must adopt it, not ping GitHub.
            every { AuthManager.getUpdateCheckDoneForVersion() } returns currentVersion
            AppUpdateCache.update(
                AppUpdateState.UpdateAvailable(
                    latestTag = "v1.22.0",
                    apkUrl = "https://example.com/hermes-mobile-v1.22.0.apk",
                    sizeBytes = 12345678L,
                ),
            )

            val vm = createViewModel()
            advanceUntilIdle()

            coVerify(exactly = 0) { checker.fetchLatestRelease() }
            assertEquals(
                AppUpdateState.UpdateAvailable("v1.22.0", "https://example.com/hermes-mobile-v1.22.0.apk", 12345678L),
                vm.state.value,
            )
        }

    // ── Manual check ────────────────────────────────────────────────────

    @Test
    fun checkForUpdate_upToDateWhenSameVersion() =
        runTest {
            coEvery { checker.fetchLatestRelease() } returns updateInfo(tag = "v1.21.0")

            val vm = createViewModel()
            advanceUntilIdle()
            assertEquals(AppUpdateState.UpToDate("v1.21.0"), vm.state.value)
        }

    @Test
    fun checkForUpdate_networkFailure_surfacesError() =
        runTest {
            coEvery { checker.fetchLatestRelease() } throws IOException("boom")

            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is AppUpdateState.Error)
            assertEquals("Network error — check your connection", (state as AppUpdateState.Error).message)
        }

    @Test
    fun checkForUpdate_noReleaseYet_surfacesError() =
        runTest {
            coEvery { checker.fetchLatestRelease() } returns null

            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is AppUpdateState.Error)
            assertEquals("No release found yet", (state as AppUpdateState.Error).message)
        }

    @Test
    fun checkForUpdate_releaseWithoutApk_surfacesError() =
        runTest {
            coEvery { checker.fetchLatestRelease() } returns UpdateInfo(tagName = "v1.22.0")

            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is AppUpdateState.Error)
            assertEquals("Release has no APK asset", (state as AppUpdateState.Error).message)
        }

    @Test
    fun checkForUpdate_marksDoneEvenWhenCheckFails() =
        runTest {
            coEvery { checker.fetchLatestRelease() } throws IOException("boom")

            val vm = createViewModel()
            advanceUntilIdle()

            coVerify(exactly = 1) { AuthManager.setUpdateCheckDoneForVersion(currentVersion) }
        }

    // ── Update flow ─────────────────────────────────────────────────────

    @Test
    fun startUpdate_downloadsAndLaunchesInstaller() =
        runTest {
            val uri = mockk<Uri>()
            mockkStatic(FileProvider::class)
            every { FileProvider.getUriForFile(any(), any(), any()) } returns uri

            // android.content.Intent can't be constructed in a plain JVM test,
            // so the intent factory is injected; assert the VM launches
            // exactly the intent the factory built for the APK URI.
            val intentMock = mockk<Intent>()
            val capturedIntents = mutableListOf<Intent>()
            every { app.startActivity(capture(capturedIntents)) } returns Unit

            val vm =
                AppUpdateViewModel(app, checker, currentVersion, testDispatcher) { intentMock }
            advanceUntilIdle()
            assertTrue(vm.state.value is AppUpdateState.UpdateAvailable)

            vm.startUpdate()
            advanceUntilIdle()

            coVerify(exactly = 1) {
                checker.downloadApk("https://example.com/hermes-mobile-v1.22.0.apk", any(), any())
            }
            assertEquals(AppUpdateState.Installing("v1.22.0"), vm.state.value)
            assertTrue("installer must be launched", capturedIntents.isNotEmpty())
            assertTrue("launched intent must be the factory's", capturedIntents[0] === intentMock)
            verify { FileProvider.getUriForFile(any(), "com.m57.hermescontrol.fileprovider", any()) }
        }

    @Test
    fun startUpdate_withoutUnknownSourcesPermission_opensGate() =
        runTest {
            every { packageManager.canRequestPackageInstalls() } returns false

            val vm = createViewModel()
            advanceUntilIdle()

            vm.startUpdate()
            advanceUntilIdle()

            assertEquals(AppUpdateState.NeedsUnknownSourcesPermission, vm.state.value)
            coVerify(exactly = 0) { checker.downloadApk(any(), any(), any()) }
        }

    @Test
    fun startUpdate_downloadFailure_surfacesErrorAndRetries() =
        runTest {
            coEvery { checker.downloadApk(any(), any(), any()) } returns false

            val vm = createViewModel()
            advanceUntilIdle()

            vm.startUpdate()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is AppUpdateState.Error)
            assertEquals("Download failed — tap to retry", (state as AppUpdateState.Error).message)
            // Retry path: tapping the row re-runs the check, which recovers.
            vm.checkForUpdate()
            advanceUntilIdle()
            assertTrue(vm.state.value is AppUpdateState.UpdateAvailable)
        }

    @Test
    fun cancelDownload_cancelsJobAndRestoresAvailableState() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            // In-flight download
            coEvery { checker.downloadApk(any(), any(), any()) } coAnswers {
                kotlinx.coroutines.delay(10000)
                true
            }

            vm.startUpdate()
            testDispatcher.scheduler.advanceTimeBy(100)

            assertTrue(vm.state.value is AppUpdateState.Downloading)

            vm.cancelDownload()
            advanceUntilIdle()

            assertTrue(vm.state.value is AppUpdateState.UpdateAvailable)
        }

    @Test
    fun dismissCurrentUpdate_persistsDismissedTagAndHidesDialog() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            AppUpdateCache.showDialog()
            assertTrue(AppUpdateCache.isDialogVisible)

            vm.dismissCurrentUpdate()
            advanceUntilIdle()

            verify(exactly = 1) { AuthManager.setDismissedUpdateTag("v1.22.0") }
            assertTrue(AppUpdateCache.dismissed)
            assertFalse(AppUpdateCache.isDialogVisible)
        }
}
