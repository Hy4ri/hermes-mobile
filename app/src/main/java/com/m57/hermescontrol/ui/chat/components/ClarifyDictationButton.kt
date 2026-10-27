package com.m57.hermescontrol.ui.chat.components

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.m57.hermescontrol.ExternalActivityLifecycleGuard
import com.m57.hermescontrol.R
import com.m57.hermescontrol.launchExternalActivityWithLease
import com.m57.hermescontrol.ui.chat.SpeechInputHelper

/**
 * Flat mic icon for a clarify text field: dictates with the same on-device
 * recognizer as the composer mic, but writes the result into [onText]'s field
 * instead of the chat composer. Renders nothing when no recognizer exists.
 */
@Composable
internal fun ClarifyDictationButton(
    onShowMessage: (String) -> Unit,
    onText: (String) -> Unit,
) {
    val context = LocalContext.current
    val available = remember(context) { SpeechInputHelper.isSpeechInputAvailable(context) }
    if (!available) return

    val currentOnText by rememberUpdatedState(onText)
    val currentOnShowMessage by rememberUpdatedState(onShowMessage)
    val listeningPrompt = stringResource(R.string.chat_mic_listening)
    val notAvailableMsg = stringResource(R.string.stt_not_available)
    val permissionDeniedMsg = stringResource(R.string.stt_permission_denied)

    val speechLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            if (result.resultCode == Activity.RESULT_OK) {
                val spoken =
                    result.data
                        ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull()
                        .orEmpty()
                if (spoken.isNotBlank()) currentOnText(spoken)
            }
        }

    fun startDictation() {
        launchExternalActivityWithLease(context) {
            try {
                speechLauncher.launch(SpeechInputHelper.createSpeechIntent(listeningPrompt))
            } catch (_: ActivityNotFoundException) {
                ExternalActivityLifecycleGuard.externalActivityReturned()
                currentOnShowMessage(notAvailableMsg)
            }
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            if (granted) {
                startDictation()
            } else {
                currentOnShowMessage(permissionDeniedMsg)
            }
        }

    IconButton(
        onClick = {
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            if (granted) {
                startDictation()
            } else {
                launchExternalActivityWithLease(context) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        },
        modifier = Modifier.testTag("clarify_dictate_button"),
    ) {
        Icon(
            imageVector = Icons.Outlined.Mic,
            contentDescription = stringResource(R.string.clarify_dictate),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Appends dictated text to whatever the user already typed, separated by a space. */
internal fun appendDictation(
    current: String,
    spoken: String,
): String {
    val text = spoken.trim()
    if (text.isEmpty()) return current
    return if (current.isBlank()) text else current.trimEnd() + " " + text
}

/** Clarify answers are keyed by question id; dictation appends to that question's text. */
internal fun Map<String, String>.withDictation(
    qid: String,
    spoken: String,
): Map<String, String> = this + (qid to appendDictation(this[qid].orEmpty(), spoken))
