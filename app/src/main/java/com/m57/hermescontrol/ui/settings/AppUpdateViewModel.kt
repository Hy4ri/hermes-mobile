package com.m57.hermescontrol.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.update.AppUpdateCache
import com.m57.hermescontrol.data.update.AppUpdateChecker
import com.m57.hermescontrol.data.update.AppUpdateState
import com.m57.hermescontrol.data.update.isNewerVersion
import com.m57.hermescontrol.data.update.releaseTag
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Drives the in-app self-update flow (issue #867): check the GitHub
 * releases API, download the release APK, and hand it to the system package
 * installer. The installer launch uses the application context
 * (FLAG_ACTIVITY_NEW_TASK), so no Activity is required.
 *
 * A silent check runs once per installed version (guarded by the
 * `updateCheckDoneForVersion` app pref) — that's what puts the "Update
 * available" badge on the About row without the user tapping anything.
 */
class AppUpdateViewModel(
    application: Application,
    private val checker: AppUpdateChecker = AppUpdateChecker(),
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val installIntentFactory: (Uri) -> Intent = ::buildInstallIntent,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    init {
        // Silent first-launch check: once per installed version. Marked done
        // after the attempt completes (success or failure) so a failed check
        // can retry on the next open, but a dead network can't spam the API
        // on every visit.
        if (AuthManager.getUpdateCheckDoneForVersion() != currentVersion) {
            checkForUpdate()
        } else {
            // Issue #890: the launch check (UpdateNoticeManager) already ran
            // for this version — adopt its result instead of pinging GitHub
            // again, so the About tab agrees with the chat banner.
            val cached = AppUpdateCache.state.value
            if (cached is AppUpdateState.UpdateAvailable || cached is AppUpdateState.UpToDate) {
                _state.value = cached
            }
        }
    }

    /** Manual check from the About row. */
    fun checkForUpdate() {
        if (_state.value is AppUpdateState.Checking) return
        _state.value = AppUpdateState.Checking
        viewModelScope.launch(ioDispatcher) {
            AuthManager.setUpdateCheckDoneForVersion(currentVersion)
            val result =
                try {
                    checker.fetchLatestRelease()
                } catch (e: IOException) {
                    _state.value = AppUpdateState.Error(NETWORK_ERROR)
                    return@launch
                } catch (e: Exception) {
                    _state.value = AppUpdateState.Error(GENERIC_CHECK_ERROR)
                    return@launch
                }
            val info =
                result ?: run {
                    _state.value = AppUpdateState.Error(NO_RELEASE_ERROR)
                    return@launch
                }
            val apk =
                info.apkAsset ?: run {
                    _state.value = AppUpdateState.Error(NO_APK_ERROR)
                    return@launch
                }
            _state.value =
                if (isNewerVersion(info.tagName, currentVersion)) {
                    AppUpdateState.UpdateAvailable(
                        latestTag = info.tagName,
                        apkUrl = apk.browserDownloadUrl,
                        sizeBytes = apk.size,
                    )
                } else {
                    AppUpdateState.UpToDate(latestTag = info.tagName)
                }
            // Keep the launch notice (issue #890) in sync with manual checks.
            AppUpdateCache.update(_state.value)
            _state.value.releaseTag()?.let { AuthManager.setLastKnownLatestTag(it) }
        }
    }

    /** Download the release APK and launch the system installer. */
    fun startUpdate() {
        val available = _state.value as? AppUpdateState.UpdateAvailable ?: return
        if (!canRequestInstalls()) {
            _state.value = AppUpdateState.NeedsUnknownSourcesPermission
            return
        }
        val dest = File(getApplication<Application>().cacheDir, APK_FILE_NAME)
        _state.value = AppUpdateState.Downloading(0f)
        viewModelScope.launch(ioDispatcher) {
            val downloaded =
                checker.downloadApk(available.apkUrl, dest) { progress ->
                    _state.value = AppUpdateState.Downloading(progress)
                }
            if (!downloaded) {
                _state.value = AppUpdateState.Error(DOWNLOAD_ERROR)
                return@launch
            }
            _state.value = AppUpdateState.Installing(available.latestTag)
            launchInstaller(dest)
        }
    }

    private fun canRequestInstalls(): Boolean =
        try {
            getApplication<Application>().packageManager.canRequestPackageInstalls()
        } catch (e: Exception) {
            false
        }

    private fun launchInstaller(apkFile: File) {
        try {
            val context = getApplication<Application>()
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile,
                )
            context.startActivity(installIntentFactory(uri))
        } catch (e: Exception) {
            _state.value = AppUpdateState.Error(INSTALLER_ERROR)
        }
    }

    private companion object {
        const val APK_FILE_NAME = "hermes-update.apk"

        val NETWORK_ERROR = "Network error — check your connection"
        val GENERIC_CHECK_ERROR = "Couldn't check for updates"
        val NO_RELEASE_ERROR = "No release found yet"
        val NO_APK_ERROR = "Release has no APK asset"
        val DOWNLOAD_ERROR = "Download failed — tap to retry"
        val INSTALLER_ERROR = "Couldn't open the installer"
    }
}

/** MIME type of an installable APK (issue #867). */
internal const val APK_MIME = "application/vnd.android.package-archive"

/**
 * The system-installer intent for a downloaded APK: ACTION_VIEW with the
 * package-archive mime, a read grant for the FileProvider URI, and
 * NEW_TASK (launched from the application context).
 */
internal fun buildInstallIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, APK_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
