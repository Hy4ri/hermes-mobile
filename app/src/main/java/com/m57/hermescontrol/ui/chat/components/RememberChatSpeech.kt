package com.m57.hermescontrol.ui.chat.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.session.ActiveSessionHolder

/**
 * Creates and remembers a screen/list-level [SpeechController] scoped to the active
 * chat session, server endpoint, profile, and auth state.
 *
 * Handles lifecycle events:
 * - Resets (stops playback and cleans cache) on [Lifecycle.Event.ON_STOP]
 * - Resets on composition disposal or when the server endpoint, profile, auth state, or session changes
 */
@Composable
fun rememberChatSpeech(
    synthesizer: SpeechSynthesizer? = null,
    player: SpeechPlayer? = null,
    onError: (() -> Unit)? = null,
): SpeechController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val dataScope by AuthManager.dataScopeFlow.collectAsStateWithLifecycle()
    val activeSessionId by ActiveSessionHolder.activeSessionId.collectAsStateWithLifecycle()

    val profile = dataScope?.activeProfileId
    val scopeKey = dataScope?.inMemoryKey(localKey = activeSessionId ?: "none").orEmpty()

    val resolvedSynthesizer =
        remember(synthesizer, dataScope) {
            synthesizer ?: AndroidSpeechSynthesizer(
                context = context,
                api = ApiClient.hermesApi,
                profile = profile,
            )
        }

    val resolvedPlayer =
        remember(player, context) {
            player ?: AndroidSpeechPlayer(context)
        }

    val controller =
        remember(scope, resolvedSynthesizer, resolvedPlayer) {
            SpeechController(
                scope = scope,
                synthesizer = resolvedSynthesizer,
                player = resolvedPlayer,
                onError = {
                    onError?.invoke() ?: Toast
                        .makeText(
                            context,
                            context.getString(R.string.tts_error_synthesis),
                            Toast.LENGTH_SHORT,
                        ).show()
                },
            )
        }

    // Reset when session scope changes (session/profile/server/auth switch)
    DisposableEffect(controller, scopeKey) {
        onDispose {
            controller.reset()
        }
    }

    // Reset on ON_STOP lifecycle event
    DisposableEffect(lifecycleOwner, controller) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    controller.reset()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.reset()
        }
    }

    return controller
}
