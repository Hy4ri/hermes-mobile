package com.m57.hermescontrol.data.update

import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Shared release check (issue #890): checks after launch and on foreground
 * return, with a short process-local debounce so the chat screen can show an
 * update banner without the user ever opening the About tab.
 *
 * The result lands in [AppUpdateCache] (consumed by the chat banner and
 * adopted by the About tab) and the latest tag is persisted via
 * [AuthManager.setLastKnownLatestTag] so a dismissed banner can return on a
 * later launch. Failed checks remain visible and can be retried from the About
 * tab or dialog without persisting a false successful-check marker.
 */
object UpdateNoticeManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val checkLock = Any()
    private var activeCheck: Job? = null
    private var lastSuccessfulCheckAt: Long? = null
    private var lastFailedCheckAt: Long? = null
    private var channelGeneration = 0

    /**
     * Release-only feature (issue #890): the launch check and chat banner are
     * disabled in debug builds (BuildConfig.DEBUG) so daily dev builds never
     * hit the GitHub API or nag about updates. Tests flip this to true.
     */
    var enabled: Boolean = !BuildConfig.DEBUG
        internal set

    /** Avoid duplicate foreground checks in one process; a cold launch always rechecks. */
    const val CHECK_INTERVAL_MS: Long = 5 * 60 * 1000L
    const val FAILURE_BACKOFF_MS: Long = 30 * 1000L

    /** Run after preferences are ready; failed checks never suppress the next foreground retry. */
    fun checkOnLaunch(
        checker: AppUpdateChecker = AppUpdateChecker(),
        currentVersion: String = BuildConfig.VERSION_NAME,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        now: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ) {
        if (!enabled && !force) return
        synchronized(checkLock) {
            if (activeCheck?.isActive == true) return
            if (!force && lastSuccessfulCheckAt?.let { now - it < CHECK_INTERVAL_MS } == true) return
            if (!force && lastFailedCheckAt?.let { now - it < FAILURE_BACKOFF_MS } == true) return
            val includeReleaseCandidates = AuthManager.isCheckingReleaseCandidateUpdates()
            val generation = channelGeneration
            val previousState = AppUpdateCache.state.value
            if (force ||
                previousState !is AppUpdateState.UpdateAvailable
            ) {
                AppUpdateCache.update(AppUpdateState.Checking)
            }
            activeCheck =
                scope.launch(ioDispatcher) {
                    val thisJob = currentCoroutineContext()[Job]
                    var successful = false
                    var failed = false
                    try {
                        val info = checker.fetchLatestRelease(includeReleaseCandidates)
                        val apk = info?.apkAsset
                        val state =
                            if (info == null) {
                                AppUpdateState.Error(NO_RELEASE_ERROR, isCheckError = true)
                            } else if (apk == null) {
                                AppUpdateState.Error(NO_APK_ERROR, isCheckError = true)
                            } else if (isNewerVersion(info.tagName, currentVersion)) {
                                AppUpdateState.UpdateAvailable(
                                    latestTag = info.tagName,
                                    apkUrl = apk.browserDownloadUrl,
                                    sizeBytes = apk.size,
                                    releaseNotes = info.body,
                                )
                            } else {
                                AppUpdateState.UpToDate(latestTag = info.tagName)
                            }
                        var resultRejected = false
                        synchronized(checkLock) {
                            if (activeCheck !== thisJob || generation != channelGeneration) return@synchronized
                            val currentChannel = AuthManager.isCheckingReleaseCandidateUpdates()
                            if (includeReleaseCandidates != currentChannel ||
                                (info != null && isReleaseCandidateVersion(info.tagName) && !currentChannel)
                            ) {
                                if (AppUpdateCache.state.value is AppUpdateState.Checking) {
                                    AppUpdateCache.update(previousState)
                                }
                                resultRejected = true
                                return@synchronized
                            }
                            AppUpdateCache.update(state)
                            if (state is AppUpdateState.UpdateAvailable || state is AppUpdateState.UpToDate) {
                                state.releaseTag()?.let { AuthManager.setLastKnownLatestTag(it) }
                                AuthManager.setUpdateCheckDoneForVersion(currentVersion)
                                AuthManager.setLastUpdateCheckTimestamp(System.currentTimeMillis())
                                successful = true
                            } else {
                                failed = true
                            }
                        }
                        if (resultRejected) return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        synchronized(checkLock) {
                            if (activeCheck === thisJob && generation == channelGeneration) {
                                failed = true
                                if (AppUpdateCache.state.value is AppUpdateState.Checking) {
                                    AppUpdateCache.update(AppUpdateState.Error(NETWORK_ERROR, isCheckError = true))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        synchronized(checkLock) {
                            if (activeCheck === thisJob && generation == channelGeneration) {
                                failed = true
                                if (AppUpdateCache.state.value is AppUpdateState.Checking) {
                                    AppUpdateCache.update(
                                        AppUpdateState.Error(GENERIC_CHECK_ERROR, isCheckError = true),
                                    )
                                }
                            }
                        }
                    } finally {
                        synchronized(checkLock) {
                            if (activeCheck === thisJob) {
                                if (successful) lastSuccessfulCheckAt = now
                                if (failed) lastFailedCheckAt = now
                                activeCheck = null
                            }
                        }
                    }
                }
        }
    }

    private const val NETWORK_ERROR = "Network error — check your connection"
    private const val GENERIC_CHECK_ERROR = "Couldn't check for updates"
    private const val NO_RELEASE_ERROR = "No release found yet"
    private const val NO_APK_ERROR = "Release has no APK asset"

    /** Explicit check action; shares in-flight work but bypasses the local debounce. */
    fun checkNow(
        checker: AppUpdateChecker,
        currentVersion: String,
        ioDispatcher: CoroutineDispatcher,
    ) {
        checkOnLaunch(checker, currentVersion, ioDispatcher, force = true)
    }

    /** Persist the channel choice and invalidate in-flight checks as one operation. */
    fun channelChanged(enabled: Boolean) {
        synchronized(checkLock) {
            AuthManager.setCheckReleaseCandidateUpdates(enabled)
            channelGeneration++
            if (AppUpdateCache.state.value is AppUpdateState.Checking) {
                AppUpdateCache.update(AppUpdateState.Idle)
            }
            activeCheck?.cancel()
            activeCheck = null
            lastSuccessfulCheckAt = null
            lastFailedCheckAt = null
        }
    }

    /** Test hook: reset process-local admission state between tests. */
    internal fun resetForTests() {
        synchronized(checkLock) {
            activeCheck?.cancel()
            activeCheck = null
            if (AppUpdateCache.state.value is AppUpdateState.Checking) {
                AppUpdateCache.update(AppUpdateState.Idle)
            }
            lastSuccessfulCheckAt = null
            lastFailedCheckAt = null
            channelGeneration++
        }
    }

    /**
     * The tag the chat banner should advertise, or null when nothing newer is
     * known, if the user explicitly dismissed this tag, or if it is a
     * release-candidate tag while the user is on the stable channel.
     */
    fun noticeTag(currentVersion: String = BuildConfig.VERSION_NAME): String? {
        if (!enabled) return null
        val dismissed = AuthManager.getDismissedUpdateTag()
        val candidateTag = (AppUpdateCache.state.value as? AppUpdateState.UpdateAvailable)?.latestTag ?: return null

        if (candidateTag == dismissed || AppUpdateCache.isDismissed(candidateTag)) return null
        if (isReleaseCandidateVersion(candidateTag) && !AuthManager.isCheckingReleaseCandidateUpdates()) {
            return null
        }
        return candidateTag.takeIf { isNewerVersion(it, currentVersion) }
    }
}
