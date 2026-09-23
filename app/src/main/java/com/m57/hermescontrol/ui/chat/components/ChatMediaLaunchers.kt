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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import com.m57.hermescontrol.ui.chat.VoiceNoteRecorder
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatMediaLaunchers(
    val isListening: Boolean,
    val isRecordingVoice: Boolean,
    val voiceNoteAmplitude: State<Float>,
    val onMicTap: () -> Unit,
    val onCameraTap: () -> Unit,
    val onImageTap: () -> Unit,
    val onFileTap: () -> Unit,
    val onMicHoldStart: () -> Unit = {},
    val onMicHoldEnd: () -> Unit = {},
    val onMicHoldCancel: () -> Unit = {},
)

@Composable
fun rememberChatMediaLaunchers(
    inputFieldValue: TextFieldValue,
    onInputFieldValueChange: (TextFieldValue) -> Unit,
    onAddAttachment: (uri: String, name: String, mimeType: String, size: Long) -> Unit,
    onAddAttachments: (List<Attachment>) -> Unit,
    onVoiceNoteRecorded: (file: File) -> Unit,
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
    val currentOnVoiceNoteRecorded by rememberUpdatedState(onVoiceNoteRecorded)
    val currentOnShowMessage by rememberUpdatedState(onShowMessage)
    val currentLaunchExternalActivity by rememberUpdatedState(launchExternalActivity)

    val micListeningPrompt = stringResource(R.string.chat_mic_listening)
    val sttNotAvailableMsg = stringResource(R.string.stt_not_available)
    val sttPermissionDeniedMsg = stringResource(R.string.stt_permission_denied)
    val cameraErrorMsg = stringResource(R.string.chat_camera_error)
    val voiceRecordFailedMsg = stringResource(R.string.chat_voice_record_failed)

    // Hold-to-record voice note recorder: the clip uploads to the dashboard
    // for server-side transcription, unlike the flat tap path below, which
    // still drives the on-device recognizer.
    val voiceNoteRecorder = remember { VoiceNoteRecorder(context) }
    var isRecordingVoice by remember { mutableStateOf(false) }

    // Live mic level for the recording panel — rises fast, decays slowly so
    // the meter reads as voice activity instead of flicker.
    val voiceNoteAmplitude = remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(90)
            val previous = voiceNoteAmplitude.value
            val next =
                if (voiceNoteRecorder.isActive) {
                    maxOf(voiceNoteRecorder.currentAmplitude() / 32767f, previous * 0.8f)
                } else {
                    0f
                }
            if (next != previous) {
                voiceNoteAmplitude.value = next
            }
        }
    }

    fun finishVoiceRecording() {
        val recordedFile = voiceNoteRecorder.stop()
        isRecordingVoice = false
        if (recordedFile != null) {
            currentOnVoiceNoteRecorded(recordedFile)
        }
    }

    DisposableEffect(voiceNoteRecorder) {
        voiceNoteRecorder.onMaxDurationReached = {
            if (voiceNoteRecorder.isActive) {
                finishVoiceRecording()
            }
        }
        onDispose {
            voiceNoteRecorder.onMaxDurationReached = null
            voiceNoteRecorder.cancel()
        }
    }

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

    // Hold-to-record permission: unlike the launcher above, granting does NOT
    // auto-start dictation — the user records by holding the mic again.
    val voiceNotePermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            if (!granted) {
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
        if (isRecordingVoice) {
            // A tap while recording discards the in-flight voice note.
            voiceNoteRecorder.cancel()
            isRecordingVoice = false
        } else if (isListening) {
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

    val onMicHoldStart: () -> Unit = {
        if (voiceNoteRecorder.isActive || isListening) {
            // A voice note or a dictation session is already running.
        } else if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (voiceNoteRecorder.start()) {
                isRecordingVoice = true
            } else {
                currentOnShowMessage(voiceRecordFailedMsg)
            }
        } else {
            currentLaunchExternalActivity {
                voiceNotePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            currentOnShowMessage(sttPermissionDeniedMsg)
        }
    }

    val onMicHoldEnd: () -> Unit = {
        if (voiceNoteRecorder.isActive) {
            finishVoiceRecording()
        }
    }

    val onMicHoldCancel: () -> Unit = {
        if (voiceNoteRecorder.isActive) {
            voiceNoteRecorder.cancel()
        }
        isRecordingVoice = false
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

    return remember(isListening, isRecordingVoice) {
        ChatMediaLaunchers(
            isListening = isListening || isRecordingVoice,
            isRecordingVoice = isRecordingVoice,
            voiceNoteAmplitude = voiceNoteAmplitude,
            onMicTap = onMicTap,
            onCameraTap = onCameraTap,
            onImageTap = onImageTap,
            onFileTap = onFileTap,
            onMicHoldStart = onMicHoldStart,
            onMicHoldEnd = onMicHoldEnd,
            onMicHoldCancel = onMicHoldCancel,
        )
    }
}
