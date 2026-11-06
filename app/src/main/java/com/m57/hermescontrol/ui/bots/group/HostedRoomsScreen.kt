package com.m57.hermescontrol.ui.bots.group

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.AlertDialog
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.HostedRoomSnapshot
import com.m57.hermescontrol.data.ws.HostedTask
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.bots.group.components.HostedRoomContextDialog
import com.m57.hermescontrol.ui.bots.group.components.contextLabel
import com.m57.hermescontrol.ui.chat.MarkdownText
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesIconButton
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.m57.hermescontrol.ui.common.ExpressiveButton as Button
import com.m57.hermescontrol.ui.common.ExpressiveTextButton as TextButton

@Composable
fun HostedRoomsScreen(
    onBack: () -> Unit,
    viewModel: HostedRoomsViewModel = viewModel { HostedRoomsViewModel() },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(lifecycle, viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(AuthManager.dataScopeFlow, HermesWsClient.connectionStatus) { dataScope, connection ->
                dataScope to connection
            }.collectLatest {
                viewModel.syncOwner()
                while (true) {
                    viewModel.refresh()
                    delay(2_000)
                }
            }
        }
    }
    var confirmLeave by remember(state.owner?.scopeKey, state.selectedRoomId) { mutableStateOf(false) }
    val requestBack: () -> Unit = {
        when {
            state.selectedRoomId == null -> onBack()
            state.pendingSend != null -> confirmLeave = true
            else -> viewModel.select(null)
        }
    }
    BackHandler(enabled = state.selectedRoomId != null, onBack = requestBack)
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.hosted_leave_title)) },
            text = { Text(stringResource(R.string.hosted_leave_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onBack()
                }) {
                    Text(stringResource(R.string.hosted_leave))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    HostedRoomsContent(
        state = state,
        onBack = requestBack,
        onRefresh = { scope.launch { viewModel.refresh() } },
        onSelect = viewModel::select,
        onSend = viewModel::send,
        onRetrySend = viewModel::retrySend,
        onStop = viewModel::stop,
        onRetry = viewModel::retry,
        onApprove = viewModel::approve,
        onLoadContext = viewModel::loadContext,
        onSaveContext = viewModel::saveContext,
    )
}

@Composable
internal fun HostedRoomsContent(
    state: HostedRoomsState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onSend: (String) -> Unit,
    onRetrySend: () -> Unit,
    onStop: () -> Unit,
    onRetry: (HostedTask) -> Unit,
    onApprove: (HostedTask, String) -> Unit,
    onLoadContext: suspend (HostedOwner, String) -> Result<HostedRoomSnapshot>,
    onSaveContext: suspend (HostedOwner, String, String, String, String?, String?, Boolean) -> Result<Unit>,
) {
    val spacing = LocalSpacing.current
    val contextScope = androidx.compose.runtime.rememberCoroutineScope()
    val contextOwner = state.owner
    val contextRoom = state.selectedRoomId
    var editorOpen by remember(contextOwner, contextRoom) { mutableStateOf(false) }
    var editorOpening by remember(contextOwner, contextRoom) { mutableLongStateOf(0L) }
    var editorSnapshot by remember(contextOwner, contextRoom) { mutableStateOf<HostedRoomSnapshot?>(null) }
    var editorLoading by remember(contextOwner, contextRoom) { mutableStateOf(false) }
    var editorSaving by remember(contextOwner, contextRoom) { mutableStateOf(false) }
    var editorError by remember(contextOwner, contextRoom) { mutableStateOf<String?>(null) }
    val contextEnabled =
        contextOwner?.connected == true && state.capabilities?.contextSupported == true &&
            state.snapshot?.room?.disbandedAt == null && !state.busy && !state.contextSaving
    val loadEditor: () -> Unit = {
        if (contextOwner != null && contextRoom != null) {
            editorOpen = true
            editorOpening++
            val opening = editorOpening
            editorSnapshot = null
            editorLoading = true
            editorError = null
            contextScope.launch {
                val result = onLoadContext(contextOwner, contextRoom)
                if (editorOpen && editorOpening == opening) {
                    editorSnapshot = result.getOrNull()
                    editorError = result.exceptionOrNull()?.message
                    editorLoading = false
                }
            }
        }
    }
    if (editorOpen && contextOwner != null && contextRoom != null) {
        androidx.compose.runtime.key(contextOwner, contextRoom, editorOpening) {
            HostedRoomContextDialog(
                context = editorSnapshot?.context,
                members = state.snapshot?.room?.members ?: editorSnapshot?.room?.members.orEmpty(),
                loading = editorLoading,
                saving = editorSaving,
                error = editorError,
                writable = contextEnabled,
                pendingContextSave = state.pendingContextSave,
                onDismiss = { if (!editorSaving) editorOpen = false },
                onReload = loadEditor,
                onSave = { purpose, notes, routing, leadMemberId ->
                    val opening = editorOpening
                    val retry = editorError != null
                    editorSaving = true
                    editorError = null
                    contextScope.launch {
                        val result =
                            onSaveContext(contextOwner, contextRoom, purpose, notes, routing, leadMemberId, retry)
                        if (editorOpen && editorOpening == opening) {
                            editorSaving = false
                            if (result.isSuccess) {
                                editorOpen = false
                            } else {
                                editorError = result.exceptionOrNull()?.message
                            }
                        }
                    }
                },
            )
        }
    }
    var draft by remember(state.owner?.scopeKey, state.selectedRoomId) { mutableStateOf("") }
    var confirmation by remember(state.owner, state.selectedRoomId) { mutableStateOf<Pair<HostedTask, String?>?>(null) }
    confirmation?.let { (task, choice) ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = {
                Text(
                    stringResource(
                        if (choice ==
                            null
                        ) {
                            R.string.hosted_retry_title
                        } else {
                            R.string.hosted_approval_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    if (choice == null) {
                        stringResource(R.string.hosted_retry_warning)
                    } else {
                        listOf(task.memberId, task.approval?.command.orEmpty(), task.approval?.reason.orEmpty())
                            .filter(String::isNotBlank)
                            .joinToString("\n")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmation = null
                        if (choice == null) onRetry(task) else onApprove(task, choice)
                    },
                    enabled =
                        state.writable && task in
                            state.snapshot
                                ?.driverStatus
                                ?.pendingActions
                                .orEmpty(),
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    HermesScaffold(
        title = { Text(state.snapshot?.room?.name ?: stringResource(R.string.hosted_rooms_title)) },
        navigationIcon = NavIcon.Back(onBack),
        drawerGesturesEnabled = false,
        onRefresh = onRefresh,
        isRefreshing = state.loading,
        actions = {
            if (contextRoom != null && state.capabilities?.contextSupported == true) {
                HermesIconButton(
                    imageVector = Icons.Default.Settings,
                    label = stringResource(R.string.hosted_context_settings),
                    onClick = loadEditor,
                    enabled = contextEnabled && !editorOpen,
                )
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            when {
                state.owner?.connected != true -> {
                    EmptyState(title = stringResource(R.string.hosted_offline), modifier = Modifier.fillMaxSize())
                }

                state.unsupported -> {
                    EmptyState(
                        title = stringResource(R.string.hosted_unavailable),
                        subtitle = stringResource(R.string.hosted_legacy_unchanged),
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                state.loading && state.capabilities == null -> {
                    LoadingState()
                }

                state.error != null && state.capabilities == null -> {
                    ErrorState(message = state.error, onRetry = onRefresh)
                }

                state.selectedRoomId == null -> {
                    if (state.error !=
                        null
                    ) {
                        Text(
                            state.error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(spacing.md),
                        )
                    }
                    if (state.rooms.isEmpty()) {
                        EmptyState(
                            title = stringResource(R.string.hosted_rooms_empty),
                            subtitle = stringResource(R.string.hosted_rooms_setup),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(horizontal = spacing.md),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            items(state.rooms, key = { it.roomId }) { room ->
                                Card(Modifier.fillMaxWidth().clickable { onSelect(room.roomId) }) {
                                    Column(Modifier.padding(spacing.md)) {
                                        Text(room.name, style = MaterialTheme.typography.titleMedium)
                                        Text(room.roomId, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    state.snapshot?.context?.takeIf { state.capabilities?.contextSupported == true }?.let { confirmed ->
                        Column(
                            modifier = Modifier.padding(horizontal = spacing.md),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            if (confirmed.purpose.isNotBlank()) {
                                Text(
                                    confirmed.purpose,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (confirmed.routing == "lead") {
                                state.snapshot
                                    ?.room
                                    ?.members
                                    .orEmpty()
                                    .find { !it.memberId.isNullOrBlank() && it.memberId == confirmed.leadMemberId }
                                    ?.let { lead ->
                                        Text(
                                            stringResource(R.string.hosted_context_confirmed_lead, lead.contextLabel()),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                            }
                        }
                    }
                    if (state.error !=
                        null
                    ) {
                        Text(
                            state.error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(spacing.md),
                        )
                    }
                    if (state.capabilities?.driver != true || state.snapshot?.driverStatus?.running != true) {
                        Text(
                            stringResource(R.string.hosted_read_only),
                            modifier = Modifier.padding(horizontal = spacing.md),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        item {
                            HostedMemberProgress(
                                driver = state.snapshot?.driverStatus,
                                owner = state.owner,
                                roomId = state.selectedRoomId,
                            )
                        }
                        if (state.events.isEmpty()) {
                            item { Text(stringResource(R.string.hosted_log_empty)) }
                        }
                        items(state.events, key = { it.seq }) { event ->
                            Column {
                                Text(
                                    "${event.seq} · ${(event.actor["id"] as? JsonPrimitive)?.contentOrNull ?: event.kind}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val text = (event.payload["text"] as? JsonPrimitive)?.contentOrNull
                                if (text != null) {
                                    MarkdownText(text = text, textColor = MaterialTheme.colorScheme.onSurface)
                                } else {
                                    Text(event.kind, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        items(
                            state.snapshot
                                ?.driverStatus
                                ?.pendingActions
                                .orEmpty(),
                            key = {
                                "action:${it.kind}:${it.memberId}:${it.taskId}:${it.executionGeneration}:${it.requestId}"
                            },
                        ) { task ->
                            Column {
                                Text(task.memberId, style = MaterialTheme.typography.labelLarge)
                                when (task.kind) {
                                    "retry" -> {
                                        TextButton(
                                            onClick = { confirmation = task to null },
                                            enabled = state.writable,
                                        ) {
                                            Text(stringResource(R.string.hosted_retry_title))
                                        }
                                    }

                                    "approval" -> {
                                        Text(task.approval?.command.orEmpty())
                                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                                            task.approval
                                                ?.choices
                                                .orEmpty()
                                                .filter { it == "once" || it == "deny" }
                                                .forEach { choice ->
                                                    TextButton(
                                                        onClick = { confirmation = task to choice },
                                                        enabled = state.writable,
                                                    ) {
                                                        Text(
                                                            stringResource(
                                                                if (choice ==
                                                                    "once"
                                                                ) {
                                                                    R.string.hosted_allow_once
                                                                } else {
                                                                    R.string.hosted_deny
                                                                },
                                                            ),
                                                        )
                                                    }
                                                }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    state.pendingSend?.let { pending ->
                        Column(Modifier.padding(horizontal = spacing.md)) {
                            Text(stringResource(R.string.hosted_send_uncertain))
                            Text(pending.text)
                            TextButton(onClick = onRetrySend, enabled = state.writable) {
                                Text(stringResource(R.string.hosted_resend_same))
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = { Text(stringResource(R.string.hosted_message)) },
                            modifier = Modifier.weight(1f),
                            enabled = state.writable && state.pendingSend == null,
                        )
                        Button(
                            onClick = {
                                onSend(draft)
                                draft = ""
                            },
                            enabled =
                                state.writable && state.pendingSend == null && draft.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.hosted_send))
                        }
                    }
                    TextButton(
                        onClick = onStop,
                        enabled = state.writable,
                    ) { Text(stringResource(R.string.hosted_stop)) }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun HostedRoomsUnsupportedPreview() {
    HostedRoomsContent(
        state = HostedRoomsState(owner = HostedOwner("preview", "default", 1, true), unsupported = true),
        onBack = {},
        onRefresh = {},
        onSelect = {},
        onSend = {},
        onRetrySend = {},
        onStop = {},
        onRetry = {},
        onApprove = {
            _,
            _,
            ->
        },
        onLoadContext = { _, _ -> Result.failure(IllegalStateException("Preview")) },
        onSaveContext = { _, _, _, _, _, _, _ -> Result.success(Unit) },
    )
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Preview(showBackground = true, widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ar")
@Composable
private fun HostedRoomComposerPreview() {
    val direction =
        if (LocalConfiguration.current.locales[0].language ==
            "ar"
        ) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        com.m57.hermescontrol.theme.HermesControlTheme(
            themePreference = com.m57.hermescontrol.theme.ThemePreference.DARK,
        ) {
            HostedRoomsContent(
                state =
                    HostedRoomsState(
                        owner = HostedOwner("preview", "default", 1, true),
                        capabilities =
                            com.m57.hermescontrol.data.ws
                                .HostedCapabilities(true, true, 100),
                        selectedRoomId = "room-preview",
                        snapshot =
                            com.m57.hermescontrol.data.ws.HostedRoomSnapshot(
                                room =
                                    com.m57.hermescontrol.data.ws
                                        .HostedRoom("room-preview", "Hosted room"),
                                driverStatus =
                                    com.m57.hermescontrol.data.ws
                                        .HostedDriverStatus(
                                            running = true,
                                            maxRounds = 8,
                                            memberActivity =
                                                listOf(
                                                    com.m57.hermescontrol.data.ws.HostedMemberActivity(
                                                        memberId = "Researcher",
                                                        status = "running",
                                                        threadId = "thread-1",
                                                        roundIndex = 1,
                                                        startedAt = System.currentTimeMillis() / 1_000.0 - 12,
                                                        tool = "web_search",
                                                        progressAt = null,
                                                    ),
                                                    com.m57.hermescontrol.data.ws.HostedMemberActivity(
                                                        memberId = "Reviewer",
                                                        status = "queued",
                                                    ),
                                                ),
                                        ),
                            ),
                    ),
                onBack = {},
                onRefresh = {},
                onSelect = {},
                onSend = {},
                onRetrySend = {},
                onStop = {},
                onRetry = {},
                onApprove = { _, _ -> },
                onLoadContext = { _, _ -> Result.failure(IllegalStateException("Preview")) },
                onSaveContext = { _, _, _, _, _, _, _ -> Result.success(Unit) },
            )
        }
    }
}
