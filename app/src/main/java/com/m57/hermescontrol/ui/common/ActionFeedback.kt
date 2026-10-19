package com.m57.hermescontrol.ui.common

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.R
import kotlinx.coroutines.launch

/**
 * Snackbar host owned by the enclosing [HermesScaffold]. It lives and dies with
 * the screen, so queued feedback cannot outlive it or reach another screen.
 * Null outside a scaffold; [ActionFeedbackEffect] then falls back to a toast.
 */
internal val LocalActionFeedbackHost = compositionLocalOf<SnackbarHostState?> { null }

/** Host for [LocalActionFeedbackHost]; failures use the error container colours. */
@Composable
internal fun ActionFeedbackSnackbarHost(hostState: SnackbarHostState) {
    SnackbarHost(hostState, modifier = Modifier.imePadding()) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            actionContentColor = MaterialTheme.colorScheme.onErrorContainer,
            dismissActionContentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * Shows a failure message in the screen snackbar with a Copy action.
 *
 * Copy puts the full [message] on the clipboard, so wrapped text is not
 * truncated. The message is cleared at once through [onConsumed]; the snackbar
 * runs in its own scope so that clearing cannot cancel it, and the scope ends
 * with the screen.
 */
@Composable
fun ActionFeedbackEffect(
    message: String?,
    onConsumed: () -> Unit,
) {
    val host = LocalActionFeedbackHost.current
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val copyLabel = stringResource(R.string.action_copy_error)
    val scope = rememberCoroutineScope()
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        onConsumed()
        if (host == null) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }
        scope.launch {
            val result =
                host.showSnackbar(
                    message = text,
                    actionLabel = copyLabel,
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
            if (result == SnackbarResult.ActionPerformed) {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
            }
        }
    }
}
