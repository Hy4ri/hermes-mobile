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
import com.m57.hermescontrol.data.update.isReleaseCandidateVersion
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

    private val _checkReleaseCandidateUpdates =
        MutableStateFlow(AuthManager.isCheckingReleaseCandidateUpdates())

    /** Whether the RC update channel is enabled (issue: RC update toggle). */
    val checkReleaseCandidateUpdates: StateFlow<Boolean> = _checkReleaseCandidateUpdates.asStateFlow()

    private var checkJob: kotlinx.coroutines.Job? = null
    private var downloadJob: kotlinx.coroutines.Job? = null
    private var lastAvailable: AppUpdateState.UpdateAvailable? = null

    init {
        val cached = AppUpdateCache.state.value
        // An RC offer cached while the RC channel was on must not be adopted
        // after the user switched back to stable-only.
        val blocked =
            !_checkReleaseCandidateUpdates.value &&
                cached.releaseTag()?.let(::isReleaseCandidateVersion) == true
        if (blocked) AppUpdateCache.reset()
        if (cached is AppUpdateState.UpdateAvailable && !blocked) {
            lastAvailable = cached
            _state.value = cached
        } else if (cached is AppUpdateState.UpToDate && !blocked) {
            _state.value = cached
        } else {
            checkForUpdate()
        }
    }

    /** Manual check from the About row or dialog. */
    fun checkForUpdate() {
        if (_state.value is AppUpdateState.Checking) return
        runCheck()
    }

    /**
     * Opt in/out of release-candidate updates and immediately re-check on the
     * newly selected channel. Switching back to stable drops any cached RC
     * offer so [startUpdate] cannot install it anyway.
     */
    fun setCheckReleaseCandidateUpdates(enabled: Boolean) {
        if (_checkReleaseCandidateUpdates.value == enabled) return
        AuthManager.setCheckReleaseCandidateUpdates(enabled)
        _checkReleaseCandidateUpdates.value = enabled
        if (!enabled) {
            lastAvailable = null
            AppUpdateCache.reset()
        }
        // A check already in flight captured the previous channel — restart it.
        checkJob?.cancel()
        runCheck()
    }

    private fun runCheck() {
        val includeReleaseCandidates = _checkReleaseCandidateUpdates.value
        _state.value = AppUpdateState.Checking
        checkJob =
            viewModelScope.launch(ioDispatcher) {
                val now = System.currentTimeMillis()
                AuthManager.setUpdateCheckDoneForVersion(currentVersion)
                AuthManager.setLastUpdateCheckTimestamp(now)
                val result =
                    try {
                        checker.fetchLatestRelease(includeReleaseCandidates)
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
                        AppUpdateState
                            .UpdateAvailable(
                                latestTag = info.tagName,
                                apkUrl = apk.browserDownloadUrl,
                                sizeBytes = apk.size,
                                releaseNotes = info.body,
                            ).also { lastAvailable = it }
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
        val available =
            (_state.value as? AppUpdateState.UpdateAvailable)
                ?: lastAvailable
                ?: (AppUpdateCache.state.value as? AppUpdateState.UpdateAvailable)
                ?: return
        lastAvailable = available
        if (!canRequestInstalls()) {
            _state.value = AppUpdateState.NeedsUnknownSourcesPermission
            return
        }
        val dest = File(getApplication<Application>().cacheDir, APK_FILE_NAME)
        _state.value = AppUpdateState.Downloading(0f)
        downloadJob?.cancel()
        downloadJob =
            viewModelScope.launch(ioDispatcher) {
                val downloaded =
                    checker.downloadApk(available.apkUrl, dest) { progress ->
                        _state.value = AppUpdateState.Downloading(progress)
                    }
                if (!downloaded) {
                    if (downloadJob?.isCancelled != true) {
                        _state.value = AppUpdateState.Error(DOWNLOAD_ERROR)
                    }
                    return@launch
                }
                _state.value = AppUpdateState.Installing(available.latestTag)
                launchInstaller(dest)
            }
    }

    /** Cancel in-flight download and return to UpdateAvailable. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val available =
            lastAvailable
                ?: (AppUpdateCache.state.value as? AppUpdateState.UpdateAvailable)
        if (available != null) {
            _state.value = available
        } else {
            _state.value = AppUpdateState.Idle
        }
    }

    /** Dismiss the current update tag so it stops nagging. */
    fun dismissCurrentUpdate() {
        val tag =
            _state.value.releaseTag()
                ?: lastAvailable?.latestTag
                ?: AppUpdateCache.state.value.releaseTag()
        if (tag != null) {
            AuthManager.setDismissedUpdateTag(tag)
        }
        AppUpdateCache.dismiss()
        AppUpdateCache.hideDialog()
    }

    /** Resume install when returning from Unknown Sources permission screen. */
    fun resumeInstallAfterPermission() {
        if (canRequestInstalls()) {
            val dest = File(getApplication<Application>().cacheDir, APK_FILE_NAME)
            val available =
                (_state.value as? AppUpdateState.UpdateAvailable)
                    ?: lastAvailable
                    ?: (AppUpdateCache.state.value as? AppUpdateState.UpdateAvailable)
            if (dest.exists() && dest.length() > 0 && available != null) {
                _state.value = AppUpdateState.Installing(available.latestTag)
                launchInstaller(dest)
            } else {
                startUpdate()
            }
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
