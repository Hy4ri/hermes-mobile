package com.m57.hermescontrol.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.notification.NotificationHelper
import com.m57.hermescontrol.theme.StatusRed
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    // Lifecycle effects, permissions, session switching, auto-scroll, errors
    ChatLifecycleEffects(
        sessionId = sessionId,
        connectionStatus = state.connectionStatus,
        currentSessionId = state.currentSessionId,
        messages = state.messages,
        streamingMessage = streamingState.streamingMessage,
        isThinking = streamingState.isThinking,
        errorMessage = state.errorMessage,
        isSearchActive = state.isSearchActive,
        currentSearchMatchIndex = state.currentSearchMatchIndex,
        searchMatchIndices = state.searchMatchIndices,
        clarifyRequest = state.clarifyRequest,
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
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    maxLines = 2,
                    modifier = Modifier.animateContentSize(),
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
) {
    // Allow sending slash commands even while agent is typing
    val isSlashCommand = inputText.startsWith("/")
    val canSend = inputText.isNotBlank() && isConnected && (!isAgentTyping || isSlashCommand)

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

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp, max = 120.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
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

                    Spacer(modifier = Modifier.width(6.dp))

                    // Mic / Stop / Send button — swaps based on input state
                    AnimatedContent(
                        targetState =
                            when {
                                isListening -> "listening"
                                inputText.isBlank() -> "mic"
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
    isSearchActive: Boolean,
    currentSearchMatchIndex: Int,
    searchMatchIndices: List<Int>,
    clarifyRequest: ClarifyUi?,
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

    // Clarify dialog
    clarifyRequest?.let { clarify ->
        ClarifyDialog(
            clarify = clarify,
            onOptionSelected = viewModel::respondToClarify,
            onDismiss = viewModel::dismissClarify,
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
) {
    androidx.compose.animation.AnimatedVisibility(
        visible =
            connectionStatus == ConnectionStatus.RECONNECTING ||
                connectionStatus == ConnectionStatus.DISCONNECTED,
        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text =
                        if (connectionStatus == ConnectionStatus.RECONNECTING) {
                            stringResource(R.string.chat_status_reconnecting)
                        } else {
                            stringResource(R.string.chat_status_disconnected)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (connectionStatus == ConnectionStatus.DISCONNECTED) {
                    TextButton(
                        onClick = onReconnect,
                        colors =
                            androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                    ) {
                        Text(stringResource(R.string.chat_action_reconnect))
                    }
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
    viewModel: ChatViewModel,
) {
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
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
