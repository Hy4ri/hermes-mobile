package com.m57.hermescontrol.ui.chat

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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.notification.NotificationHelper
import com.m57.hermescontrol.theme.StatusGreen
import com.m57.hermescontrol.theme.StatusRed
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.HermesScaffold

@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ChatViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var inputText by rememberSaveable { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Manage notification foreground service lifecycle:
    // - When app goes to background (ON_STOP), mark as not-foreground and
    //   start the notification service so we can post reply notifications.
    // - When app returns to foreground (ON_START), mark as foreground and
    //   stop the notification service.
    DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_START -> {
                        NotificationHelper.setAppForeground(context, true)
                        NotificationHelper.stop(context)
                    }
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                        NotificationHelper.setAppForeground(context, false)
                        NotificationHelper.start(context)
                    }
                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Auto-scroll to bottom on new messages
    LaunchedEffect(state.messages.size, state.currentStreamingText) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    // Show error as snackbar
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Clarify dialog
    state.clarifyRequest?.let { clarify ->
        ClarifyDialog(
            clarify = clarify,
            onOptionSelected = viewModel::respondToClarify,
            onDismiss = viewModel::dismissClarify,
        )
    }

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
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Hermes",
                    style =
                        MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Connection status dot
                Box(
                    modifier =
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (state.isConnected) StatusGreen else StatusRed),
                )
            }
        },
        onOpenDrawer = onOpenDrawer,
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
            IconButton(onClick = { viewModel.createNewSession() }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "New Chat",
                )
            }

            // Session picker
            Box {
                IconButton(onClick = {
                    viewModel.loadSessions()
                    viewModel.toggleSessionPicker()
                }) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = "Sessions",
                    )
                }

                DropdownMenu(
                    expanded = state.showSessionPicker,
                    onDismissRequest = { viewModel.toggleSessionPicker() },
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("New Session")
                            }
                        },
                        onClick = {
                            viewModel.createNewSession()
                            viewModel.toggleSessionPicker()
                        },
                    )
                    if (state.sessions.isNotEmpty()) {
                        HorizontalDivider()
                    }
                    state.sessions.forEach { session ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = session.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = "${session.messageCount} messages",
                                        style =
                                            MaterialTheme.typography.labelSmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            ),
                                    )
                                }
                            },
                            onClick = { viewModel.switchSession(session.id) },
                            trailingIcon = {
                                if (session.id == state.currentSessionId) {
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = "Current",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            // Settings
            IconButton(onClick = onNavigateToSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(backgroundGradient)
                    .navigationBarsPadding()
                    .imePadding(),
        ) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                if (state.messages.isEmpty() && !state.isLoading) {
                    EmptyState(
                        title = "Ready to chat",
                        subtitle = "Send a message to start a conversation",
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(
                        items = state.messages,
                        key = { it.id },
                    ) { message ->
                        ChatBubble(
                            message = message,
                            isDarkTheme = isDark,
                        )
                    }

                    // Thinking indicator
                    if (state.isThinking) {
                        item(key = "thinking") {
                            ThinkingIndicator(state.thinkingText)
                        }
                    }
                }

                // Loading overlay
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.isLoading,
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
                                    text = "Connecting to Hermes…",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            ChatInputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                onSend = {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                },
                onInterrupt = viewModel::interruptSession,
                isAgentTyping = state.isAgentTyping,
                isConnected = state.isConnected,
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
                            "Thinking: $display…"
                        } else {
                            "Thinking…"
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
    onInterrupt: () -> Unit,
    isAgentTyping: Boolean,
    isConnected: Boolean,
) {
    val canSend = inputText.isNotBlank() && !isAgentTyping && isConnected

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                val commands =
                    listOf(
                        "/help",
                        "/status",
                        "/sessions",
                        "/stats",
                        "/system",
                        "/new",
                        "/stop",
                        "/interrupt",
                    )
                androidx.compose.animation.AnimatedVisibility(
                    visible = inputText.startsWith("/") && !inputText.contains(" "),
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
                ) {
                    val filteredCommands = commands.filter { it.startsWith(inputText, ignoreCase = true) }
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
                                items(filteredCommands) { cmd ->
                                    val description =
                                        when (cmd) {
                                            "/help" -> "Show help menu"
                                            "/status" -> "Check gateway and platform status"
                                            "/sessions" -> "List all chat sessions"
                                            "/stats" -> "Check system resource usage (stats)"
                                            "/system" -> "Check system resource usage (system)"
                                            "/new" -> "Create a new chat session"
                                            "/stop" -> "Interrupt the active run"
                                            "/interrupt" -> "Interrupt the active run"
                                            else -> ""
                                        }
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
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(max = 140.dp),
                        placeholder = {
                            Text(
                                if (!isConnected) {
                                    "Not connected…"
                                } else if (isAgentTyping) {
                                    "Waiting for response…"
                                } else {
                                    "Type a message…"
                                },
                            )
                        },
                        enabled = isConnected,
                        maxLines = 5,
                        shape = RoundedCornerShape(20.dp),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                cursorColor = MaterialTheme.colorScheme.primary,
                            ),
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    if (isAgentTyping) {
                        // Interrupt button
                        FilledTonalButton(
                            onClick = onInterrupt,
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "Interrupt",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        // Send button
                        FilledTonalButton(
                            onClick = onSend,
                            enabled = canSend,
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClarifyDialog(
    clarify: ClarifyUi,
    onOptionSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clarification Needed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = clarify.text)
                Spacer(modifier = Modifier.height(8.dp))
                clarify.options.forEach { option ->
                    FilledTonalButton(
                        onClick = { onOptionSelected(option) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(option)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        },
    )
}
