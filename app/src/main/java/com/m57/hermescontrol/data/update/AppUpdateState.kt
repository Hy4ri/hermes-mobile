package com.m57.hermescontrol.data.update

/**
 * State of the in-app updater (issue #867) — shared by the About tab's
 * [com.m57.hermescontrol.ui.settings.AppUpdateViewModel] and the launch
 * notice (issue #890) via [AppUpdateCache].
 */
sealed interface AppUpdateState {
    /** Nothing shown yet (no check run in this session). */
    data object Idle : AppUpdateState

    /** A check is in flight. */
    data object Checking : AppUpdateState

    /** Latest release equals the installed version. */
    data class UpToDate(val latestTag: String) : AppUpdateState

    /** A newer release with an APK asset exists. */
    data class UpdateAvailable(
        val latestTag: String,
        val apkUrl: String,
        val sizeBytes: Long,
    ) : AppUpdateState

    /** APK download in progress; [progress] is 0..1. */
    data class Downloading(val progress: Float) : AppUpdateState

    /** Download finished and the system package installer was launched. */
    data class Installing(val latestTag: String) : AppUpdateState

    /** Install-from-unknown-sources not granted for this app yet. */
    data object NeedsUnknownSourcesPermission : AppUpdateState

    data class Error(val message: String) : AppUpdateState
}

/** The release tag a state carries, when it carries one. */
fun AppUpdateState.releaseTag(): String? =
    when (this) {
        is AppUpdateState.UpToDate -> latestTag
        is AppUpdateState.UpdateAvailable -> latestTag
        is AppUpdateState.Installing -> latestTag
        else -> null
    }
