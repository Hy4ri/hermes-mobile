package com.m57.hermescontrol.ui.chat.components

import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.ModalBottomSheet
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.config.resolveBaseUrl
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.LocalSpacing
import java.net.URI

/** Resolves the currently active saved connection profile, falling back to the first profile when unset. */
internal fun activeConnectionProfile(
    profiles: List<ConnectionProfile>,
    selectedProfileId: String?,
): ConnectionProfile? =
    profiles.firstOrNull { !selectedProfileId.isNullOrBlank() && it.id == selectedProfileId }
        ?: profiles.firstOrNull()

/** Returns the human-readable machine/connection name for the overflow menu and quick switcher. */
internal fun connectionDisplayName(
    profile: ConnectionProfile?,
    fallbackBaseUrl: String,
): String {
    val explicit = profile?.name?.trim().orEmpty()
    if (explicit.isNotEmpty()) return explicit
    val url = profile?.resolveBaseUrl(fallbackBaseUrl) ?: fallbackBaseUrl
    val host = runCatching { URI(url.trim()).host }.getOrNull()?.trim().orEmpty()
    if (host.isNotEmpty()) return host
    return url.trim()
}

/**
 * Quick connection profile switcher sheet opened from the chat overflow menu (issue #1886).
 *
 * Lists saved connection profiles with the active one marked and provides a direct jump
 * to full Connection Settings for adding, editing, or configuring servers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionQuickSwitcherSheet(
    profiles: List<ConnectionProfile>,
    selectedProfileId: String?,
    fallbackBaseUrl: String,
    onSelectProfile: (String) -> Unit,
    onOpenConnectionSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        ConnectionQuickSwitcherContent(
            profiles = profiles,
            selectedProfileId = selectedProfileId,
            fallbackBaseUrl = fallbackBaseUrl,
            onSelectProfile = onSelectProfile,
            onOpenConnectionSettings = onOpenConnectionSettings,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun ConnectionQuickSwitcherContent(
    profiles: List<ConnectionProfile>,
    selectedProfileId: String?,
    fallbackBaseUrl: String,
    onSelectProfile: (String) -> Unit,
    onOpenConnectionSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val activeProfile = activeConnectionProfile(profiles, selectedProfileId)
    val rowColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = spacing.lg)
                .testTag("connection_switcher_sheet"),
    ) {
        Text(
            text = stringResource(R.string.chat_connection_switcher_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
        )

        if (profiles.isNotEmpty()) {
            profiles.forEach { profile ->
                val isActive = profile.id == activeProfile?.id
                ConnectionProfileRow(
                    name = connectionDisplayName(profile, fallbackBaseUrl),
                    url = profile.resolveBaseUrl(fallbackBaseUrl),
                    isActive = isActive,
                    colors = rowColors,
                    modifier = Modifier.testTag("connection_switcher_item_" + profile.id),
                    onClick = {
                        if (isActive) {
                            onDismiss()
                        } else {
                            onSelectProfile(profile.id)
                        }
                    },
                )
            }
        } else {
            ConnectionProfileRow(
                name = connectionDisplayName(null, fallbackBaseUrl),
                url = fallbackBaseUrl,
                isActive = true,
                colors = rowColors,
                modifier = Modifier.testTag("connection_switcher_item_fallback"),
                onClick = onDismiss,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = spacing.xs))

        ListItem(
            headlineContent = {
                Text(
                    text = stringResource(R.string.chat_connection_switcher_open_settings),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = rowColors,
            modifier =
                Modifier
                    .clickable(onClick = onOpenConnectionSettings)
                    .testTag("connection_switcher_open_settings"),
        )
    }
}

@Composable
private fun ConnectionProfileRow(
    name: String,
    url: String,
    isActive: Boolean,
    colors: androidx.compose.material3.ListItemColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = name,
                style =
                    MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        textDirection = TextDirection.Content,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Filled.Dns,
                contentDescription = null,
                tint =
                    if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        },
        trailingContent = {
            if (isActive) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.chat_connection_active_desc),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        colors = colors,
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Narrow Arabic large text", widthDp = 320, fontScale = 1.5f, locale = "ar")
@Composable
private fun ConnectionQuickSwitcherPreview() {
    val rtl = LocalConfiguration.current.layoutDirection == View.LAYOUT_DIRECTION_RTL
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        HermesControlTheme {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                ConnectionQuickSwitcherContent(
                    profiles =
                        listOf(
                            ConnectionProfile(
                                id = "flip3",
                                name = "Flip3 Home",
                                baseUrl = "http://192.168.1.57:9119",
                            ),
                            ConnectionProfile(
                                id = "work",
                                name = "Workstation",
                                baseUrl = "https://hermes.tailnet.ts.net:9119",
                            ),
                        ),
                    selectedProfileId = "flip3",
                    fallbackBaseUrl = "http://192.168.1.57:9119",
                    onSelectProfile = {},
                    onOpenConnectionSettings = {},
                    onDismiss = {},
                )
            }
        }
    }
}
