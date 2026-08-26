package com.m57.hermescontrol.data.update

import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Startup update check (issue #890): one silent GitHub ping per installed
 * version, fired right after launch, so the chat screen can show an update
 * banner without the user ever opening the About tab.
 *
 * The result lands in [AppUpdateCache] (consumed by the chat banner and
 * adopted by the About tab) and the latest tag is persisted via
 * [AuthManager.setLastKnownLatestTag] so a dismissed banner can return on a
 * later launch. Failures leave the cache Idle — the About tab still has its
 * own manual retry path.
 */
object UpdateNoticeManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Release-only feature (issue #890): the launch check and chat banner are
     * disabled in debug builds (BuildConfig.DEBUG) so daily dev builds never
     * hit the GitHub API or nag about updates. Tests flip this to true.
     */
    var enabled: Boolean = !BuildConfig.DEBUG
        internal set

    /** Release check interval (24 hours). */
    const val CHECK_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

    /** Run from Application.onCreate, after AuthManager.init. Checks at most once every 24h. */
    fun checkOnLaunch(
        checker: AppUpdateChecker = AppUpdateChecker(),
        currentVersion: String = BuildConfig.VERSION_NAME,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!enabled) return
        val lastCheck = AuthManager.getLastUpdateCheckTimestamp()
        val versionChanged = AuthManager.getUpdateCheckDoneForVersion() != currentVersion
        if (!versionChanged && (now - lastCheck < CHECK_INTERVAL_MS)) return

        scope.launch(ioDispatcher) {
            AuthManager.setUpdateCheckDoneForVersion(currentVersion)
            AuthManager.setLastUpdateCheckTimestamp(now)
            val result =
                try {
                    checker.fetchLatestRelease()
                } catch (e: IOException) {
                    return@launch
                } catch (e: Exception) {
                    return@launch
                }
            val info = result ?: return@launch
            val apk = info.apkAsset ?: return@launch
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
            AppUpdateCache.update(state)
            state.releaseTag()?.let { AuthManager.setLastKnownLatestTag(it) }
        }
    }

    /**
     * The tag the chat banner should advertise, or null when nothing newer is
     * known or if the user explicitly dismissed this tag.
     */
    fun noticeTag(currentVersion: String = BuildConfig.VERSION_NAME): String? {
        if (!enabled) return null
        val dismissed = AuthManager.getDismissedUpdateTag()
        val candidateTag =
            (AppUpdateCache.state.value as? AppUpdateState.UpdateAvailable)?.latestTag
                ?: AuthManager.getLastKnownLatestTag()
                ?: return null

        if (candidateTag == dismissed) return null
        return candidateTag.takeIf { isNewerVersion(it, currentVersion) }
    }
}
