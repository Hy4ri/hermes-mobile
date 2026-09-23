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
import com.m57.hermescontrol.data.update.UpdateNoticeManager
import com.m57.hermescontrol.data.update.isReleaseCandidateVersion
import com.m57.hermescontrol.data.update.releaseTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Drives the in-app self-update flow (issue #867): check the GitHub
 * releases API, download the release APK, and hand it to the system package
 * installer. The installer launch uses the application context
 * (FLAG_ACTIVITY_NEW_TASK), so no Activity is required.
 *
 * The ViewModel adopts the process-shared launch/foreground check result, while
 * manual checks, downloads, and system-installer handoff stay screen-owned.
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

    private var downloadJob: kotlinx.coroutines.Job? = null
    private var lastAvailable: AppUpdateState.UpdateAvailable? = null
    private var downloadGeneration = 0
    private var downloadPartialFile: File? = null
    private var installerReturnPending = false

    init {
        val cached = AppUpdateCache.state.value
        // An RC offer cached while the RC channel was on must not be adopted
        // after the user switched back to stable-only.
        val blocked =
            !_checkReleaseCandidateUpdates.value &&
                cached.releaseTag()?.let(::isReleaseCandidateVersion) == true
        if (blocked) AppUpdateCache.reset()
        viewModelScope.launch {
            AppUpdateCache.state.collect { sharedState ->
                if (_state.value !is AppUpdateState.Downloading &&
                    _state.value !is AppUpdateState.Installing &&
                    _state.value !is AppUpdateState.NeedsUnknownSourcesPermission
                ) {
                    _state.value = sharedState
                    if (sharedState is AppUpdateState.UpdateAvailable) lastAvailable = sharedState
                }
            }
        }
        if (cached is AppUpdateState.UpdateAvailable && !blocked) {
            lastAvailable = cached
            _state.value = cached
        } else if (cached is AppUpdateState.UpToDate && !blocked) {
            _state.value = cached
        } else {
            _state.value = cached
            UpdateNoticeManager.checkOnLaunch(checker, currentVersion, ioDispatcher)
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
        UpdateNoticeManager.channelChanged(enabled)
        _checkReleaseCandidateUpdates.value = enabled
        if (!enabled) {
            if (lastAvailable?.latestTag?.let(::isReleaseCandidateVersion) == true) cancelDownload()
            lastAvailable = null
            AppUpdateCache.reset()
        }
        runCheck()
    }

    private fun runCheck() {
        UpdateNoticeManager.checkNow(checker, currentVersion, ioDispatcher)
    }

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
        if (_state.value is AppUpdateState.Downloading || _state.value is AppUpdateState.Installing) return
        val available = updateAvailableForInstall() ?: return
        lastAvailable = available
        if (!canRequestInstalls()) {
            _state.value = AppUpdateState.NeedsUnknownSourcesPermission
            return
        }
        val dest = updateApkFile(available.latestTag)
        val generation = ++downloadGeneration
        val partial = File(dest.parentFile, "${dest.name}.${UUID.randomUUID()}.$generation.part")
        downloadPartialFile = partial
        try {
            partial.delete()
            _state.value = AppUpdateState.Downloading(0f)
            downloadJob?.cancel()
            downloadJob =
                viewModelScope.launch(ioDispatcher) {
                    try {
                        val downloaded =
                            checker.downloadApk(available.apkUrl, partial) { progress ->
                                if (generation ==
                                    downloadGeneration
                                ) {
                                    _state.value = AppUpdateState.Downloading(progress)
                                }
                            }
                        if (generation != downloadGeneration) return@launch
                        if (!downloaded || !partial.exists() || partial.length() == 0L) {
                            _state.value = AppUpdateState.Error(DOWNLOAD_ERROR)
                            return@launch
                        }
                        if ((dest.exists() && !dest.delete()) || !partial.renameTo(dest)) {
                            _state.value = AppUpdateState.Error(DOWNLOAD_ERROR)
                            return@launch
                        }
                        _state.value = AppUpdateState.Installing(available.latestTag)
                        launchInstaller(dest)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (generation == downloadGeneration) _state.value = AppUpdateState.Error(DOWNLOAD_ERROR)
                    } finally {
                        if (generation == downloadGeneration) {
                            partial.delete()
                            downloadPartialFile = null
                        }
                    }
                }
        } catch (_: Exception) {
            if (generation == downloadGeneration) {
                partial.delete()
                downloadPartialFile = null
                _state.value = AppUpdateState.Error(DOWNLOAD_ERROR)
            }
        }
    }

    /** Cancel in-flight download and return to UpdateAvailable. */
    fun cancelDownload() {
        downloadGeneration++
        downloadJob?.cancel()
        downloadJob = null
        downloadPartialFile?.delete()
        downloadPartialFile = null
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
        AppUpdateCache.hideDialog()
    }

    /** Resume install when returning from Unknown Sources permission screen. */
    fun resumeInstallAfterPermission() {
        if (canRequestInstalls()) {
            val available = updateAvailableForInstall()
            val dest = available?.let { updateApkFile(it.latestTag) }
            if (dest != null && dest.exists() && dest.length() > 0L) {
                _state.value = AppUpdateState.Installing(available.latestTag)
                launchInstaller(dest)
            } else {
                startUpdate()
            }
        }
    }

    /** Returning from the external installer is not proof that installation succeeded. */
    fun reconcileInstallerReturn() {
        if (_state.value !is AppUpdateState.Installing || !installerReturnPending) return
        installerReturnPending = false
        _state.value = lastAvailable ?: AppUpdateCache.state.value
    }

    private fun updateApkFile(tag: String): File {
        val safeTag = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(getApplication<Application>().cacheDir, "hermes-update-$safeTag.apk")
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
            installerReturnPending = true
        } catch (e: Exception) {
            installerReturnPending = false
            _state.value = AppUpdateState.Error(INSTALLER_ERROR)
        }
    }

    private companion object {
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
