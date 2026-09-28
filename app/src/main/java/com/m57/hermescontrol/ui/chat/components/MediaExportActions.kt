package com.m57.hermescontrol.ui.chat.components

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.ExternalActivityLifecycleGuard
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.notification.NotificationHelper
import com.m57.hermescontrol.ui.chat.MediaExportHelper
import com.m57.hermescontrol.ui.chat.MediaSaveStrategy
import com.m57.hermescontrol.ui.chat.acceptedSaveDestination
import com.m57.hermescontrol.ui.chat.mediaSaveStrategy
import com.m57.hermescontrol.ui.chat.normalizeFrameworkMime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Save/Share state for the media viewer; [isBusy] is true while either is in flight. */
internal class MediaExportActions(
    val isBusy: Boolean,
    val onSave: () -> Unit,
    val onShare: () -> Unit,
)

/**
 * The single Save/Share pipeline for every media kind (issue #1328). Streams
 * through [MediaExportHelper]: MediaStore Downloads on API 29+, a SAF
 * create-document picker on API 26-28, and a FileProvider share cache.
 */
@Composable
internal fun rememberMediaExportActions(
    mediaUri: String,
    mimeType: String?,
    displayName: String,
): MediaExportActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isBusy by remember { mutableStateOf(false) }
    val saveFailed = stringResource(R.string.media_player_save_failed)
    val shareFailed = stringResource(R.string.media_player_share_failed)
    val shareTitle = stringResource(R.string.media_player_share_title)

    val launchExternalActivity: (() -> Unit) -> Unit = { launch ->
        ExternalActivityLifecycleGuard.launchExternalActivity(
            acquireConnectionLease = HermesWsClient::acquireExternalActivityConnectionLease,
            releaseConnectionLease = HermesWsClient::releaseExternalActivityConnectionLease,
            prepareForBackground = { NotificationHelper.start(context) },
            cleanupAfterLaunchFailure = { NotificationHelper.stop(context) },
            launch = launch,
        )
    }

    val saveDocumentLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            try {
                ExternalActivityLifecycleGuard.externalActivityReturned()
                val destination = acceptedSaveDestination(result.resultCode, result.data?.data)
                if (destination != null) {
                    scope.launch {
                        try {
                            val msg =
                                MediaExportHelper.saveMediaToUri(
                                    context = context,
                                    sourceUri = mediaUri,
                                    targetUri = destination,
                                    fallbackMime =
                                        mimeType?.takeUnless { it == "application/octet-stream" }
                                            ?: com.m57.hermescontrol.ui.chat
                                                .mediaMimeForPath(displayName),
                                )
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            isBusy = false
                        }
                    }
                } else {
                    isBusy = false
                }
            } catch (e: Throwable) {
                isBusy = false
                if (e is CancellationException) throw e
            }
        }

    val onSave: () -> Unit = {
        if (!isBusy) {
            isBusy = true
            when (mediaSaveStrategy()) {
                MediaSaveStrategy.MEDIA_STORE -> {
                    scope.launch {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val msg =
                                    MediaExportHelper.saveMediaToDownloads(
                                        context = context,
                                        uri = mediaUri,
                                        fallbackMime =
                                            mimeType?.takeUnless { it == "application/octet-stream" }
                                                ?: com.m57.hermescontrol.ui.chat
                                                    .mediaMimeForPath(displayName),
                                        displayName = displayName,
                                    )
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            isBusy = false
                        }
                    }
                }

                MediaSaveStrategy.CREATE_DOCUMENT -> {
                    val suggestedName =
                        MediaExportHelper.resolveDisplayName(
                            displayName = displayName,
                            mimeType = mimeType,
                            uri = mediaUri,
                        )
                    val rawMime =
                        mimeType?.takeUnless { it == "application/octet-stream" }
                            ?: com.m57.hermescontrol.ui.chat
                                .mediaMimeForPath(displayName)
                    val normalizedMime = normalizeFrameworkMime(rawMime)
                    val intent =
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = normalizedMime
                            putExtra(Intent.EXTRA_TITLE, suggestedName)
                        }
                    try {
                        launchExternalActivity {
                            saveDocumentLauncher.launch(intent)
                        }
                    } catch (_: Exception) {
                        isBusy = false
                        scope.launch(Dispatchers.Main) {
                            Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    val onShare: () -> Unit = {
        if (!isBusy) {
            isBusy = true
            scope.launch {
                try {
                    val intent =
                        MediaExportHelper.shareMedia(
                            context = context,
                            uri = mediaUri,
                            fallbackMime =
                                mimeType?.takeUnless { it == "application/octet-stream" }
                                    ?: com.m57.hermescontrol.ui.chat
                                        .mediaMimeForPath(displayName),
                            displayName = displayName,
                        )
                    withContext(Dispatchers.Main) {
                        if (intent != null) {
                            val chooser = Intent.createChooser(intent, shareTitle)
                            try {
                                launchExternalActivity {
                                    context.startActivity(chooser)
                                }
                            } catch (_: Exception) {
                                Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    isBusy = false
                }
            }
        }
    }

    return MediaExportActions(isBusy = isBusy, onSave = onSave, onShare = onShare)
}
