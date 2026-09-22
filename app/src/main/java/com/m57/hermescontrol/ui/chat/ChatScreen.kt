package com.m57.hermescontrol.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.ExternalActivityLifecycleGuard
import com.m57.hermescontrol.HistoryScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.model.reasoningSupport
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.notification.NotificationHelper
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.components.ChatAppUpdateSection
import com.m57.hermescontrol.ui.chat.components.ChatConnectionBanner
import com.m57.hermescontrol.ui.chat.components.ChatHistoryWindowBanner
import com.m57.hermescontrol.ui.chat.components.ChatInputBar
import com.m57.hermescontrol.ui.chat.components.ChatLifecycleEffects
import com.m57.hermescontrol.ui.chat.components.ChatLoadingOverlay
import com.m57.hermescontrol.ui.chat.components.ChatResumeErrorOverlay
import com.m57.hermescontrol.ui.chat.components.ChatScrollToBottomFab
import com.m57.hermescontrol.ui.chat.components.ChatTimelineNoPrefetchStrategy
import com.m57.hermescontrol.ui.chat.components.ChatTimelineSheet
import com.m57.hermescontrol.ui.chat.components.ContextDetailSheet
import com.m57.hermescontrol.ui.chat.components.ConnectionSetupSheet
import com.m57.hermescontrol.ui.chat.components.ContextUsageChip
import com.m57.hermescontrol.ui.chat.components.ReactionHeartsOverlay
import com.m57.hermescontrol.ui.chat.components.ReloginDialog
import com.m57.hermescontrol.ui.chat.components.SearchBarRow
import com.m57.hermescontrol.ui.chat.components.SessionIntegrationsSheet
import com.m57.hermescontrol.ui.chat.components.SideQuestionSheet
import com.m57.hermescontrol.ui.chat.components.SubagentInspectionSheet
import com.m57.hermescontrol.ui.chat.components.TaskProgressChip
import com.m57.hermescontrol.ui.chat.components.rememberChatMediaLaunchers
import com.m57.hermescontrol.ui.chat.components.rememberChatScrollController
import com.m57.hermescontrol.ui.chat.components.shouldShowProgressChip
import com.m57.hermescontrol.ui.chat.components.tailContentKey
import com.m57.hermescontrol.ui.chat.fullbleed.FullBleedChatList
import com.m57.hermescontrol.ui.common.ActionProgressDialog
import com.m57.hermescontrol.ui.common.AutoScrollingTitleText
import com.m57.hermescontrol.ui.common.CredentialWarningBanner
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.model.components.ModelPickerDialog
import com.m57.hermescontrol.util.ConnectorUrlValidator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SESSION_SYNC_INTERVAL_MS = 30_000L

internal fun acceptedSaveDestination(
    resultCode: Int,
    destination: Uri?,
): Uri? = destination.takeIf { resultCode == Activity.RESULT_OK }

internal fun canStartAttachmentSave(
    pendingSavePath: String?,
    savingAttachmentPath: String?,
): Boolean = pendingSavePath == null && savingAttachmentPath == null

/**
 * Chat screen — the primary conversation surface of Hermes Control.
 *
 * This file is a thin compositor that delegates all UI rendering to focused
 * composable components under `ui/chat/components/`. See issue #621 for the
 * rationale behind the split and the full file→content mapping.
 *
 * The original 2,267-line god file was split into 11 single-purpose files;
 * this entry point handles only state hoisting, scaffold wiring, and the
 * remembered launchers that need to be activity-scoped.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    sessionId: String? = null,
    viewModel: ChatViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val streamingState by viewModel.streamingState.collectAsStateWithLifecycle()
    val timelineState by viewModel.timelineState.collectAsStateWithLifecycle()
    val credentialWarning by HermesWsClient.credentialWarning.collectAsStateWithLifecycle()
    val connectorsViewModel: ChatConnectorsViewModel = viewModel()
    val connectorsState by connectorsViewModel.uiState.collectAsStateWithLifecycle()
    val actionProgressState by viewModel.actionProgress.state.collectAsStateWithLifecycle()
    val connectionOperationState by viewModel.connectionOperationState.collectAsStateWithLifecycle()
    // Snapshot-backed search state — read directly so only the scopes that
    // read its fields recompose on search changes (bar, matched bubbles).
    val searchState = viewModel.searchState
    val displayedMessages = timelineState.historyMessages ?: state.messages
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var browserAuthInFlight by rememberSaveable { mutableStateOf(false) }
    var browserAuthDeparted by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                        if (browserAuthInFlight) {
                            browserAuthDeparted = true
                        }
                        connectorsViewModel.onPause()
                    }

                    Lifecycle.Event.ON_RESUME -> {
                        connectorsViewModel.onResume()
                        if (browserAuthInFlight && browserAuthDeparted) {
                            browserAuthInFlight = false
                            browserAuthDeparted = false
                            ExternalActivityLifecycleGuard.externalActivityReturned()
                        }
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val activity =
                generateSequence(context) { (it as? ContextWrapper)?.baseContext }
                    .filterIsInstance<Activity>()
                    .firstOrNull()
            val isChangingConfigs = activity?.isChangingConfigurations == true
            if (!isChangingConfigs) {
                if (browserAuthInFlight && browserAuthDeparted) {
                    browserAuthInFlight = false
                    browserAuthDeparted = false
                    ExternalActivityLifecycleGuard.externalActivityReturned()
                }
                connectorsViewModel.onPause()
                connectorsViewModel.hide()
            }
        }
    }

    val browserEvent = connectorsState.browserLaunchEvent
    val listState = rememberLazyListState(prefetchStrategy = ChatTimelineNoPrefetchStrategy)
    val scrollScope = rememberCoroutineScope()
    val scrollController = rememberChatScrollController(listState, scrollScope)
    var showContextSheet by remember { mutableStateOf(false) }
    var pendingSavePath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSaveName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSaveMimeType by rememberSaveable { mutableStateOf<String?>(null) }
    val saveAttachmentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                ExternalActivityLifecycleGuard.externalActivityReturned()
                val path = pendingSavePath
                val destination = acceptedSaveDestination(result.resultCode, result.data?.data)
                if (destination != null && path != null) {
                    viewModel.saveAttachment(
                        Attachment(
                            uri = "gateway:$path",
                            name = pendingSaveName ?: "download",
                            mimeType = pendingSaveMimeType ?: "application/octet-stream",
                            size = 0,
                            source = AttachmentSource.GATEWAY,
                        ),
                        destination,
                    )
                }
            } finally {
                pendingSavePath = null
                pendingSaveName = null
                pendingSaveMimeType = null
            }
        }

    // Periodic session sync while connected.
    LaunchedEffect(lifecycleOwner, state.currentSessionId, state.connectionStatus) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (state.currentSessionId != null && state.connectionStatus == ConnectionStatus.CONNECTED) {
                viewModel.syncCurrentSession()
                viewModel.fetchContextUsage()
            }
            while (state.currentSessionId != null && state.connectionStatus == ConnectionStatus.CONNECTED) {
                delay(SESSION_SYNC_INTERVAL_MS)
                viewModel.syncCurrentSession()
                viewModel.fetchContextUsage()
            }
        }
    }

    // /resume · /history (issue #864): open the session history tab so the
    // user picks a past session — client-side, no gateway round-trip.
    LaunchedEffect(state.openHistoryRequested) {
        if (state.openHistoryRequested) {
            NavigationController.navigateTo(HistoryScreen)
            viewModel.consumeOpenHistoryRequest()
        }
    }

    // FullBleedChatList preserves the live lazy row key and pixel offset on prepend.
    // Never restore a message index captured before an asynchronous fetch: it is not
    // a lazy row index and the reader may have moved in the meantime.

    // Continuous bottom-follow tracking from LazyListState (issue #682).
    LaunchedEffect(Unit) {
        scrollController.observeUserScrollPosition()
    }

    // Drive follow + unseen tracking from a stable tail-content key covering
    // messages, streaming, thinking, subagent cards, and clarify prompts
    // (issue #682). Replaces the old item-count heuristic that ignored the
    // streaming tail.
    LaunchedEffect(
        timelineState.isHistorical,
        state.messages,
        streamingState.streamingMessage,
        streamingState.isThinking,
        state.subagentIndicators,
        state.todos,
        state.clarifyRequest,
    ) {
        if (timelineState.isHistorical) {
            scrollController.pauseFollowing()
            return@LaunchedEffect
        }
        scrollController.onTailChanged(
            tailKey =
                tailContentKey(
                    messages = state.messages,
                    streamingMessage = streamingState.streamingMessage,
                    isThinking = streamingState.isThinking,
                    subagentIndicators = state.subagentIndicators,
                    clarifyRequest = state.clarifyRequest,
                ),
            messageCount = state.messages.size,
        )
    }
    LaunchedEffect(timelineState.historyAnchorRowId) {
        if (timelineState.isHistorical && displayedMessages.isNotEmpty()) {
            scrollController.pauseFollowing()
            listState.scrollToItem(0)
        }
    }
    val showScrollToBottom by remember(timelineState.isHistorical, displayedMessages) {
        derivedStateOf {
            !timelineState.isHistorical && scrollController.showFab(displayedMessages.isNotEmpty())
        }
    }
    var inputFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }

    LaunchedEffect(state.pendingPrefillText) {
        val prefill = state.pendingPrefillText
        if (prefill != null) {
            inputFieldValue = ChatInputPolicy.commandFieldValue(prefill)
            viewModel.consumePendingPrefill()
        }
    }
    LaunchedEffect(state.composerTextToRestore) {
        state.composerTextToRestore?.let { text ->
            inputFieldValue = ChatInputPolicy.restoreRejectedText(text, inputFieldValue)
            viewModel.consumeComposerTextRestore()
        }
    }
    var lastAnimatedMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var showReloginDialog by rememberSaveable { mutableStateOf(false) }
    var showSubagentInspectionSheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showSubagentInspectionSheet) {
        if (showSubagentInspectionSheet) {
            viewModel.hydrateSubagents()
        }
    }
    var viewingImage by rememberSaveable { mutableStateOf<ImageViewerModel?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val launchExternalActivity: (() -> Unit) -> Unit = { launch ->
        ExternalActivityLifecycleGuard.launchExternalActivity(
            acquireConnectionLease = HermesWsClient::acquireExternalActivityConnectionLease,
            releaseConnectionLease = HermesWsClient::releaseExternalActivityConnectionLease,
            prepareForBackground = { NotificationHelper.start(context) },
            cleanupAfterLaunchFailure = { NotificationHelper.stop(context) },
            launch = launch,
        )
    }

    val invalidResponseError = stringResource(R.string.session_integrations_err_invalid_response)
    val browserLaunchError = stringResource(R.string.session_integrations_err_browser_launch)

    LaunchedEffect(browserEvent) {
        if (browserEvent != null) {
            val eventId = browserEvent.eventId
            val taken = connectorsViewModel.takeBrowserEvent(eventId)
            if (taken != null) {
                if (!ConnectorUrlValidator.isValidHttpsUrl(taken.url)) {
                    connectorsViewModel.launchError(invalidResponseError)
                    return@LaunchedEffect
                }
                try {
                    val intent =
                        Intent(Intent.ACTION_VIEW, Uri.parse(taken.url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    launchExternalActivity {
                        browserAuthDeparted = false
                        browserAuthInFlight = true
                        context.startActivity(intent)
                    }
                } catch (_: ActivityNotFoundException) {
                    browserAuthInFlight = false
                    browserAuthDeparted = false
                    connectorsViewModel.launchError(browserLaunchError)
                } catch (_: SecurityException) {
                    browserAuthInFlight = false
                    browserAuthDeparted = false
                    connectorsViewModel.launchError(browserLaunchError)
                } catch (_: Exception) {
                    browserAuthInFlight = false
                    browserAuthDeparted = false
                    connectorsViewModel.launchError(browserLaunchError)
                }
            }
        }
    }

    val mediaLaunchers =
        rememberChatMediaLaunchers(
            inputFieldValue = inputFieldValue,
            onInputFieldValueChange = { inputFieldValue = it },
            onAddAttachment = { uri, name, mimeType, size ->
                viewModel.addAttachment(uri, name, mimeType, size)
            },
            onAddAttachments = { attachments ->
                viewModel.addAttachments(attachments)
            },
            onShowMessage = { msg ->
                scrollScope.launch {
                    snackbarHostState.showSnackbar(msg)
                }
            },
            launchExternalActivity = launchExternalActivity,
            context = context,
        )

    val isChatContentReadable =
        !showReloginDialog &&
            !state.updateConfirmOpen &&
            !actionProgressState.visible &&
            !state.showModelPicker &&
            state.modelSwitchConfirmMessage == null &&
            state.sudoPrompt == null &&
            state.secretPrompt == null &&
            !(showContextSheet && state.contextBreakdown != null) &&
            !showSubagentInspectionSheet &&
            state.btwState == null &&
            viewingImage == null &&
            !connectorsState.isVisible &&
            !timelineState.isOpen

    // Lifecycle effects, permissions, session switching, auto-scroll, errors
    ChatLifecycleEffects(
        sessionId = sessionId,
        connectionStatus = state.connectionStatus,
        currentSessionId = state.currentSessionId,
        messages = state.messages,
        errorMessage = state.errorMessage,
        backgroundCompleteMessage = state.backgroundCompleteMessage,
        openError = state.openError,
        clarifyRequest = state.clarifyRequest,
        sudoPrompt = state.sudoPrompt,
        secretPrompt = state.secretPrompt,
        listState = listState,
        scrollController = scrollController,
        snackbarHostState = snackbarHostState,
        viewModel = viewModel,
        isOverlayActive = !isChatContentReadable,
    )

    HermesScaffold(
        modifier = modifier,
        pinTopBar = true,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AutoScrollingTitleText(
                    text = state.chatTitle,
                    modifier = Modifier.weight(1f),
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                )
                // Connection status dot — red when offline, hidden when connected
                if (!state.isConnected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier =
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(LocalHermesStatusColors.current.error),
                    )
                }
                // Terminal backend chip (issue #860) — ssh/docker/... next to the
                // title; hidden when local (default) or absent.
                val terminalBackend = state.terminalBackend
                if (!terminalBackend.isNullOrBlank() && terminalBackend != "local") {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text(
                            text = terminalBackend,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                val statusColors = LocalHermesStatusColors.current
                val isDownloadComplete = data.visuals.message.startsWith("Saved ")
                Snackbar(
                    snackbarData = data,
                    containerColor =
                        if (isDownloadComplete) statusColors.success else MaterialTheme.colorScheme.errorContainer,
                    contentColor =
                        if (isDownloadComplete) statusColors.onSuccess else MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        actions = {
            IconButton(onClick = { viewModel.createNewSession() }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.content_desc_new_chat),
                )
            }

            // Session actions overflow menu (issue #1091)
            var showSessionMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { showSessionMenu = true },
                    modifier = Modifier.testTag("chat_session_menu_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.session_integrations_menu_item),
                    )
                }
                DropdownMenu(
                    expanded = showSessionMenu,
                    onDismissRequest = { showSessionMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_action_timeline)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                            )
                        },
                        enabled = state.currentSessionId != null,
                        onClick = {
                            showSessionMenu = false
                            viewModel.openTimeline()
                        },
                        modifier = Modifier.testTag("chat_menu_timeline"),
                    )

                    // Chat search lives in this overflow menu (moved from the top bar)
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (searchState.isActive) {
                                        R.string.chat_action_close_search
                                    } else {
                                        R.string.chat_action_search
                                    },
                                ),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector =
                                    if (searchState.isActive) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showSessionMenu = false
                            viewModel.toggleSearch()
                        },
                        enabled = !timelineState.isHistorical,
                        modifier = Modifier.testTag("chat_menu_search"),
                    )

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.session_integrations_menu_item)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Extension,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showSessionMenu = false
                            connectorsViewModel.show()
                        },
                        modifier = Modifier.testTag("chat_menu_session_integrations"),
                    )
                }
            }
        },
    ) { _ ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .imePadding(),
        ) {
            ChatConnectionBanner(
                connectionStatus = state.connectionStatus,
                onReconnect = viewModel::reconnect,
                onReloginClick = { showReloginDialog = true },
            )

            // Issue #942: compact glanceable progress strip while work is active.
            // Bound to the same hydrated todos / subagentIndicators state.
            // Auto-hides when all todos complete/cancel and no subagent is running.
            val workActive = shouldShowProgressChip(state.todos, state.subagentIndicators)
            TaskProgressChip(
                visible = workActive,
                todos = state.todos,
                indicators = state.subagentIndicators,
                onClick = {
                    showSubagentInspectionSheet = true
                    scrollController.resumeFollowing()
                },
            )

            credentialWarning?.let { warning ->
                CredentialWarningBanner(
                    warning = warning,
                    onFix = { NavigationController.navigateTo(com.m57.hermescontrol.ProvidersScreen) },
                    onDismiss = { HermesWsClient.clearCredentialWarning() },
                )
            }

            // Issue #890: launch update check — non-blocking banner when a
            // newer release exists. Tapping "Update" opens the in-place dialog.
            ChatAppUpdateSection()

            AnimatedVisibility(
                visible = searchState.isActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 2.dp,
                    border =
                        BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                        ),
                ) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        SearchBarRow(
                            searchQuery = searchState.query,
                            onQueryChange = { viewModel.setSearchQuery(it) },
                            searchMatchCount = searchState.matchTotal,
                            searchMatchCapped = searchState.matchCapped,
                            currentMatchIndex = searchState.currentIndex,
                            onNavigateUp = { viewModel.navigateSearchMatch(-1) },
                            onNavigateDown = { viewModel.navigateSearchMatch(1) },
                            onClose = { viewModel.clearSearch() },
                        )
                    }
                }
            }

            if (timelineState.isHistorical) {
                ChatHistoryWindowBanner(
                    hasOlder = timelineState.historyHasOlder,
                    hasNewer = timelineState.historyHasNewer,
                    onReturnToLatest = {
                        viewModel.returnToLatestMessages()
                        scrollController.jumpToBottom()
                    },
                )
            }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                val onSaveAttachment: (com.m57.hermescontrol.data.model.Attachment) -> Unit = { attachment ->
                    if (canStartAttachmentSave(pendingSavePath, state.savingAttachmentPath)) {
                        pendingSavePath = viewModel.gatewayPathFor(attachment)
                        pendingSaveName = attachment.name
                        pendingSaveMimeType = attachment.mimeType
                        launchExternalActivity {
                            saveAttachmentLauncher.launch(
                                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type =
                                        attachment.mimeType
                                            .substringBefore(';')
                                            .trim()
                                            .takeIf { it.isNotBlank() } ?: "application/octet-stream"
                                    putExtra(
                                        Intent.EXTRA_TITLE,
                                        attachment.name
                                            .substringAfterLast('/')
                                            .substringAfterLast('\\')
                                            .ifBlank { "download" },
                                    )
                                },
                            )
                        }
                    }
                }

                // Full-bleed chat renderer (issue #866) — the single chat
                // surface since the bubble renderer was removed.
                FullBleedChatList(
                    messages = displayedMessages,
                    streamingState = if (timelineState.isHistorical) StreamingState() else streamingState,
                    isAgentTyping = state.isAgentTyping && !timelineState.isHistorical,
                    searchState = searchState,
                    typingEffectEnabled = state.typingEffectEnabled && !timelineState.isHistorical,
                    typingEffectDelayMs = state.typingEffectDelayMs,
                    messageStatsEnabled = state.messageStatsEnabled,
                    showUserMessageTokens = state.showUserMessageTokens,
                    showAssistantMessageTokens = state.showAssistantMessageTokens,
                    showTokensPerSecond = state.showTokensPerSecond,
                    maxToolCallsPerTurn = state.maxToolCallsPerTurn,
                    isLoading = state.isLoading && !timelineState.isHistorical,
                    isLoadingOlder = state.isLoadingOlder && !timelineState.isHistorical,
                    hasOlderMessages = state.hasOlderMessages && !timelineState.isHistorical,
                    pagingSessionId =
                        state.currentSessionId?.let { currentSessionId ->
                            if (timelineState.isHistorical) {
                                currentSessionId + ":history:" + timelineState.historyAnchorRowId
                            } else {
                                currentSessionId
                            }
                        },
                    listState = listState,
                    scrollController = scrollController,
                    lastAnimatedMessageId = lastAnimatedMessageId,
                    onLastAnimatedMessageIdChange = { lastAnimatedMessageId = it },
                    viewModel = viewModel,
                    clarifyRequest = state.clarifyRequest.takeUnless { timelineState.isHistorical },
                    onRespondClarify = viewModel::respondToClarify,
                    onRespondClarifyBatch = viewModel::respondToClarifyBatch,
                    onDismissClarify = viewModel::dismissClarify,
                    vaultUnlockPrompt = state.vaultUnlockPrompt.takeUnless { timelineState.isHistorical },
                    onRespondVaultUnlock = viewModel::respondToVaultUnlock,
                    onDismissVaultUnlock = viewModel::dismissVaultUnlock,
                    vaultSaveLoginPrompt = state.vaultSaveLoginPrompt.takeUnless { timelineState.isHistorical },
                    onRespondVaultSaveLogin = viewModel::respondToVaultSaveLogin,
                    onDismissVaultSaveLogin = viewModel::dismissVaultSaveLogin,
                    vaultCodePrompt = state.vaultCodePrompt.takeUnless { timelineState.isHistorical },
                    onRespondVaultCode = viewModel::respondToVaultCode,
                    onDismissVaultCode = viewModel::dismissVaultCode,
                    onSaveAttachment = onSaveAttachment,
                    savingAttachmentPath = pendingSavePath ?: state.savingAttachmentPath,
                    openingAttachmentPath = state.openingAttachmentPath,
                    onImageClick = { viewingImage = it },
                )

                // Loading overlay
                ChatLoadingOverlay(
                    isLoading = state.isLoading && state.resumeError == null && !timelineState.isHistorical,
                )

                // Resume-exhausted overlay — explicit error + Retry instead
                // of an infinite spinner (desktop parity).
                if (!timelineState.isHistorical) {
                    ChatResumeErrorOverlay(
                        errorMessage = state.resumeError,
                        onRetry = viewModel::retryResumeSession,
                    )
                }

                // Scroll-to-bottom FAB (issue #682): shows while follow is
                // paused and renders the unseen-message badge.
                ChatScrollToBottomFab(
                    show = showScrollToBottom,
                    pendingCount = scrollController.pendingCount,
                    onScrollToBottom = scrollController::resumeFollowing,
                )

                // Reaction heartsanimation (purely cosmetic — fades out
                // automatically after the ViewModel clears the state)
                if (!timelineState.isHistorical) {
                    key(state.reactionTriggerId) {
                        ReactionHeartsOverlay(
                            reactionKind = state.reactionKind,
                        )
                    }
                }
            }

            // Context-window meter (used / full) — sits above the composer so it
            // stays visible while the session is active, grouped with the model
            // it belongs to without crowding the title or the control row.
            ContextUsageChip(
                usedTokens = state.usedContextTokens,
                fullTokens = state.fullContextTokens,
                compressionCount = state.compressionCount,
                onClick =
                    if (state.contextBreakdown != null) {
                        { showContextSheet = true }
                    } else {
                        null
                    },
            )

            ChatInputBar(
                inputFieldValue = inputFieldValue,
                onInputChange = { inputFieldValue = it },
                onSend = {
                    if (viewModel.sendMessage(inputFieldValue.text)) {
                        inputFieldValue = TextFieldValue("")
                        // Jump only after an accepted send. A readiness race keeps the draft intact.
                        scrollController.jumpToBottom(animated = true)
                    }
                },
                onMicTap = mediaLaunchers.onMicTap,
                isListening = mediaLaunchers.isListening,
                isAgentTyping = state.isAgentTyping,
                isConnected = state.isConnected,
                isSessionReady = state.isSessionReady && !timelineState.isHistorical,
                sessionPreparationFailed = state.resumeError != null,
                commandCatalog = state.commandCatalog,
                slashUsageCounts = state.slashUsageCounts,
                pendingAttachments = state.pendingAttachments,
                onCameraTap = mediaLaunchers.onCameraTap,
                onImageTap = mediaLaunchers.onImageTap,
                onFileTap = mediaLaunchers.onFileTap,
                onRemoveAttachment = viewModel::removeAttachment,
                onPreviewAttachment = { attachment ->
                    if (attachment.isImage) {
                        viewingImage =
                            ImageViewerModel(
                                model = attachment.uri,
                                name = attachment.name,
                                mimeType = attachment.mimeType,
                            )
                    }
                },
                // Composer toolbar wiring (PR 1)
                currentSessionModel = state.currentSessionModel,
                showModelProvider = state.showModelProvider,
                reasoningLevel = state.reasoningLevel,
                reasoningWireLevel = state.reasoningWireLevel,
                pendingReasoningLevel = state.pendingReasoningLevel,
                onModelTap = { viewModel.openModelPicker() },
                onReasoningTap = { level -> viewModel.setReasoningLevel(level) },
                canDisableReasoning = state.currentModelCapabilities?.can_disable_reasoning,
                supportsReasoning = state.currentModelCapabilities?.reasoningSupport,
                fastMode = state.fastMode,
                fastSupported = state.currentModelCapabilities?.fast == true,
                isFastModeChanging = state.isFastModeChanging,
                onToggleFastMode = { viewModel.toggleFastMode() },
            )
        }

        ChatTimelineSheet(
            state = timelineState,
            onDismiss = viewModel::closeTimeline,
            onJump = viewModel::jumpToTimelineEntry,
            onLoadMore = viewModel::loadMoreTimeline,
            onRetry = viewModel::retryTimeline,
        )

        if (showReloginDialog) {
            ReloginDialog(
                onDismiss = { showReloginDialog = false },
                onRelogin = { username, password, onResult ->
                    viewModel.relogin(username, password, onResult)
                },
            )
        }

        // /update from chat (issue #862): confirm, then the shared progress
        // popup tracks the background update (live log tail + final state).
        if (state.updateConfirmOpen) {
            AlertDialog(
                onDismissRequest = viewModel::closeUpdateConfirm,
                title = { Text(stringResource(R.string.system_update_confirm_title)) },
                text = { Text(stringResource(R.string.system_update_confirm_desc)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.closeUpdateConfirm()
                        viewModel.applyUpdate()
                    }) {
                        Text(stringResource(R.string.system_confirm_update_now))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::closeUpdateConfirm) {
                        Text(stringResource(R.string.system_confirm_cancel))
                    }
                },
            )
        }
        ActionProgressDialog(
            controller = viewModel.actionProgress,
            title = stringResource(R.string.system_update_progress_title),
        )

        // In-session model picker (issue #589) — opens on "/model".
        if (state.showModelPicker) {
            ModelPickerDialog(
                providers = state.modelPickerProviders,
                title = stringResource(R.string.chat_switch_model_title),
                isLoading = state.modelPickerLoading && state.modelPickerProviders.isEmpty(),
                pinnedModels = state.modelPickerPinned,
                onPinToggle = { provider, model -> viewModel.togglePinModel(provider, model) },
                onSelect = { provider, model ->
                    viewModel.sendSlashModel(provider, model)
                },
                onDismiss = { viewModel.closeModelPicker() },
            )
        }

        // Expensive / Data-policy model confirmation dialog (issue #589 follow-up)
        if (state.modelSwitchConfirmMessage != null) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissModelSwitchConfirm() },
                title = { Text(stringResource(R.string.model_expensive_title)) },
                text = { Text(state.modelSwitchConfirmMessage!!) },
                confirmButton = {
                    Button(onClick = { viewModel.confirmModelSwitchExpensive() }) {
                        Text(stringResource(R.string.model_confirm_continue))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissModelSwitchConfirm() }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }

        if (showContextSheet && state.contextBreakdown != null) {
            ContextDetailSheet(
                breakdown = state.contextBreakdown!!,
                usedTokens = state.usedContextTokens,
                fullTokens = state.fullContextTokens,
                onDismiss = { showContextSheet = false },
            )
        }

        if (showSubagentInspectionSheet) {
            SubagentInspectionSheet(
                indicators = state.subagentIndicators,
                todos = state.todos,
                inspectingSubagentId = state.inspectingSubagentId,
                subagentTranscript = state.subagentTranscript,
                onToggleTranscript = { subagentId -> viewModel.toggleSubagentTranscript(subagentId) },
                onRetryTranscript = { viewModel.retrySubagentTranscript() },
                onSteerSubagent = { indicator, msg -> viewModel.steerSubagent(indicator, msg) },
                onStopSubagent = { indicator -> viewModel.stopSubagent(indicator) },
                onDismiss = {
                    showSubagentInspectionSheet = false
                    viewModel.closeSubagentTranscript()
                },
            )
        }

        state.btwState?.let { btw ->
            SideQuestionSheet(
                state = btw,
                onDismiss = { viewModel.dismissBtw() },
            )
        }

        viewingImage?.let { image ->
            ImageViewerDialog(
                image = image,
                onDismiss = { viewingImage = null },
            )
        }

        connectionOperationState.operation?.let { operation ->
            ConnectionSetupSheet(
                operation = operation,
                onRespond = viewModel::respondToConnection,
                onContinue = viewModel::continueConnectionOperation,
                onOpenBrowser = {
                    operation.targets.firstOrNull()?.connectUrl?.let { url ->
                        if (ConnectorUrlValidator.isValidHttpsUrl(url)) {
                            launchExternalActivity {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                            viewModel.wakeConnectionOperation()
                        }
                    }
                },
                onDismiss = viewModel::continueConnectionOperation,
            )
        }

        SessionIntegrationsSheet(
            uiState = connectorsState,
            sessionId = connectorsState.sessionId,
            onDismiss = connectorsViewModel::hide,
            onRefresh = { connectorsViewModel.refresh(force = true) },
            onConnect = { slug, reconnect -> connectorsViewModel.connect(slug, reconnect) },
            onClearError = connectorsViewModel::clearError,
        )
    }
}
