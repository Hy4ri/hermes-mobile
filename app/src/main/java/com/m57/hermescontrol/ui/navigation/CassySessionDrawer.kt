package com.m57.hermescontrol.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.workspace.WorkspaceUiState

@Composable
fun CassySessionDrawer(
    state: WorkspaceUiState,
    connectionStatus: ConnectionStatus,
    onNewChat: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onToggleSessionPin: (String) -> Unit,
    onManageSessions: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val pinnedIds =
        remember(state.store.sessionPreferences) {
            state.store.sessionPreferences.filter { it.pinned }.mapTo(mutableSetOf()) { it.sessionId }
        }
    val orderedSessions =
        remember(state.sessions, pinnedIds, query) {
            state.sessions.values
                .sortedWith(
                    compareByDescending<SessionInfo> { it.id in pinnedIds }.thenByDescending { it.started_at ?: 0.0 },
                )
                .filter { session ->
                    query.isBlank() ||
                        session.title.orEmpty().contains(query, ignoreCase = true) ||
                        session.preview.orEmpty().contains(query, ignoreCase = true)
                }
        }

    Column(
        modifier = modifier.fillMaxHeight().widthIn(max = 360.dp),
    ) {
        DrawerHeader(connectionStatus = connectionStatus, activeRuns = state.activeRuns)

        Button(
            onClick = onNewChat,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.drawer_new_chat), fontWeight = FontWeight.SemiBold)
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            placeholder = { Text(stringResource(R.string.drawer_search_chats)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    stringResource(
                        if (query.isBlank()) R.string.drawer_chats else R.string.drawer_search_results,
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (state.isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = orderedSessions.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (orderedSessions.isEmpty()) {
                item {
                    EmptyConversationState(hasQuery = query.isNotBlank())
                }
            } else {
                items(orderedSessions, key = { it.id }) { session ->
                    SessionDrawerRow(
                        session = session,
                        selected = session.id == state.store.activeSessionId,
                        pinned = session.id in pinnedIds,
                        onClick = { onSessionSelected(session.id) },
                        onTogglePin = { onToggleSessionPin(session.id) },
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationDrawerItem(
            icon = { Icon(Icons.Filled.History, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_manage_chats)) },
            selected = false,
            onClick = onManageSessions,
            modifier = Modifier.padding(horizontal = 8.dp),
            colors = drawerItemColors(),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Filled.GridView, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_tools)) },
            selected = false,
            onClick = onOpenTools,
            modifier = Modifier.padding(horizontal = 8.dp),
            colors = drawerItemColors(),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_settings)) },
            selected = false,
            onClick = onOpenSettings,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            colors = drawerItemColors(),
        )
    }
}

@Composable
private fun DrawerHeader(
    connectionStatus: ConnectionStatus,
    activeRuns: Int,
) {
    val statusColors = LocalHermesStatusColors.current
    val (statusColor, statusLabel) =
        when (connectionStatus) {
            ConnectionStatus.CONNECTED -> statusColors.success to stringResource(R.string.drawer_connected)
            ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING ->
                statusColors.warning to stringResource(R.string.drawer_connecting)
            else -> statusColors.error to stringResource(R.string.drawer_offline)
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(
                    42.dp,
                ).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text("Cassy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                Text(
                    text =
                        if (activeRuns > 0) {
                            stringResource(R.string.drawer_status_active, statusLabel, activeRuns)
                        } else {
                            "  $statusLabel"
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SessionDrawerRow(
    session: SessionInfo,
    selected: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val title = session.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.drawer_untitled_chat)
    val preview =
        session.preview?.takeIf { it.isNotBlank() }
            ?: session.message_count?.let { stringResource(R.string.drawer_message_count, it) }
            ?: stringResource(R.string.drawer_no_messages)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Spacer(modifier = Modifier.size(9.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            Text(
                text = preview,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onTogglePin),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription =
                    stringResource(if (pinned) R.string.drawer_unpin else R.string.drawer_pin),
                modifier = Modifier.size(18.dp),
                tint =
                    if (pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    },
            )
        }
    }
}

@Composable
private fun EmptyConversationState(hasQuery: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (hasQuery) Icons.Filled.Search else Icons.Filled.Forum,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text =
                stringResource(
                    if (hasQuery) R.string.drawer_no_matching_chat else R.string.drawer_no_chats,
                ),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text =
                stringResource(
                    if (hasQuery) R.string.drawer_try_other_search else R.string.drawer_start_first_chat,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun drawerItemColors() =
    NavigationDrawerItemDefaults.colors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
