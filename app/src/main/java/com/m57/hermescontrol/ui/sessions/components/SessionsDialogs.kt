package com.m57.hermescontrol.ui.sessions.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.ConfirmDialog
import com.m57.hermescontrol.ui.sessions.SessionsUiState
import com.m57.hermescontrol.ui.sessions.cleanSearchSnippet

@Composable
fun SessionsDialogs(
    state: SessionsUiState,
    pruneDays: String,
    onPruneDaysChange: (String) -> Unit,
    statusColors: HermesStatusColors,
    onConfirmEmptyCleanup: () -> Unit,
    onDismissEmptyCleanup: () -> Unit,
    onConfirmPrune: (Int) -> Unit,
    onDismissPrune: () -> Unit,
    onConfirmDeleteSession: () -> Unit,
    onDismissDeleteSession: () -> Unit,
    onConfirmBulkDelete: () -> Unit,
    onDismissBulkDelete: () -> Unit,
    onRenameSession: (String, String) -> Unit,
    onRenameDraftChange: (String) -> Unit,
    onDismissRename: () -> Unit,
) {
    val spacing = LocalSpacing.current

    // Empty-session cleanup dialog
    if (state.showEmptyCleanupDialog) {
        ConfirmDialog(
            title = stringResource(R.string.sessions_empty_cleanup_title),
            message = stringResource(R.string.sessions_empty_cleanup_message, state.emptyCount),
            onConfirm = onConfirmEmptyCleanup,
            onDismiss = onDismissEmptyCleanup,
            confirmText = stringResource(R.string.sessions_empty_cleanup_confirm),
        )
    }

    // Prune dialog
    if (state.showPruneDialog) {
        ConfirmDialog(
            title = stringResource(R.string.sessions_prune_title),
            message = stringResource(R.string.sessions_prune_desc),
            onConfirm = {
                val days = pruneDays.toIntOrNull()
                if (days != null && days > 0) onConfirmPrune(days)
            },
            onDismiss = onDismissPrune,
            confirmText = stringResource(R.string.sessions_prune_confirm),
            isDestructive = true,
            extraContent = {
                Spacer(modifier = Modifier.height(spacing.sm))
                OutlinedTextField(
                    value = pruneDays,
                    onValueChange = onPruneDaysChange,
                    label = { Text(stringResource(R.string.sessions_prune_days_label)) },
                    placeholder = { Text("7") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                val days = pruneDays.toIntOrNull()
                                if (days != null && days > 0) onConfirmPrune(days)
                            },
                        ),
                )
            },
        )
    }

    // Single-session delete confirmation dialog
    if (state.sessionToDeleteConfirm != null) {
        val sessionToDelete = state.sessionToDeleteConfirm
        val sessionTitle =
            state.sessions
                .find { it.id == sessionToDelete }
                ?.title
                ?.takeIf { it.isNotBlank() }
                ?: state.searchResults
                    .find { it.session_id == sessionToDelete }
                    ?.snippet
                    ?.let(::cleanSearchSnippet)
                    ?.take(80)
                ?: stringResource(R.string.history_untitled)

        ConfirmDialog(
            title = stringResource(R.string.sessions_delete_title),
            message = stringResource(R.string.sessions_delete_message, sessionTitle),
            onConfirm = onConfirmDeleteSession,
            onDismiss = onDismissDeleteSession,
            confirmText = stringResource(R.string.action_delete),
            isDestructive = true,
        )
    }

    // Bulk delete confirmation dialog
    if (state.showBulkDeleteConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.sessions_bulk_delete_title),
            message = stringResource(R.string.sessions_bulk_delete_message, state.selectedIds.size),
            onConfirm = onConfirmBulkDelete,
            onDismiss = onDismissBulkDelete,
            confirmText = stringResource(R.string.action_delete),
            isDestructive = true,
        )
    }

    // Single-session rename dialog
    state.renamingSessionId?.let { sessionId ->
        AlertDialog(
            onDismissRequest = onDismissRename,
            title = { Text(stringResource(R.string.sessions_rename_title)) },
            text = {
                OutlinedTextField(
                    value = state.renameDraft,
                    onValueChange = onRenameDraftChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.sessions_rename_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onRenameSession(sessionId, state.renameDraft) },
                    enabled = state.renameDraft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.sessions_action_rename))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRename) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
