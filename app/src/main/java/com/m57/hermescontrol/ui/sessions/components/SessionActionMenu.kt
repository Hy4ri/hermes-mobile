package com.m57.hermescontrol.ui.sessions.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors

/**
 * Per-session overflow/long-press menu.
 */
@Composable
fun SessionActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isDeleting: Boolean,
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    isArchived: Boolean = false,
    onToggleArchive: (() -> Unit)? = null,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val statusColors = LocalHermesStatusColors.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (onTogglePin != null) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (isPinned) {
                                R.string.sessions_action_unpin
                            } else {
                                R.string.sessions_action_pin
                            },
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                onClick = onTogglePin,
            )
        }
        if (onToggleArchive != null) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (isArchived) {
                                R.string.sessions_action_unarchive
                            } else {
                                R.string.sessions_action_archive
                            },
                        ),
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector =
                            if (isArchived) {
                                Icons.Filled.Unarchive
                            } else {
                                Icons.Filled.Archive
                            },
                        contentDescription = null,
                    )
                },
                onClick = onToggleArchive,
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sessions_action_select)) },
            leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
            onClick = onSelect,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sessions_action_rename)) },
            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            onClick = onRename,
        )
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = statusColors.error,
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = statusColors.error,
                )
            },
            enabled = !isDeleting,
            onClick = onDelete,
        )
    }
}
