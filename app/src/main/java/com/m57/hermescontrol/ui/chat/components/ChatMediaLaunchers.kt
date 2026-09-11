package com.m57.hermescontrol.ui.chat.components

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.m57.hermescontrol.ExternalActivityLifecycleGuard
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.ui.chat.ChatInputPolicy
import com.m57.hermescontrol.ui.chat.SpeechInputHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatMediaLaunchers(
    val isListening: Boolean,
    val onMicTap: () -> Unit,
    val onCameraTap: () -> Unit,
    val onImageTap: () -> Unit,
    val onFileTap: () -> Unit,
)

@Composable
fun rememberChatMediaLaunchers(
    inputFieldValue: TextFieldValue,
    onInputFieldValueChange: (TextFieldValue) -> Unit,
    onAddAttachment: (uri: String, name: String, mimeType: String, size: Long) -> Unit,
    onAddAttachments: (List<Attachment>) -> Unit,
    onShowMessage: (String) -> Unit,
    launchExternalActivity: (() -> Unit) -> Unit,
    context: Context = LocalContext.current,
): ChatMediaLaunchers {
    var isListening by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val currentInputFieldValue by rememberUpdatedState(inputFieldValue)
    val currentOnInputFieldValueChange by rememberUpdatedState(onInputFieldValueChange)
    val currentOnAddAttachment by rememberUpdatedState(onAddAttachment)
    val currentOnAddAttachments by rememberUpdatedState(onAddAttachments)
    val currentOnShowMessage by rememberUpdatedState(onShowMessage)
    val currentLaunchExternalActivity by rememberUpdatedState(launchExternalActivity)

    val micListeningPrompt = stringResource(R.string.chat_mic_listening)
    val sttNotAvailableMsg = stringResource(R.string.stt_not_available)
    val sttPermissionDeniedMsg = stringResource(R.string.stt_permission_denied)
    val cameraErrorMsg = stringResource(R.string.chat_camera_error)

    // Speech-to-text recognition launcher (issue #194)
    val speechLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            isListening = false
            if (result.resultCode == Activity.RESULT_OK) {
                val spokenText =
                    result.data
                        ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull()
                        .orEmpty()
                if (spokenText.isNotBlank()) {
                    val currentText = currentInputFieldValue.text
                    val merged =
                        if (currentText.isBlank()) {
                            spokenText
                        } else {
                            "$currentText $spokenText"
                        }
                    currentOnInputFieldValueChange(ChatInputPolicy.commandFieldValue(merged))
                }
            }
        }

    // Mic permission launcher
    val micPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            if (granted) {
                if (SpeechInputHelper.isSpeechInputAvailable(context)) {
                    val intent = SpeechInputHelper.createSpeechIntent(micListeningPrompt)
                    isListening = true
                    currentLaunchExternalActivity {
                        try {
                            speechLauncher.launch(intent)
                        } catch (_: ActivityNotFoundException) {
                            isListening = false
                            currentOnShowMessage(sttNotAvailableMsg)
                        }
                    }
                } else {
                    currentOnShowMessage(sttNotAvailableMsg)
                }
            } else {
                currentOnShowMessage(sttPermissionDeniedMsg)
            }
        }

    // Multi-file picker for attachments (issue #195)
    val filePickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetMultipleContents(),
        ) { uris: List<Uri> ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            val attachments =
                uris.mapNotNull { uri ->
                    runCatching {
                        var name = uri.lastPathSegment ?: "file"
                        var size = 0L
                        context.contentResolver
                            .query(
                                uri,
                                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                                null,
                                null,
                                null,
                            )?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                                }
                            }
                        Attachment(
                            uri = uri.toString(),
                            name = name,
                            mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
                            size = size,
                        )
                    }.onFailure { error ->
                        Log.w("ChatScreen", "Skipping unreadable picked attachment", error)
                    }.getOrNull()
                }
            currentOnAddAttachments(attachments)
        }

    // Camera photo launcher (issue #195)
    val cameraLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { success ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            val uri = pendingCameraUri
            pendingCameraUri = null
            if (success && uri != null) {
                try {
                    val fileName =
                        "photo_${
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(
                                Date(),
                            )
                        }.jpg"
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val size = inputStream?.use { it.available().toLong() } ?: 0L
                    currentOnAddAttachment(uri.toString(), fileName, "image/jpeg", size)
                } catch (e: Exception) {
                    Log.e("ChatScreen", "Camera capture failed", e)
                    currentOnShowMessage(cameraErrorMsg)
                }
            }
        }

    val onMicTap: () -> Unit = {
        if (isListening) {
            isListening = false
        } else if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (SpeechInputHelper.isSpeechInputAvailable(context)) {
                val intent = SpeechInputHelper.createSpeechIntent(micListeningPrompt)
                isListening = true
                currentLaunchExternalActivity {
                    try {
                        speechLauncher.launch(intent)
                    } catch (_: ActivityNotFoundException) {
                        isListening = false
                        currentOnShowMessage(sttNotAvailableMsg)
                    }
                }
            } else {
                currentOnShowMessage(sttNotAvailableMsg)
            }
        } else {
            currentLaunchExternalActivity {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val onCameraTap: () -> Unit = {
        try {
            val timeStamp =
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val photoFile =
                File.createTempFile("camera_${timeStamp}_", ".jpg", context.cacheDir)
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    photoFile,
                )
            pendingCameraUri = uri
            currentLaunchExternalActivity {
                cameraLauncher.launch(uri)
            }
        } catch (e: Exception) {
            Log.e("ChatScreen", "Camera launch failed", e)
        }
    }

    val onImageTap: () -> Unit = {
        currentLaunchExternalActivity {
            filePickerLauncher.launch("image/*")
        }
    }

    val onFileTap: () -> Unit = {
        currentLaunchExternalActivity {
            filePickerLauncher.launch("*/*")
        }
    }

    return remember(isListening) {
        ChatMediaLaunchers(
            isListening = isListening,
            onMicTap = onMicTap,
            onCameraTap = onCameraTap,
            onImageTap = onImageTap,
            onFileTap = onFileTap,
        )
    }
}
