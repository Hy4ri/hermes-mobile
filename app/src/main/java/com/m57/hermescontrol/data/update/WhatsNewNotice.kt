package com.m57.hermescontrol.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import kotlinx.coroutines.CancellationException

/**
 * One-shot "What's new" dialog for the first launch after an app update.
 * Fresh installs are recorded silently and never see it.
 */
object WhatsNewNotice {
    var release by mutableStateOf<UpdateInfo?>(null)
        private set

    suspend fun checkOnLaunch(
        context: Context,
        checker: AppUpdateChecker = AppUpdateChecker(),
        currentVersion: String = BuildConfig.VERSION_NAME,
    ) {
        if (!UpdateNoticeManager.enabled || release != null) return
        val lastSeen = AuthManager.getLastSeenVersion()
        if (lastSeen == currentVersion) return
        if (lastSeen == null && isFreshInstall(context)) {
            AuthManager.setLastSeenVersion(currentVersion)
            return
        }
        val info =
            try {
                checker.fetchReleaseByTag("v$currentVersion")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return // Network failure: retry on the next launch.
            }
        if (info == null || info.body.isBlank()) {
            AuthManager.setLastSeenVersion(currentVersion)
            return
        }
        release = info
    }

    fun dismiss(currentVersion: String = BuildConfig.VERSION_NAME) {
        AuthManager.setLastSeenVersion(currentVersion)
        release = null
    }

    private fun isFreshInstall(context: Context): Boolean =
        runCatching {
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
            info.firstInstallTime == info.lastUpdateTime
        }.getOrDefault(true)
}
