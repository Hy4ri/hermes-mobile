package com.m57.hermescontrol.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.components.ChatConnectionBanner
import com.m57.hermescontrol.ui.chat.components.ChatInputBar
import com.m57.hermescontrol.ui.chat.components.ChatLifecycleEffects
import com.m57.hermescontrol.ui.chat.components.ChatLoadingOverlay
import com.m57.hermescontrol.ui.chat.components.ChatMessageList
import com.m57.hermescontrol.ui.chat.components.ChatScrollToBottomFab
import com.m57.hermescontrol.ui.chat.components.ReactionHeartsOverlay
import com.m57.hermescontrol.ui.chat.components.ReloginDialog
import com.m57.hermescontrol.ui.chat.components.SearchBarRow
import com.m57.hermescontrol.ui.chat.components.rememberChatScrollController
import com.m57.hermescontrol.ui.chat.components.tailContentKey
import com.m57.hermescontrol.ui.common.AutoScrollingTitleText
import com.m57.hermescontrol.ui.common.CredentialWarningBanner
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.model.components.ModelPickerDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SESSION_SYNC_INTERVAL_MS = 5_000L

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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    sessionId: String? = null,
    viewModel: ChatViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val streamingState by viewModel.streamingState.collectAsStateWithLifecycle()
    val credentialWarning by HermesWsClient.credentialWarning.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val scrollController = rememberChatScrollController(listState, scrollScope)
    var isOlderPagingArmed by remember(state.currentSessionId) { mutableStateOf(false) }

    // Periodic session sync while connected.
    LaunchedEffect(state.currentSessionId, state.connectionStatus) {
        while (state.currentSessionId != null && state.connectionStatus == ConnectionStatus.CONNECTED) {
            delay(SESSION_SYNC_INTERVAL_MS)
            viewModel.syncCurrentSession()
        }
    }

    // Arm + trigger older-message paging when the user scrolls near the top.
    // Before prepending, capture the anchor (first visible message id + offset)
    // so the same content stays under the reader's eye after the insert
    // (issue #682). Restored in a LaunchedEffect once the page actually lands.
    val pagingAnchor = remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(listState, state.currentSessionId, state.hasOlderMessages, state.isLoadingOlder) {
        if (!state.hasOlderMessages || state.isLoadingOlder) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collectLatest { firstVisibleIndex ->
                if (firstVisibleIndex > 2) {
                    isOlderPagingArmed = true
                } else if (isOlderPagingArmed) {
                    val anchorId = state.messages.getOrNull(firstVisibleIndex)?.id
                    val offset = scrollController.captureAnchorOffset()
                    pagingAnchor.value = if (anchorId != null) anchorId to offset else null
                    viewModel.loadOlderMessages()
                }
            }
    }

    // After older history is prepended, restore the anchor message (by id, so
    // streaming-token inserts during paging don't skew the index) plus offset.
    LaunchedEffect(state.messages) {
        val anchor = pagingAnchor.value ?: return@LaunchedEffect
        val (anchorId, offset) = anchor
        val index = state.messages.indexOfFirst { it.id == anchorId }
        if (index >= 0) {
            scrollController.scrollToItem(index, offset)
            pagingAnchor.value = null
        }
    }

    // Continuous bottom-follow tracking from LazyListState (issue #682).
    LaunchedEffect(Unit) {
        scrollController.observeUserScrollPosition()
    }

    // Drive follow + unseen tracking from a stable tail-content key covering
    // messages, streaming, thinking, subagent cards, and clarify prompts
    // (issue #682). Replaces the old item-count heuristic that ignored the
    // streaming tail.
    LaunchedEffect(
        state.messages,
        streamingState.streamingMessage,
        streamingState.isThinking,
        state.subagentIndicators,
        state.clarifyRequest,
    ) {
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
    val showScrollToBottom by remember {
        derivedStateOf {
            scrollController.showFab(state.messages.isNotEmpty())
        }
    }
    var inputFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var isListening by rememberSaveable { mutableStateOf(false) }
    var lastAnimatedMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var showReloginDialog by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current

    val micListeningPrompt = stringResource(R.string.chat_mic_listening)
    val sttNotAvailableMsg = stringResource(R.string.stt_not_available)
    val sttPermissionDeniedMsg = stringResource(R.string.stt_permission_denied)

    // Speech-to-text recognition launcher (issue #194)
    val speechLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            isListening = false
            if (result.resultCode == Activity.RESULT_OK) {
                val spokenText =
                    result.data
                        ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull()
                        .orEmpty()
                if (spokenText.isNotBlank()) {
                    val merged =
                        if (inputFieldValue.text.isBlank()) {
                            spokenText
                        } else {
                            "${inputFieldValue.text} $spokenText"
                        }
                    inputFieldValue = ChatInputPolicy.commandFieldValue(merged)
                }
            }
        }

    // Mic permission launcher
    val micPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    val intent =
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                            putExtra(
                                RecognizerIntent.EXTRA_PROMPT,
                                micListeningPrompt,
                            )
                        }
                    isListening = true
                    speechLauncher.launch(intent)
                } else {
                    scrollScope.launch {
                        snackbarHostState.showSnackbar(sttNotAvailableMsg)
                    }
                }
            } else {
                scrollScope.launch {
                    snackbarHostState.showSnackbar(sttPermissionDeniedMsg)
                }
            }
        }

    // File picker launcher for attachments (issue #195)
    val filePickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            if (uri != null) {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        val name = if (nameIdx >= 0) c.getString(nameIdx) else uri.lastPathSegment ?: "file"
                        val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                        viewModel.addAttachment(uri.toString(), name, mimeType, size)
                    }
                }
            }
        }

    // Camera photo launcher (issue #195)
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraErrorMsg = stringResource(R.string.chat_camera_error)
    val cameraLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { success ->
            val uri = pendingCameraUri
            pendingCameraUri = null
            if (success && uri != null) {
                try {
                    val fileName =
                        "photo_${
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(
                                Date(),
                            )
                        }.jpg"
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val size = inputStream?.use { it.available().toLong() } ?: 0L
                    viewModel.addAttachment(uri.toString(), fileName, "image/jpeg", size)
                } catch (e: Exception) {
                    Log.e("ChatScreen", "Camera capture failed", e)
                    scrollScope.launch {
                        snackbarHostState.showSnackbar(
                            cameraErrorMsg,
                        )
                    }
                }
            }
        }

    // Lifecycle effects, permissions, session switching, auto-scroll, errors
    ChatLifecycleEffects(
        sessionId = sessionId,
        connectionStatus = state.connectionStatus,
        currentSessionId = state.currentSessionId,
        messages = state.messages,
        errorMessage = state.errorMessage,
        backgroundCompleteMessage = state.backgroundCompleteMessage,
        isSearchActive = state.isSearchActive,
        currentSearchMatchIndex = state.currentSearchMatchIndex,
        searchMatchIndices = state.searchMatchIndices,
        clarifyRequest = state.clarifyRequest,
        sudoPrompt = state.sudoPrompt,
        secretPrompt = state.secretPrompt,
        listState = listState,
        scrollController = scrollController,
        snackbarHostState = snackbarHostState,
        viewModel = viewModel,
    )

    val backgroundGradient =
        Brush.verticalGradient(
            colors =
                listOf(
                    MaterialTheme.colorScheme.background,
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                ),
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
            }
        },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        actions = {
            // Search toggle
            IconButton(onClick = { viewModel.toggleSearch() }) {
                Icon(
                    imageVector =
                        if (state.isSearchActive) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription =
                        if (state.isSearchActive) {
                            stringResource(
                                R.string.chat_action_close_search,
                            )
                        } else {
                            stringResource(R.string.chat_action_search)
                        },
                )
            }

            IconButton(onClick = { viewModel.createNewSession() }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.content_desc_new_chat),
                )
            }
        },
    ) { _ ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(backgroundGradient)
                    .imePadding(),
        ) {
            ChatConnectionBanner(
                connectionStatus = state.connectionStatus,
                onReconnect = viewModel::reconnect,
                onReloginClick = { showReloginDialog = true },
            )

            credentialWarning?.let { warning ->
                CredentialWarningBanner(
                    warning = warning,
                    onFix = { NavigationController.navigateTo(com.m57.hermescontrol.ProvidersScreen) },
                    onDismiss = { HermesWsClient.clearCredentialWarning() },
                )
            }

            AnimatedVisibility(
                visible = state.isSearchActive,
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
                            searchQuery = state.searchQuery,
                            onQueryChange = { viewModel.setSearchQuery(it) },
                            searchMatchCount = state.searchMatchIndices.size,
                            currentMatchIndex = state.currentSearchMatchIndex,
                            onNavigateUp = { viewModel.navigateSearchMatch(-1) },
                            onNavigateDown = { viewModel.navigateSearchMatch(1) },
                            onClose = { viewModel.clearSearch() },
                        )
                    }
                }
            }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                ChatMessageList(
                    messages = state.messages,
                    streamingMessage = streamingState.streamingMessage,
                    isThinking = streamingState.isThinking,
                    thinkingText = streamingState.thinkingText,
                    isSearchActive = state.isSearchActive,
                    searchQuery = state.searchQuery,
                    currentSearchMatchIndex = state.currentSearchMatchIndex,
                    searchMatchIndices = state.searchMatchIndices,
                    typingEffectEnabled = state.typingEffectEnabled,
                    typingEffectDelayMs = state.typingEffectDelayMs,
                    isLoading = state.isLoading,
                    isLoadingOlder = state.isLoadingOlder,
                    isDark = isDark,
                    listState = listState,
                    lastAnimatedMessageId = lastAnimatedMessageId,
                    onLastAnimatedMessageIdChange = { lastAnimatedMessageId = it },
                    viewModel = viewModel,
                    subagentIndicators = state.subagentIndicators,
                    clarifyRequest = state.clarifyRequest,
                    onRespondClarify = viewModel::respondToClarify,
                    onDismissClarify = viewModel::dismissClarify,
                )

                // Loading overlay
                ChatLoadingOverlay(isLoading = state.isLoading)

                // Scroll-to-bottom FAB (issue #682): shows while follow is
                // paused and renders the unseen-message badge.
                ChatScrollToBottomFab(
                    show = showScrollToBottom,
                    pendingCount = scrollController.pendingCount,
                    onScrollToBottom = scrollController::resumeFollowing,
                )

                // Reaction heartsanimation (purely cosmetic — fades out
                // automatically after the ViewModel clears the state)
                key(state.reactionTriggerId) {
                    ReactionHeartsOverlay(
                        reactionKind = state.reactionKind,
                    )
                }
            }

            ChatInputBar(
                inputFieldValue = inputFieldValue,
                onInputChange = { inputFieldValue = it },
                onSend = {
                    viewModel.sendMessage(inputFieldValue.text)
                    inputFieldValue = TextFieldValue("")
                    // Jump to bottom after send (serialized through the controller).
                    scrollController.jumpToBottom(animated = true)
                },
                onMicTap = {
                    if (isListening) {
                        isListening = false
                    } else if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        if (SpeechRecognizer.isRecognitionAvailable(context)) {
                            val intent =
                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                    )
                                    putExtra(
                                        RecognizerIntent.EXTRA_PROMPT,
                                        micListeningPrompt,
                                    )
                                }
                            isListening = true
                            speechLauncher.launch(intent)
                        } else {
                            scrollScope.launch {
                                snackbarHostState.showSnackbar(sttNotAvailableMsg)
                            }
                        }
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                isListening = isListening,
                isAgentTyping = state.isAgentTyping,
                isConnected = state.isConnected,
                commandCatalog = state.commandCatalog,
                pendingAttachments = state.pendingAttachments,
                onCameraTap = {
                    try {
                        val timeStamp =
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        val photoFile =
                            File.createTempFile("camera_${timeStamp}_", ".jpg", context.cacheDir)
                        val uri =
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                photoFile,
                            )
                        pendingCameraUri = uri
                        cameraLauncher.launch(uri)
                    } catch (e: Exception) {
                        Log.e("ChatScreen", "Camera launch failed", e)
                    }
                },
                onImageTap = { filePickerLauncher.launch("image/*") },
                onFileTap = { filePickerLauncher.launch("*/*") },
                onRemoveAttachment = viewModel::removeAttachment,
                // Composer toolbar wiring (PR 1)
                currentSessionModel = state.currentSessionModel,
                reasoningLevel = state.reasoningLevel,
                onModelTap = { viewModel.openModelPicker() },
                onReasoningTap = { level -> viewModel.setReasoningLevel(level) },
            )
        }

        if (showReloginDialog) {
            ReloginDialog(
                onDismiss = { showReloginDialog = false },
                onRelogin = { username, password, onResult ->
                    viewModel.relogin(username, password, onResult)
                },
            )
        }

        // In-session model picker (issue #589) — opens on "/model".
        if (state.showModelPicker) {
            ModelPickerDialog(
                providers = state.modelPickerProviders,
                title = "Switch model (this chat)",
                isLoading = state.modelPickerLoading && state.modelPickerProviders.isEmpty(),
                pinnedModels = state.modelPickerPinned,
                onPinToggle = { provider, model -> viewModel.togglePinModel(provider, model) },
                onSelect = { provider, model ->
                    viewModel.sendSlashModel(provider, model)
                },
                onDismiss = { viewModel.closeModelPicker() },
            )
        }
    }
}
