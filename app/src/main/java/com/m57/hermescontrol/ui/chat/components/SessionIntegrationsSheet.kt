package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ConnectorError
import com.m57.hermescontrol.data.model.ConnectorStatus
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.ChatConnectorsUiState
import com.m57.hermescontrol.ui.chat.ConnectorActionState
import com.m57.hermescontrol.ui.chat.ConnectorUiItem
import com.m57.hermescontrol.ui.chat.ConnectorsLoadPhase

/**
 * Visual elements for a connector status badge.
 */
data class ConnectorStatusVisuals(
    val icon: ImageVector,
    val color: Color,
    val label: String,
)

@Composable
fun resolveConnectorStatusVisuals(
    status: ConnectorStatus,
    isConnected: Boolean,
): ConnectorStatusVisuals {
    val statusColors = LocalHermesStatusColors.current
    return when {
        isConnected || status == ConnectorStatus.CONNECTED -> {
            ConnectorStatusVisuals(
                icon = Icons.Filled.CheckCircle,
                color = statusColors.success,
                label = stringResource(R.string.session_integrations_status_connected),
            )
        }

        status == ConnectorStatus.INITIATED -> {
            ConnectorStatusVisuals(
                icon = Icons.Filled.HourglassTop,
                color = statusColors.info,
                label = stringResource(R.string.session_integrations_awaiting),
            )
        }

        status == ConnectorStatus.EXPIRED -> {
            ConnectorStatusVisuals(
                icon = Icons.Filled.Warning,
                color = statusColors.warning,
                label = stringResource(R.string.session_integrations_status_expired),
            )
        }

        status == ConnectorStatus.REVOKED -> {
            ConnectorStatusVisuals(
                icon = Icons.Filled.Warning,
                color = statusColors.warning,
                label = stringResource(R.string.session_integrations_status_revoked),
            )
        }

        status == ConnectorStatus.FAILED -> {
            ConnectorStatusVisuals(
                icon = Icons.Filled.ErrorOutline,
                color = statusColors.error,
                label = stringResource(R.string.session_integrations_status_failed),
            )
        }

        status == ConnectorStatus.DISCONNECTED -> {
            ConnectorStatusVisuals(
                icon = Icons.Filled.LinkOff,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                label = stringResource(R.string.session_integrations_status_disconnected),
            )
        }

        else -> {
            ConnectorStatusVisuals(
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                label = stringResource(R.string.session_integrations_status_unknown),
            )
        }
    }
}

@Composable
fun mapLoadPhaseErrorMessage(loadPhase: ConnectorsLoadPhase): String? =
    when (loadPhase) {
        is ConnectorsLoadPhase.Error -> {
            mapConnectorErrorMessage(loadPhase.error)
        }

        is ConnectorsLoadPhase.Unavailable -> {
            stringResource(R.string.session_integrations_err_unavailable)
        }

        is ConnectorsLoadPhase.UnsupportedRuntime -> {
            stringResource(
                R.string.session_integrations_err_unsupported_runtime,
            )
        }

        is ConnectorsLoadPhase.UnsupportedBackend -> {
            stringResource(
                R.string.session_integrations_err_unsupported_backend,
            )
        }

        is ConnectorsLoadPhase.NotOwner -> {
            stringResource(R.string.session_integrations_err_not_owner)
        }

        else -> {
            null
        }
    }

@Composable
fun mapConnectorErrorMessage(error: ConnectorError): String =
    when (error) {
        is ConnectorError.InvalidParams -> {
            stringResource(R.string.session_integrations_err_invalid_params)
        }

        is ConnectorError.NotOwner -> {
            stringResource(R.string.session_integrations_err_not_owner)
        }

        is ConnectorError.Unavailable -> {
            stringResource(R.string.session_integrations_err_unavailable)
        }

        is ConnectorError.UnsupportedRuntime -> {
            stringResource(R.string.session_integrations_err_unsupported_runtime)
        }

        is ConnectorError.UnsupportedBackend -> {
            stringResource(R.string.session_integrations_err_unsupported_backend)
        }

        is ConnectorError.RequestFailed -> {
            stringResource(R.string.session_integrations_err_request_failed)
        }

        is ConnectorError.InvalidResponse, is ConnectorError.MalformedEnvelope -> {
            stringResource(R.string.session_integrations_err_invalid_response)
        }

        is ConnectorError.NetworkError -> {
            stringResource(R.string.session_integrations_err_network)
        }

        is ConnectorError.Other -> {
            stringResource(R.string.session_integrations_err_generic)
        }
    }

@Composable
fun mapActionErrorMessage(rawError: String?): String =
    when {
        rawError.isNullOrBlank() -> {
            stringResource(R.string.session_integrations_err_generic)
        }

        rawError.contains("not owned", ignoreCase = true) -> {
            stringResource(R.string.session_integrations_err_not_owner)
        }

        rawError.contains(
            "No active session",
            ignoreCase = true,
        ) -> {
            stringResource(R.string.session_integrations_no_session)
        }

        rawError.contains("Invalid connector slug", ignoreCase = true) ||
            rawError.contains("Invalid params", ignoreCase = true) -> {
            stringResource(R.string.session_integrations_err_invalid_params)
        }

        rawError.contains("authorization URL", ignoreCase = true) ||
            rawError.contains("unexpected status", ignoreCase = true) -> {
            stringResource(R.string.session_integrations_err_invalid_response)
        }

        rawError.contains("authorization failed", ignoreCase = true) -> {
            stringResource(R.string.session_integrations_err_request_failed)
        }

        rawError.contains("browser", ignoreCase = true) -> {
            stringResource(R.string.session_integrations_err_browser_launch)
        }

        else -> {
            stringResource(R.string.session_integrations_err_generic)
        }
    }

/**
 * Native bottom sheet for session integrations / connectors (issue #1091).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionIntegrationsSheet(
    uiState: ChatConnectorsUiState,
    sessionId: String? = uiState.sessionId,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onConnect: (slug: String, reconnect: Boolean) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    if (!uiState.isVisible) return

    val effectiveSessionId = sessionId ?: uiState.sessionId
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.testTag("session_integrations_sheet"),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        SessionIntegrationsContent(
            uiState = uiState,
            sessionId = effectiveSessionId,
            onDismiss = onDismiss,
            onRefresh = onRefresh,
            onConnect = onConnect,
            onClearError = onClearError,
        )
    }
}

/**
 * Stateless content composable for testing and decoupled layout.
 */
@Composable
fun SessionIntegrationsContent(
    uiState: ChatConnectorsUiState,
    sessionId: String? = uiState.sessionId,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onConnect: (slug: String, reconnect: Boolean) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColors = LocalHermesStatusColors.current
    val effectiveSessionId = sessionId ?: uiState.sessionId
    val isSessionBlank = effectiveSessionId.isNullOrBlank()

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
    ) {
        // Top Header
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.session_integrations_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("session_integrations_title"),
                )
                Spacer(modifier = Modifier.height(2.dp))
                val subtitleText =
                    if (!isSessionBlank) {
                        val displayId =
                            if (effectiveSessionId.length >
                                12
                            ) {
                                "${effectiveSessionId.take(12)}…"
                            } else {
                                effectiveSessionId
                            }
                        stringResource(R.string.session_integrations_subtitle, displayId)
                    } else {
                        stringResource(R.string.session_integrations_no_session)
                    }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("session_integrations_subtitle"),
                )
            }

            IconButton(
                onClick = onRefresh,
                enabled = !uiState.isLoading && !isSessionBlank,
                modifier = Modifier.testTag("session_integrations_refresh_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.session_integrations_refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("session_integrations_close_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.session_integrations_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Action Error Banner
        if (uiState.errorMessage != null) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("session_integrations_action_error_banner"),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = mapActionErrorMessage(uiState.errorMessage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onClearError,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.session_integrations_close),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }

        // Awaiting Authorization Banner (manual Refresh status affordance)
        val awaitingSlug =
            (uiState.actionState as? ConnectorActionState.AwaitingAuthorization)?.slug
        if (awaitingSlug != null) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("session_integrations_awaiting_banner"),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.OpenInBrowser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.session_integrations_awaiting),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.session_integrations_awaiting_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = onRefresh,
                        modifier =
                            Modifier
                                .align(Alignment.End)
                                .testTag("session_integrations_refresh_status_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.session_integrations_refresh_status),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        // Content States
        when {
            isSessionBlank || (uiState.loadPhase is ConnectorsLoadPhase.Initial && uiState.items.isEmpty()) -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp)
                            .testTag("session_integrations_no_session_state"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LinkOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.session_integrations_no_session),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            uiState.loadPhase is ConnectorsLoadPhase.Loading -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp)
                            .testTag("session_integrations_loading"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.session_integrations_connecting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            uiState.loadPhase !is ConnectorsLoadPhase.Loading &&
                uiState.loadPhase !is ConnectorsLoadPhase.Refreshing &&
                uiState.loadPhase !is ConnectorsLoadPhase.Loaded &&
                uiState.items.isEmpty() -> {
                // Non-loaded error / unavailable / unsupported / not owner terminal states
                val errorMessage = mapLoadPhaseErrorMessage(uiState.loadPhase) ?: ""
                val canRetry = (uiState.loadPhase as? ConnectorsLoadPhase.Error)?.canRetry ?: false

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp)
                            .testTag("session_integrations_error"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = statusColors.warning,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("session_integrations_error_text"),
                        )
                        if (canRetry) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = onRefresh,
                                modifier = Modifier.testTag("session_integrations_retry_button"),
                            ) {
                                Text(stringResource(R.string.session_integrations_retry))
                            }
                        }
                    }
                }
            }

            uiState.items.isEmpty() -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp)
                            .testTag("session_integrations_empty"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.session_integrations_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> {
                // List of items
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("session_integrations_list"),
                ) {
                    if (uiState.loadPhase is ConnectorsLoadPhase.Refreshing) {
                        item {
                            LinearProgressIndicator(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag("session_integrations_refreshing_indicator"),
                            )
                        }
                    }

                    items(
                        items = uiState.items,
                        key = { it.slug },
                    ) { item ->
                        ConnectorRow(
                            item = item,
                            actionState = uiState.actionState,
                            isLoading = uiState.isLoading,
                            onConnect = onConnect,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectorRow(
    item: ConnectorUiItem,
    actionState: ConnectorActionState,
    isLoading: Boolean,
    onConnect: (slug: String, reconnect: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visuals = resolveConnectorStatusVisuals(item.status, item.isConnected)
    val isCurrentConnecting =
        actionState is ConnectorActionState.Connecting && actionState.slug == item.slug
    val isAnyActionPending =
        actionState is ConnectorActionState.Connecting || actionState is ConnectorActionState.AwaitingAuthorization
    val needsReconnect =
        item.isConnected ||
            item.status == ConnectorStatus.EXPIRED ||
            item.status == ConnectorStatus.REVOKED

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("session_integrations_item_${item.slug}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Accessible Status Indicator Icon
        Box(
            modifier =
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(visuals.color.copy(alpha = 0.15f))
                    .testTag("session_integrations_status_${item.slug}"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = visuals.icon,
                contentDescription = visuals.label,
                tint = visuals.color,
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = visuals.label,
                style = MaterialTheme.typography.bodySmall,
                color = visuals.color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        if (isCurrentConnecting) {
            CircularProgressIndicator(
                modifier =
                    Modifier
                        .size(20.dp)
                        .testTag("session_integrations_spinner_${item.slug}"),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            val buttonText =
                if (needsReconnect) {
                    stringResource(R.string.session_integrations_reconnect)
                } else {
                    stringResource(R.string.session_integrations_connect)
                }

            OutlinedButton(
                onClick = { onConnect(item.slug, needsReconnect) },
                enabled = item.isEnabled && !isAnyActionPending && !isLoading,
                modifier = Modifier.testTag("session_integrations_action_${item.slug}"),
            ) {
                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
