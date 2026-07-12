package com.m57.hermescontrol.ui.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.model.SessionInfo

@Composable
fun WorkspaceControlBar(
    state: WorkspaceUiState,
    currentSessionId: String?,
    isRunning: Boolean,
    onSessionSelected: (String) -> Unit,
    onSessionClosed: (String) -> Unit,
    onSessionPinToggled: (String) -> Unit,
    onModelSelected: (String) -> Unit,
    onWorkspaceSelected: (String) -> Unit,
    onWorkspaceCreated: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var workspaceMenuOpen by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var createWorkspaceOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
                        modifier =
                            Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = {
                                workspaceMenuOpen =
                                    true
                            }),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.DashboardCustomize,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                state.selectedWorkspace?.name ?: "Focus",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Choose workspace",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    DropdownMenu(expanded = workspaceMenuOpen, onDismissRequest = { workspaceMenuOpen = false }) {
                        state.store.workspaces.forEach { workspace ->
                            DropdownMenuItem(
                                text = { Text(workspace.name) },
                                leadingIcon = {
                                    if (workspace.id == state.store.selectedWorkspaceId) {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    onWorkspaceSelected(workspace.id)
                                    workspaceMenuOpen = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("New workspace") },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            onClick = {
                                workspaceMenuOpen = false
                                createWorkspaceOpen = true
                            },
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))
                StatusPill(
                    label = if (state.activeRuns == 1) "1 active" else "${state.activeRuns} active",
                    active = state.activeRuns > 0,
                )
                Spacer(Modifier.weight(1f))

                Box {
                    Surface(
                        onClick = { if (state.pinnedModels.isNotEmpty()) modelMenuOpen = true },
                        enabled = state.pinnedModels.isNotEmpty() && currentSessionId != null,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Model", style = MaterialTheme.typography.labelMedium)
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                        state.pinnedModels.forEach { model ->
                            val alias = model.alias()
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.modelName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            model.providerSlug,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    onModelSelected(alias)
                                    modelMenuOpen = false
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh workspace status")
                    }
                }
            }

            AnimatedVisibility(visible = state.openSessions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    state.openSessions.forEach { session ->
                        SessionTab(
                            session = session,
                            selected = session.id == currentSessionId,
                            running = isRunning && session.id == currentSessionId,
                            pinned =
                                state.store.sessionPreferences
                                    .firstOrNull { it.sessionId == session.id }
                                    ?.pinned ==
                                    true,
                            onClick = { onSessionSelected(session.id) },
                            onClose = { onSessionClosed(session.id) },
                            onLongClick = { onSessionPinToggled(session.id) },
                        )
                    }
                }
            }
        }
    }

    if (createWorkspaceOpen) {
        CreateWorkspaceDialog(
            onDismiss = { createWorkspaceOpen = false },
            onCreate = {
                onWorkspaceCreated(it)
                createWorkspaceOpen = false
            },
        )
    }
}

@Composable
fun WorkspaceSessionPane(
    state: WorkspaceUiState,
    currentSessionId: String?,
    isRunning: Boolean,
    onSessionSelected: (String) -> Unit,
    onSessionClosed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessionsById = state.sessions
    val orderedIds =
        remember(state.store.openSessionIds, state.store.sessionPreferences) {
            state.store.openSessionIds.sortedWith(
                compareByDescending<String> { id ->
                    state.store.sessionPreferences
                        .firstOrNull { it.sessionId == id }
                        ?.pinned == true
                }.thenBy { state.store.openSessionIds.indexOf(it) },
            )
        }
    Surface(
        modifier = modifier.width(286.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
            Text("Session desk", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "${orderedIds.size} open · ${state.activeRuns} running",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(orderedIds, key = { it }) { id ->
                    val session = sessionsById[id]
                    val selected = id == currentSessionId
                    Surface(
                        onClick = { onSessionSelected(id) },
                        shape = RoundedCornerShape(18.dp),
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(
                                    alpha = 0.82f,
                                )
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                        border =
                            BorderStroke(
                                1.dp,
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                },
                            ),
                    ) {
                        Row(
                            modifier =
                                Modifier.fillMaxWidth().padding(
                                    start = 13.dp,
                                    end = 6.dp,
                                    top = 10.dp,
                                    bottom = 10.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                modifier = Modifier.size(8.dp),
                                shape = CircleShape,
                                color =
                                    if (selected &&
                                        isRunning
                                    ) {
                                        Color(0xFF55C893)
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                content = {},
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    session?.title?.takeIf { it.isNotBlank() } ?: id,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val model =
                                    state.store.sessionPreferences
                                        .firstOrNull { it.sessionId == id }
                                        ?.modelAlias
                                if (!model.isNullOrBlank()) {
                                    Text(
                                        model.substringAfterLast('/'),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconButton(onClick = { onSessionClosed(id) }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Close session tab",
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionTab(
    session: SessionInfo,
    selected: Boolean,
    running: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier =
            Modifier
                .heightIn(
                    min = 38.dp,
                ).clip(shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = shape,
        color =
            if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        border =
            BorderStroke(
                1.dp,
                if (selected) {
                    MaterialTheme.colorScheme.secondary.copy(
                        alpha = 0.55f,
                    )
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                },
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 3.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.8.dp)
                Spacer(Modifier.width(6.dp))
            } else if (pinned) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = "Pinned",
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                session.title?.takeIf { it.isNotBlank() } ?: "Untitled session",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(118.dp),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(27.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close tab", modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    active: Boolean,
) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(7.dp),
                shape = CircleShape,
                color = if (active) Color(0xFF55C893) else MaterialTheme.colorScheme.outline,
                content = {},
            )
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CreateWorkspaceDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DashboardCustomize, contentDescription = null) },
        title = { Text("Create workspace") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Name") },
                supportingText = { Text("Group related sessions without changing Hermes history.") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun PinnedModel.alias(): String = "$providerSlug/$modelName"
