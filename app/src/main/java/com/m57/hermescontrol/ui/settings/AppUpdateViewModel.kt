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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

    /**
     * Bumped by every check. The blocking OkHttp call underneath cannot be
     * interrupted, so a cancelled check may still be running; results are only
     * published when this token is unchanged (and the channel still matches).
     */
    private var checkGeneration = 0

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
     * offer, and [updateAvailableForInstall] refuses an RC on the stable channel,
     * so a stale offer can never be installed.
     */
    fun setCheckReleaseCandidateUpdates(enabled: Boolean) {
        if (_checkReleaseCandidateUpdates.value == enabled) return
        AuthManager.setCheckReleaseCandidateUpdates(enabled)
        _checkReleaseCandidateUpdates.value = enabled
        if (!enabled) {
            lastAvailable = null
            AppUpdateCache.reset()
        }
        // A check already in flight captured the previous channel — cancel it and
        // bump the generation so a late result can never be published.
        checkJob?.cancel()
        runCheck()
    }

    private fun runCheck() {
        val generation = ++checkGeneration
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
                    } catch (e: CancellationException) {
                        // Cancellation is control flow — it must never be reported
                        // as a failed check.
                        throw e
                    } catch (e: IOException) {
                        if (isCurrentCheck(generation, includeReleaseCandidates)) {
                            _state.value = AppUpdateState.Error(NETWORK_ERROR)
                        }
                        return@launch
                    } catch (e: Exception) {
                        if (isCurrentCheck(generation, includeReleaseCandidates)) {
                            _state.value = AppUpdateState.Error(GENERIC_CHECK_ERROR)
                        }
                        return@launch
                    }

                // The OkHttp call underneath blocks and cannot be interrupted, so a
                // newer check — or a channel switch — may have happened while it ran.
                ensureActive()
                if (!isCurrentCheck(generation, includeReleaseCandidates)) return@launch

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
                val state =
                    if (isNewerVersion(info.tagName, currentVersion)) {
                        AppUpdateState.UpdateAvailable(
                            latestTag = info.tagName,
                            apkUrl = apk.browserDownloadUrl,
                            sizeBytes = apk.size,
                            releaseNotes = info.body,
                        )
                    } else {
                        AppUpdateState.UpToDate(latestTag = info.tagName)
                    }
                // Fail closed: a release-candidate result is never published once
                // the RC channel is off, however it reached this point.
                if (!isAdmissibleOnCurrentChannel(state.releaseTag())) return@launch
                if (state is AppUpdateState.UpdateAvailable) lastAvailable = state
                _state.value = state
                // Keep the launch notice (issue #890) in sync with manual checks.
                AppUpdateCache.update(state)
                state.releaseTag()?.let { AuthManager.setLastKnownLatestTag(it) }
            }
    }

    /**
     * True when [generation] is still the newest check *and* the channel that
     * check was started for is still the selected one.
     */
    private fun isCurrentCheck(
        generation: Int,
        includeReleaseCandidates: Boolean,
    ): Boolean = generation == checkGeneration && includeReleaseCandidates == _checkReleaseCandidateUpdates.value

    /** A release-candidate tag may only be used while the RC channel is on. */
    private fun isAdmissibleOnCurrentChannel(tag: String?): Boolean =
        tag == null || !isReleaseCandidateVersion(tag) || _checkReleaseCandidateUpdates.value

    /**
     * The update this ViewModel may install right now, or null.
     *
     * Fails closed: a release-candidate offer is refused while the RC channel is
     * off, however it reached held state. A refusal drops the stale offer and
     * re-checks on the current channel instead of dead-ending.
     */
    private fun updateAvailableForInstall(): AppUpdateState.UpdateAvailable? {
        val available =
            (_state.value as? AppUpdateState.UpdateAvailable)
                ?: lastAvailable
                ?: (AppUpdateCache.state.value as? AppUpdateState.UpdateAvailable)
                ?: return null
        if (!isAdmissibleOnCurrentChannel(available.latestTag)) {
            lastAvailable = null
            AppUpdateCache.reset()
            checkForUpdate()
            return null
        }
        return available
    }

    /** Download the release APK and launch the system installer. */
    fun startUpdate() {
        val available = updateAvailableForInstall() ?: return
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
            val available = updateAvailableForInstall()
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
