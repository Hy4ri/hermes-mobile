package com.m57.hermescontrol.ui.chat.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.data.update.AppUpdateCache
import com.m57.hermescontrol.data.update.AppUpdateState
import com.m57.hermescontrol.data.update.UpdateNoticeManager
import com.m57.hermescontrol.ui.common.AppUpdateDialog
import com.m57.hermescontrol.ui.common.UpdateNoticeBanner
import com.m57.hermescontrol.ui.settings.AppUpdateViewModel

@Composable
fun ChatAppUpdateSection() {
    val updateNotice by AppUpdateCache.state.collectAsStateWithLifecycle()
    val appUpdateViewModel: AppUpdateViewModel =
        viewModel {
            val app =
                this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("Application not available")
            AppUpdateViewModel(app)
        }
    val appUpdateState by appUpdateViewModel.state.collectAsStateWithLifecycle()

    val updateLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(updateLifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    if (appUpdateState is AppUpdateState.NeedsUnknownSourcesPermission) {
                        appUpdateViewModel.resumeInstallAfterPermission()
                    }
                }
            }
        updateLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            updateLifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (UpdateNoticeManager.enabled && !AppUpdateCache.dismissed) {
        val noticeTag =
            (updateNotice as? AppUpdateState.UpdateAvailable)?.latestTag
                ?: UpdateNoticeManager.noticeTag()
        if (noticeTag != null) {
            UpdateNoticeBanner(
                latestTag = noticeTag,
                onUpdate = { AppUpdateCache.showDialog() },
                onDismiss = { AppUpdateCache.dismiss() },
            )
        }
    }

    if (AppUpdateCache.isDialogVisible) {
        val context = LocalContext.current
        val dialogState =
            if (appUpdateState !is AppUpdateState.Idle) {
                appUpdateState
            } else {
                updateNotice
            }
        AppUpdateDialog(
            state = dialogState,
            onDismiss = { AppUpdateCache.hideDialog() },
            onStartUpdate = { appUpdateViewModel.startUpdate() },
            onCancelDownload = { appUpdateViewModel.cancelDownload() },
            onNeverAskAgain = { appUpdateViewModel.dismissCurrentUpdate() },
            onOpenSettings = {
                launchUnknownAppSourcesSettings(context)
            },
        )
    }
}

private fun launchUnknownAppSourcesSettings(context: Context) {
    val intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        context.startActivity(
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
