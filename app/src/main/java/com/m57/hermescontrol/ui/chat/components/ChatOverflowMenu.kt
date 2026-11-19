package com.m57.hermescontrol.ui.chat.components

import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.ChatBackgroundConnectionMenuItem
import com.m57.hermescontrol.ui.chat.ChatKeepScreenAwakeMenuItem

/**
 * Stateless body of the chat top-bar overflow menu (issue #1091, #1886).
 *
 * Every row uses a 24dp leading icon and aligned 24dp trailing slot so labels, icons,
 * chevrons, and checkboxes line up cleanly across sections.
 */
@Composable
internal fun ChatOverflowMenuContent(
    activeConnectionName: String?,
    profileLocked: Boolean,
    hasCurrentSession: Boolean,
    isSearchActive: Boolean,
    isHistoricalTimeline: Boolean,
    canArchive: Boolean,
    keepScreenAwake: Boolean,
    keepConnectedInBackground: Boolean,
    onOpenConnectionSwitcher: () -> Unit,
    onOpenTimeline: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenIntegrations: () -> Unit,
    onExportChat: () -> Unit,
    onArchiveChat: () -> Unit,
    onKeepScreenAwakeChange: (Boolean) -> Unit,
    onKeepConnectedInBackgroundChange: (Boolean) -> Unit,
) {
    if (!profileLocked && !activeConnectionName.isNullOrBlank()) {
        DropdownMenuItem(
            text = {
                Column {
                    Text(
                        text = activeConnectionName,
                        style =
                            MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium,
                                textDirection = TextDirection.Content,
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.chat_menu_switch_connection_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = onOpenConnectionSwitcher,
            modifier = Modifier.testTag("chat_menu_connection_switcher"),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    }

    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_action_timeline)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = null,
            )
        },
        enabled = hasCurrentSession,
        onClick = onOpenTimeline,
        modifier = Modifier.testTag("chat_menu_timeline"),
    )

    DropdownMenuItem(
        text = {
            Text(
                stringResource(
                    if (isSearchActive) {
                        R.string.chat_action_close_search
                    } else {
                        R.string.chat_action_search
                    },
                ),
            )
        },
        leadingIcon = {
            Icon(
                imageVector = if (isSearchActive) Icons.Filled.Close else Icons.Filled.Search,
                contentDescription = null,
            )
        },
        enabled = !isHistoricalTimeline,
        onClick = onToggleSearch,
        modifier = Modifier.testTag("chat_menu_search"),
    )

    // #1643: integrations manage credentials; hidden while a profile lock is on.
    if (!profileLocked) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.session_integrations_menu_item)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Extension,
                    contentDescription = null,
                )
            },
            onClick = onOpenIntegrations,
            modifier = Modifier.testTag("chat_menu_session_integrations"),
        )
    }

    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_action_export)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = null,
            )
        },
        enabled = hasCurrentSession,
        onClick = onExportChat,
        modifier = Modifier.testTag("chat_menu_export_chat"),
    )

    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_action_archive)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Archive,
                contentDescription = null,
            )
        },
        enabled = canArchive,
        onClick = onArchiveChat,
        modifier = Modifier.testTag("chat_menu_archive"),
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    ChatKeepScreenAwakeMenuItem(
        checked = keepScreenAwake,
        onToggle = onKeepScreenAwakeChange,
    )
    ChatBackgroundConnectionMenuItem(
        checked = keepConnectedInBackground,
        onToggle = onKeepConnectedInBackgroundChange,
    )
}

@Preview(name = "Light", widthDp = 300)
@Preview(name = "Dark", widthDp = 300, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Narrow Arabic large text", widthDp = 320, fontScale = 1.5f, locale = "ar")
@Composable
private fun ChatOverflowMenuPreview() {
    val rtl = LocalConfiguration.current.layoutDirection == View.LAYOUT_DIRECTION_RTL
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        HermesControlTheme {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.extraSmall,
                tonalElevation = 3.dp,
            ) {
                Column(modifier = Modifier.widthIn(min = 240.dp, max = 300.dp).padding(vertical = 4.dp)) {
                    ChatOverflowMenuContent(
                        activeConnectionName = "Flip3 Home",
                        profileLocked = false,
                        hasCurrentSession = true,
                        isSearchActive = false,
                        isHistoricalTimeline = false,
                        canArchive = true,
                        keepScreenAwake = true,
                        keepConnectedInBackground = false,
                        onOpenConnectionSwitcher = {},
                        onOpenTimeline = {},
                        onToggleSearch = {},
                        onOpenIntegrations = {},
                        onExportChat = {},
                        onArchiveChat = {},
                        onKeepScreenAwakeChange = {},
                        onKeepConnectedInBackgroundChange = {},
                    )
                }
            }
        }
    }
}
