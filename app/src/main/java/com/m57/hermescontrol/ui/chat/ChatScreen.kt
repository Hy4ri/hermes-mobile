package com.m57.hermescontrol.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.notification.NotificationHelper
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.StatusRed
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val listState = rememberLazyListState()
    val showScrollToBottom by remember {
        derivedStateOf {
            state.messages.isNotEmpty() && listState.canScrollForward
        }
    }
    val scrollScope = rememberCoroutineScope()
    var inputText by rememberSaveable { mutableStateOf("") }
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
                    inputText =
                        if (inputText.isBlank()) {
                            spokenText
                        } else {
                            "$inputText $spokenText"
                        }
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
        streamingMessage = streamingState.streamingMessage,
        isThinking = streamingState.isThinking,
        errorMessage = state.errorMessage,
        backgroundCompleteMessage = state.backgroundCompleteMessage,
        isSearchActive = state.isSearchActive,
        currentSearchMatchIndex = state.currentSearchMatchIndex,
        searchMatchIndices = state.searchMatchIndices,
        clarifyRequest = state.clarifyRequest,
        sudoPrompt = state.sudoPrompt,
        secretPrompt = state.secretPrompt,
        listState = listState,
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
                Text(
                    text = state.chatTitle,
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Connection status dot — red when offline, hidden when connected
                if (!state.isConnected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier =
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(StatusRed),
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
            ChatTopBanner(
                connectionStatus = state.connectionStatus,
                onReconnect = viewModel::reconnect,
                onReloginClick = { showReloginDialog = true },
            )

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
                    isDark = isDark,
                    listState = listState,
                    lastAnimatedMessageId = lastAnimatedMessageId,
                    onLastAnimatedMessageIdChange = { lastAnimatedMessageId = it },
                    hasReachedOldest = state.hasReachedOldest,
                    isLoadingOlder = state.isLoadingOlder,
                    currentOffset = state.currentOffset,
                    onLoadOlderMessages = { viewModel.loadOlderMessages() },
                    viewModel = viewModel,
                )

                // Loading overlay
                ChatLoadingOverlay(isLoading = state.isLoading)

                // Scroll-to-bottom FAB
                ChatScrollToBottomFab(
                    showScrollToBottom = showScrollToBottom,
                    scrollScope = scrollScope,
                    listState = listState,
                    messages = state.messages,
                    streamingMessage = streamingState.streamingMessage,
                    isThinking = streamingState.isThinking,
                )

                // Reaction hearts animation (purely cosmetic — fades out
                // automatically after the ViewModel clears the state)
                key(state.reactionTriggerId) {
                    ReactionHeartsOverlay(
                        reactionKind = state.reactionKind,
                    )
                }
            }

            ChatInputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                onSend = {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                    scrollScope.launch {
                        val totalItems =
                            state.messages.size +
                                (if (streamingState.streamingMessage != null) 1 else 0) +
                                (if (streamingState.isThinking) 1 else 0)
                        if (totalItems > 0) {
                            listState.animateScrollToItem(totalItems - 1)
                        }
                    }
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
    }
}

@Composable
private fun ThinkingIndicator(thinkingText: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "thinking_alpha",
    )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = LocalHermesStatusColors.current.infoContainer.copy(alpha = 0.5f),
                ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "🤔",
                    fontSize = 14.sp,
                    modifier = Modifier.graphicsLayer { this.alpha = alpha },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text =
                        if (thinkingText.isNotBlank()) {
                            val display = thinkingText.takeLast(100)
                            stringResource(R.string.chat_thinking_param, display)
                        } else {
                            stringResource(R.string.chat_thinking)
                        },
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = LocalHermesStatusColors.current.info,
                        ),
                    maxLines = 2,
                    modifier = Modifier.animateContentSize(),
                )
            }
        }
    }
}

@Composable
private fun ReasoningIndicator(reasoningText: String) {
    var expanded by remember { mutableStateOf(false) }
    val statusColors = LocalHermesStatusColors.current
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clickable(
                    role = Role.Button,
                    onClick = { expanded = !expanded },
                ),
        colors =
            CardDefaults.cardColors(
                containerColor = statusColors.infoContainer.copy(alpha = 0.5f),
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = stringResource(R.string.chat_reasoning),
                    tint = statusColors.info,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.chat_reasoning),
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColors.info,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription =
                        stringResource(
                            if (expanded) R.string.chat_reasoning_collapse else R.string.chat_reasoning_expand,
                        ),
                    tint = statusColors.info,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = reasoningText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColors.info,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicTap: () -> Unit,
    isListening: Boolean,
    isAgentTyping: Boolean,
    isConnected: Boolean,
    commandCatalog: CommandCatalog,
    pendingAttachments: List<Attachment> = emptyList(),
    onCameraTap: () -> Unit = {},
    onImageTap: () -> Unit = {},
    onFileTap: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
) {
    // Allow sending slash commands even while agent is typing
    val isSlashCommand = inputText.startsWith("/")
    val canSend =
        (inputText.isNotBlank() || pendingAttachments.isNotEmpty()) &&
            isConnected && (!isAgentTyping || isSlashCommand)

    // Attachment menu state
    var showAttachmentMenu by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            shape = RoundedCornerShape(24.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                ),
            border =
                BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column {
                // Build command list from dynamic catalog (pairs = [name, description])
                val allCommands = commandCatalog.pairs.associate { (name, desc) -> name to desc }
                // Commands hidden from suggestion menu (still work when manually typed)
                val hiddenSlashDisplay = setOf("/stop", "/interrupt")
                val commandNames = allCommands.keys.filter { it !in hiddenSlashDisplay }

                androidx.compose.animation.AnimatedVisibility(
                    visible = inputText.startsWith("/") && !inputText.contains(" "),
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
                ) {
                    val filteredCommands = commandNames.filter { it.startsWith(inputText, ignoreCase = true) }
                    if (filteredCommands.isNotEmpty()) {
                        androidx.compose.material3.Surface(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border =
                                androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                ),
                        ) {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 200.dp),
                            ) {
                                items(filteredCommands, key = { it }) { cmd ->
                                    val description = allCommands[cmd] ?: ""
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Text(
                                                    cmd,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                                Text(
                                                    description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = { onInputChange(cmd) },
                                    )
                                }
                            }
                        }
                    }
                }

                // Attachment preview chips
                AnimatedVisibility(
                    visible = pendingAttachments.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pendingAttachments.forEachIndexed { index, attachment ->
                            AttachmentChip(
                                attachment = attachment,
                                onRemove = { onRemoveAttachment(index) },
                            )
                        }
                    }
                }

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        // Single attach button that opens a dropdown menu
                        IconButton(
                            onClick = { showAttachmentMenu = true },
                            enabled = isConnected,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = stringResource(R.string.chat_attach_desc),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        DropdownMenu(
                            expanded = showAttachmentMenu,
                            onDismissRequest = { showAttachmentMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Camera") },
                                onClick = {
                                    showAttachmentMenu = false
                                    onCameraTap()
                                },
                                leadingIcon = {
                                    Text(
                                        text = "📷",
                                        fontSize = 18.sp,
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Image") },
                                onClick = {
                                    showAttachmentMenu = false
                                    onImageTap()
                                },
                                leadingIcon = {
                                    Text(
                                        text = "🖼️",
                                        fontSize = 18.sp,
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("File") },
                                onClick = {
                                    showAttachmentMenu = false
                                    onFileTap()
                                },
                                leadingIcon = {
                                    Text(
                                        text = "📄",
                                        fontSize = 18.sp,
                                    )
                                },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp, max = 120.dp)
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                                .testTag("chat_input"),
                        placeholder = {
                            Text(
                                if (!isConnected) {
                                    stringResource(R.string.chat_input_placeholder_not_connected)
                                } else if (isAgentTyping) {
                                    stringResource(R.string.chat_input_placeholder_waiting)
                                } else {
                                    stringResource(R.string.chat_input_placeholder_type_message)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        enabled = isConnected,
                        singleLine = false,
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                cursorColor = MaterialTheme.colorScheme.primary,
                            ),
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // Mic / Stop / Send button — swaps based on input state
                    AnimatedContent(
                        targetState =
                            when {
                                isListening -> "listening"
                                inputText.isBlank() && pendingAttachments.isEmpty() -> "mic"
                                else -> "send"
                            },
                        transitionSpec = {
                            (scaleIn(initialScale = 0.8f) + fadeIn())
                                .togetherWith(scaleOut(targetScale = 0.8f) + fadeOut())
                        },
                        label = "mic_send_toggle",
                    ) { buttonState ->
                        when (buttonState) {
                            "mic" -> {
                                FilledTonalButton(
                                    onClick = onMicTap,
                                    enabled = isConnected,
                                    modifier =
                                        Modifier
                                            .size(40.dp)
                                            .testTag("mic_button"),
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = stringResource(R.string.chat_mic_desc),
                                    )
                                }
                            }

                            "listening" -> {
                                FilledTonalButton(
                                    onClick = onMicTap,
                                    modifier =
                                        Modifier
                                            .size(40.dp)
                                            .testTag("mic_stop_button"),
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(0.dp),
                                    colors =
                                        ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                        ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = "Stop listening",
                                        tint = MaterialTheme.colorScheme.onError,
                                    )
                                }
                            }

                            else -> {
                                FilledTonalButton(
                                    onClick = onSend,
                                    enabled = canSend,
                                    modifier =
                                        Modifier
                                            .size(40.dp)
                                            .testTag("send_button"),
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = stringResource(R.string.chat_send_desc),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact chip showing a pending attachment with a remove button.
 */
@Composable
private fun AttachmentChip(
    attachment: Attachment,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (attachment.isImage) {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = attachment.name,
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(18.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.chat_attach_remove_desc),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClarifyDialog(
    clarify: ClarifyUi,
    onOptionSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var typedText by rememberSaveable(clarify.text) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_clarify_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = clarify.text)

                if (clarify.options.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.chat_clarify_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    clarify.options.forEach { option ->
                        Surface(
                            onClick = { typedText = option },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("clarify_option"),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .combinedClickable(
                                            role = Role.Button,
                                            onClick = { typedText = option },
                                            onLongClick = { onOptionSelected(option) },
                                        ).padding(horizontal = 24.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = option,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = typedText,
                    onValueChange = { typedText = it },
                    label = { Text(stringResource(R.string.chat_clarify_response)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (typedText.isNotBlank()) {
                        onOptionSelected(typedText)
                    }
                },
                enabled = typedText.isNotBlank(),
            ) {
                Text(stringResource(R.string.chat_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_dismiss))
            }
        },
    )
}

/**
 * Secure password dialog for a pending `sudo.request` (issue #524).
 * The backend blocked the turn waiting for the sudo password — previously
 * mobile dropped the event and the agent hung forever.
 */
@Composable
private fun SudoPromptDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_sudo_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.chat_sudo_body))
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.chat_sudo_password)) },
                    modifier = Modifier.fillMaxWidth().testTag("sudo_password_input"),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (password.isNotBlank()) {
                        onConfirm(password)
                    }
                },
                enabled = password.isNotBlank(),
            ) {
                Text(stringResource(R.string.chat_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_dismiss))
            }
        },
    )
}

/**
 * Secure value dialog for a pending `secret.request` (issue #524).
 * The backend blocked the turn waiting for a secret (token/password) —
 * previously mobile dropped the event and the agent hung forever.
 */
@Composable
private fun SecretPromptDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var secret by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_secret_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.chat_secret_body))
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(stringResource(R.string.chat_secret_value)) },
                    modifier = Modifier.fillMaxWidth().testTag("secret_value_input"),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (secret.isNotBlank()) {
                        onConfirm(secret)
                    }
                },
                enabled = secret.isNotBlank(),
            ) {
                Text(stringResource(R.string.chat_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_dismiss))
            }
        },
    )
}

@Composable
private fun CompactSearchInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val errorColor = MaterialTheme.colorScheme.error
    val outlineColor = if (isError) errorColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .height(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(20.dp),
                ).border(
                    width = 1.dp,
                    color = outlineColor,
                    shape = RoundedCornerShape(20.dp),
                ).padding(horizontal = 12.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        decorationBox = { innerTextField ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_search_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    IconButton(
                        onClick = { onValueChange("") },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.chat_clear_search_desc),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun SearchBarRow(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    searchMatchCount: Int,
    currentMatchIndex: Int,
    onNavigateUp: () -> Unit,
    onNavigateDown: () -> Unit,
    onClose: () -> Unit,
) {
    val isError = searchQuery.isNotEmpty() && searchMatchCount == 0

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactSearchInput(
            value = searchQuery,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            isError = isError,
        )
        if (searchQuery.isNotEmpty()) {
            val countText =
                if (searchMatchCount > 0) {
                    "${currentMatchIndex + 1}/$searchMatchCount"
                } else {
                    "0/0"
                }
            Text(
                text = countText,
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        color =
                            if (isError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    ),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        IconButton(
            onClick = onNavigateUp,
            enabled = searchMatchCount > 0,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.chat_prev_match_desc),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(
            onClick = onNavigateDown,
            enabled = searchMatchCount > 0,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.chat_next_match_desc),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.chat_close_search_desc),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Wraps a streaming [ChatBubble] with a word-by-word typing reveal effect.
 * Shows words one at a time at [typingDelayMs] intervals while the message is
 * still streaming. When streaming completes the full text is shown immediately.
 * The underlying [ChatMessage.content] in state is never modified — this is a
 * display-only transformation.
 */
@Composable
private fun StreamingBubbleWithTypingEffect(
    streaming: ChatMessage,
    typingDelayMs: Int,
    isDark: Boolean,
    onAnimationComplete: () -> Unit = {},
) {
    var visibleWordCount by remember { mutableIntStateOf(0) }
    val currentContent = rememberUpdatedState(streaming.content)
    val currentIsStreaming = rememberUpdatedState(streaming.isStreaming)
    val currentDelayMs = rememberUpdatedState(typingDelayMs)

    // Timer that ticks at the configured delay, incrementing the visible word
    // count each tick. Stops ticking when streaming ends, then shows all words.
    // Optimized: split is only called when waiting for new content, not per tick.
    LaunchedEffect(Unit) {
        var wordCount = 0
        while (true) {
            if (visibleWordCount < wordCount) {
                delay(currentDelayMs.value.toLong())
                visibleWordCount++
            } else {
                if (!currentIsStreaming.value) {
                    onAnimationComplete()
                    break
                }
                // Only split when we need to check for new content arriving
                val words = currentContent.value.split(" ")
                wordCount = words.size
                if (visibleWordCount < wordCount) continue
                delay(100) // was 10 — reduced from 100Hz to 10Hz
            }
        }
        visibleWordCount = Int.MAX_VALUE
    }

    // Derive display text from the latest full content at each recomposition
    val words = streaming.content.split(" ")
    val visibleCount =
        if (visibleWordCount >= Int.MAX_VALUE / 2) {
            words.size
        } else {
            visibleWordCount.coerceIn(0, words.size)
        }
    val displayText = words.take(visibleCount.coerceAtLeast(1)).joinToString(" ")

    ChatBubble(
        message = streaming.copy(content = displayText),
        isDarkTheme = isDark,
        searchQuery = "",
        isCurrentMatch = false,
    )
}

private fun LazyListState.isAtBottom(threshold: Int = 3): Boolean {
    val layoutInfo = this.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return true
    val lastVisibleItem = visibleItems.last()
    return lastVisibleItem.index >= layoutInfo.totalItemsCount - threshold
}

@Composable
private fun ChatLifecycleEffects(
    sessionId: String?,
    connectionStatus: ConnectionStatus,
    currentSessionId: String?,
    messages: List<ChatMessage>,
    streamingMessage: ChatMessage?,
    isThinking: Boolean,
    errorMessage: String?,
    backgroundCompleteMessage: String?,
    isSearchActive: Boolean,
    currentSearchMatchIndex: Int,
    searchMatchIndices: List<Int>,
    clarifyRequest: ClarifyUi?,
    sudoPrompt: SudoPromptUi?,
    secretPrompt: SecretPromptUi?,
    listState: LazyListState,
    snackbarHostState: SnackbarHostState,
    viewModel: ChatViewModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Publish the notification session ID to the ViewModel synchronously
    SideEffect {
        viewModel.initialSessionId = sessionId
    }

    // Switch to session from notification/history
    var lastSessionId by remember { mutableStateOf<String?>(null) }
    val pendingSessionId = NavigationController.pendingSessionId
    LaunchedEffect(sessionId, pendingSessionId, connectionStatus) {
        if (connectionStatus != ConnectionStatus.CONNECTED) return@LaunchedEffect
        val target = if (!sessionId.isNullOrBlank()) sessionId else pendingSessionId
        if (!target.isNullOrBlank()) {
            viewModel.switchSession(target)
            if (target == pendingSessionId) {
                NavigationController.pendingSessionId = null
            }
        }
    }

    // Lifecycle observer for notification foreground service
    DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_START -> {
                        NotificationHelper.setAppForeground(context, true)
                        NotificationHelper.stop(context)
                        viewModel.refreshSettings()
                        viewModel.refreshCurrentSession()
                    }

                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                        NotificationHelper.setAppForeground(context, false)
                        NotificationHelper.start(context)
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Request POST_NOTIFICATIONS permission on Android 13+
    val requestNotificationPermission =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { /* granted */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(context, permission) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(permission)
            }
        }
    }

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size, streamingMessage?.content?.length, isThinking) {
        val totalItems =
            messages.size +
                (if (streamingMessage != null) 1 else 0) +
                (if (isThinking) 1 else 0)
        if (totalItems > 0) {
            val isSessionSwitch = currentSessionId != lastSessionId
            if (isSessionSwitch) {
                lastSessionId = currentSessionId
                listState.scrollToItem(totalItems - 1)
            } else if (listState.isAtBottom()) {
                listState.animateScrollToItem(totalItems - 1)
            }
        }
    }

    // Show error as snackbar
    LaunchedEffect(errorMessage) {
        errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Show background-complete as a non-blocking snackbar (issue #527)
    LaunchedEffect(backgroundCompleteMessage) {
        backgroundCompleteMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearBackgroundComplete()
        }
    }

    // Clarify dialog
    clarifyRequest?.let { clarify ->
        ClarifyDialog(
            clarify = clarify,
            onOptionSelected = viewModel::respondToClarify,
            onDismiss = viewModel::dismissClarify,
        )
    }

    // Sudo / secret prompt dialogs (issue #524)
    sudoPrompt?.let { prompt ->
        SudoPromptDialog(
            onConfirm = viewModel::respondToSudo,
            onDismiss = viewModel::dismissSudo,
        )
    }

    secretPrompt?.let { prompt ->
        SecretPromptDialog(
            onConfirm = viewModel::respondToSecret,
            onDismiss = viewModel::dismissSecret,
        )
    }

    // Scroll to current search match
    LaunchedEffect(isSearchActive, currentSearchMatchIndex, searchMatchIndices) {
        if (isSearchActive &&
            currentSearchMatchIndex >= 0 &&
            currentSearchMatchIndex < searchMatchIndices.size &&
            messages.isNotEmpty()
        ) {
            val targetIndex = searchMatchIndices[currentSearchMatchIndex]
            listState.animateScrollToItem(
                targetIndex.coerceIn(0, messages.lastIndex),
            )
        }
    }
}

@Composable
private fun ChatTopBanner(
    connectionStatus: ConnectionStatus,
    onReconnect: () -> Unit,
    onReloginClick: () -> Unit,
) {
    val isShown =
        connectionStatus != ConnectionStatus.CONNECTED &&
            connectionStatus != ConnectionStatus.CONNECTING
    androidx.compose.animation.AnimatedVisibility(
        visible = isShown,
        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth(),
            color = LocalHermesStatusColors.current.errorContainer,
            contentColor = LocalHermesStatusColors.current.error,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (connectionStatus == ConnectionStatus.RECONNECTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = LocalHermesStatusColors.current.error,
                        )
                    }
                    Text(
                        text =
                            when (connectionStatus) {
                                ConnectionStatus.RECONNECTING -> stringResource(R.string.chat_status_reconnecting)
                                ConnectionStatus.DISCONNECTED -> stringResource(R.string.chat_status_disconnected)
                                ConnectionStatus.NO_NETWORK -> stringResource(R.string.chat_status_no_network)
                                ConnectionStatus.AUTH_EXPIRED -> stringResource(R.string.chat_status_auth_expired)
                                else -> ""
                            },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                when (connectionStatus) {
                    ConnectionStatus.DISCONNECTED -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = onReloginClick,
                                colors =
                                    androidx.compose.material3.ButtonDefaults.textButtonColors(
                                        contentColor = LocalHermesStatusColors.current.error,
                                    ),
                            ) {
                                Text(stringResource(R.string.chat_action_relogin))
                            }
                            TextButton(
                                onClick = onReconnect,
                                colors =
                                    androidx.compose.material3.ButtonDefaults.textButtonColors(
                                        contentColor = LocalHermesStatusColors.current.error,
                                    ),
                            ) {
                                Text(stringResource(R.string.chat_action_reconnect))
                            }
                        }
                    }

                    ConnectionStatus.NO_NETWORK -> {
                        TextButton(
                            onClick = onReconnect,
                            colors =
                                androidx.compose.material3.ButtonDefaults.textButtonColors(
                                    contentColor = LocalHermesStatusColors.current.error,
                                ),
                        ) {
                            Text(stringResource(R.string.chat_action_reconnect))
                        }
                    }

                    ConnectionStatus.RECONNECTING,
                    ConnectionStatus.AUTH_EXPIRED,
                    -> {
                        TextButton(
                            onClick = onReloginClick,
                            colors =
                                androidx.compose.material3.ButtonDefaults.textButtonColors(
                                    contentColor = LocalHermesStatusColors.current.error,
                                ),
                        ) {
                            Text(stringResource(R.string.chat_action_relogin))
                        }
                    }

                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun ChatMessageList(
    messages: List<ChatMessage>,
    streamingMessage: ChatMessage?,
    isThinking: Boolean,
    thinkingText: String,
    isSearchActive: Boolean,
    searchQuery: String,
    currentSearchMatchIndex: Int,
    searchMatchIndices: List<Int>,
    typingEffectEnabled: Boolean,
    typingEffectDelayMs: Int,
    isLoading: Boolean,
    isDark: Boolean,
    listState: LazyListState,
    lastAnimatedMessageId: String?,
    onLastAnimatedMessageIdChange: (String?) -> Unit,
    hasReachedOldest: Boolean,
    isLoadingOlder: Boolean,
    currentOffset: Int,
    onLoadOlderMessages: () -> Unit,
    viewModel: ChatViewModel,
) {
    // Lay children out vertically so the search bar occupies real layout space
    // ABOVE the message list. Without this container the call site is a Box,
    // which overlays the LazyColumn on top of the search AnimatedVisibility and
    // swallows every tap on the bar (bar visible but not clickable).
    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar
        androidx.compose.animation.AnimatedVisibility(
            visible = isSearchActive,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
        ) {
            androidx.compose.material3.Surface(
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
                        searchQuery = searchQuery,
                        onQueryChange = { viewModel.setSearchQuery(it) },
                        searchMatchCount = searchMatchIndices.size,
                        currentMatchIndex = currentSearchMatchIndex,
                        onNavigateUp = { viewModel.navigateSearchMatch(-1) },
                        onNavigateDown = { viewModel.navigateSearchMatch(1) },
                        onClose = { viewModel.clearSearch() },
                    )
                }
            }
        }

        if (messages.isEmpty() && !isLoading) {
            EmptyState(
                title = stringResource(R.string.chat_empty_title),
                subtitle = stringResource(R.string.chat_empty_subtitle),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // ── Load-older header (issue #551) ──
            // Spinner while fetching an older page; hidden once the oldest
            // page is reached (currentOffset == 0).
            if (currentOffset > 0 && !hasReachedOldest) {
                item(key = "load_older_header") {
                    // Trigger the older-page load once when this header scrolls
                    // into view (enters composition). Using LaunchedEffect(Unit)
                    // means it fires exactly once per composition — on success
                    // the prepended page removes the header (stable keys preserve
                    // scroll); on failure it does NOT auto-retry (avoiding an
                    // infinite failing-request loop). The user can scroll away
                    // and back to retry.
                    LaunchedEffect(Unit) {
                        onLoadOlderMessages()
                    }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isLoadingOlder) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                text = "↑ Load earlier messages",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            itemsIndexed(
                items = messages,
                key = { _, message -> message.id },
            ) { index, message ->
                val isCurrentMatch =
                    isSearchActive &&
                        currentSearchMatchIndex >= 0 &&
                        currentSearchMatchIndex < searchMatchIndices.size &&
                        searchMatchIndices[currentSearchMatchIndex] == index

                val isLastMessage = index == messages.lastIndex
                val isAssistant = message.role == MessageRole.ASSISTANT

                if (isAssistant && message.reasoningText.isNotBlank()) {
                    ReasoningIndicator(message.reasoningText)
                }

                if (typingEffectEnabled && isLastMessage && isAssistant && message.isStreaming &&
                    lastAnimatedMessageId != message.id
                ) {
                    StreamingBubbleWithTypingEffect(
                        streaming = message,
                        typingDelayMs = typingEffectDelayMs,
                        isDark = isDark,
                        onAnimationComplete = {
                            onLastAnimatedMessageIdChange(message.id)
                        },
                    )
                } else {
                    ChatBubble(
                        message = message,
                        isDarkTheme = isDark,
                        searchQuery = if (isSearchActive) searchQuery else "",
                        isCurrentMatch = isCurrentMatch,
                        onRespondApproval = viewModel::respondToApproval,
                    )
                }
            }

            // Streaming message
            streamingMessage?.let { streaming ->
                item(key = "streaming-${streaming.id}") {
                    if (streaming.reasoningText.isNotBlank()) {
                        ReasoningIndicator(streaming.reasoningText)
                    }
                    if (typingEffectEnabled && streaming.isStreaming) {
                        StreamingBubbleWithTypingEffect(
                            streaming = streaming,
                            typingDelayMs = typingEffectDelayMs,
                            isDark = isDark,
                        )
                    } else {
                        ChatBubble(
                            message = streaming,
                            isDarkTheme = isDark,
                            searchQuery = "",
                            isCurrentMatch = false,
                        )
                    }
                }
            }

            // Thinking indicator
            if (isThinking) {
                item(key = "thinking") {
                    ThinkingIndicator(thinkingText)
                }
            }
        }
    }
}

@Composable
private fun ChatLoadingOverlay(isLoading: Boolean) {
    androidx.compose.animation.AnimatedVisibility(
        visible = isLoading,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                elevation = CardDefaults.cardElevation(4.dp),
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.chat_status_connecting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ChatScrollToBottomFab(
    showScrollToBottom: Boolean,
    scrollScope: kotlinx.coroutines.CoroutineScope,
    listState: LazyListState,
    messages: List<ChatMessage>,
    streamingMessage: ChatMessage?,
    isThinking: Boolean,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = showScrollToBottom,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
    ) {
        FloatingActionButton(
            onClick = {
                scrollScope.launch {
                    val totalItems =
                        messages.size +
                            (if (streamingMessage != null) 1 else 0) +
                            (if (isThinking) 1 else 0)
                    if (totalItems > 0) {
                        listState.animateScrollToItem(totalItems - 1)
                    }
                }
            },
            modifier = Modifier.size(40.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.content_desc_scroll_to_bottom),
            )
        }
    }
}

@Composable
private fun ReloginDialog(
    onDismiss: () -> Unit,
    onRelogin: (String, String, (Boolean, String?) -> Unit) -> Unit,
) {
    val emptyCredentialsError = stringResource(R.string.chat_relogin_error_empty)
    val statusColors = LocalHermesStatusColors.current
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.chat_relogin_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.chat_relogin_username)) },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.chat_relogin_password)) },
                    singleLine = true,
                    enabled = !isLoading,
                    visualTransformation =
                        androidx.compose.ui.text.input
                            .PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = statusColors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isLoading,
                onClick = {
                    if (username.isBlank() || password.isBlank()) {
                        errorMessage = emptyCredentialsError
                        return@TextButton
                    }
                    isLoading = true
                    errorMessage = null
                    onRelogin(username, password) { success, error ->
                        isLoading = false
                        if (success) {
                            onDismiss()
                        } else {
                            errorMessage = error ?: "Unknown error"
                        }
                    }
                },
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = statusColors.info,
                    )
                } else {
                    Text(stringResource(R.string.chat_relogin_submit))
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isLoading,
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.chat_relogin_cancel))
            }
        },
    )
}

// ── Reaction hearts animation ──────────────────────────────────────────

/**
 * Lightweight floating-hearts animation triggered by a `reaction` WS event.
 * Hearts rise from the bottom-center of the chat area, drift slightly
 * horizontally, and fade out — purely cosmetic, no persistence.
 */
@Composable
private fun ReactionHeartsOverlay(
    reactionKind: String?,
    modifier: Modifier = Modifier,
) {
    val emojis =
        remember(reactionKind) {
            when (reactionKind) {
                "ily", "<3", "good bot" ->
                    listOf("💗", "❤️", "💕", "💖", "🩷", "💘", "💝")
                else -> listOf("💗", "❤️", "💕", "💖")
            }
        }

    androidx.compose.animation.AnimatedVisibility(
        visible = reactionKind != null,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(400)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            emojis.forEachIndexed { index, emoji ->
                key("heart_$index") {
                    FloatingHeart(
                        emoji = emoji,
                        delayMs = index * 120L + 100L,
                        horizontalOffset = ((index - emojis.size / 2) * 28).dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingHeart(
    emoji: String,
    delayMs: Long,
    horizontalOffset: Dp,
) {
    val alpha = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        delay(delayMs)
        // Phase 1: fade in quickly at the bottom
        launch {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            )
            // Phase 2: fade out slowly while rising
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 1400, easing = LinearEasing),
            )
        }
        // Float upward over the full animation
        launch {
            offsetY.animateTo(
                targetValue = -220f,
                animationSpec = tween(durationMillis = 1600, easing = LinearEasing),
            )
        }
    }

    Text(
        text = emoji,
        fontSize = 24.sp,
        modifier =
            Modifier
                .offset(x = horizontalOffset, y = offsetY.value.dp)
                .graphicsLayer(alpha = alpha.value),
    )
}
