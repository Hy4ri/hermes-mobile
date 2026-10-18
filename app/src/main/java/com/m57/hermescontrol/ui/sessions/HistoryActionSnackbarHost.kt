package com.m57.hermescontrol.ui.sessions

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.R

/** Replaces an older notice instead of queuing obsolete inverse actions. */
@Composable
internal fun HistoryActionSnackbarHost(
    feedback: HistoryActionFeedback?,
    onUndo: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = remember { SnackbarHostState() }
    val message = feedback?.let { stringResource(it.messageRes) }
    val undo = stringResource(R.string.sessions_undo_action)
    LaunchedEffect(feedback?.id) {
        if (feedback == null || message == null) return@LaunchedEffect
        val result =
            host.showSnackbar(
                message = message,
                actionLabel = if (feedback.canUndo) undo else null,
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )
        if (result == SnackbarResult.ActionPerformed) onUndo(feedback.id) else onDismiss(feedback.id)
    }
    SnackbarHost(hostState = host, modifier = modifier)
}
