package com.m57.hermescontrol.ui.chat.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.notification.NotificationHelper
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.ClarifyUi
import com.m57.hermescontrol.ui.chat.SecretPromptUi
import com.m57.hermescontrol.ui.chat.SudoPromptUi
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ChatLifecycleEffects(
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
    val lifecycleOwner = LocalLifecycleOwner.current

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
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        NotificationHelper.setAppForeground(context, true)
                        NotificationHelper.stop(context)
                        viewModel.refreshSettings()
                        viewModel.refreshCurrentSession()
                    }

                    Lifecycle.Event.ON_STOP -> {
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
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(permission)
            }
        }
    }

    // Auto-scroll to bottom on new messages + session switch (issues #584/#583)
    //
    // IMPORTANT (m57, #584 follow-up): while an assistant message is STREAMING
    // the scroll is left completely FREE — no auto-follow of the streaming
    // message — so the user can scroll up/down to read while it generates.
    // Auto-scroll only happens on explicit actions (send / FAB / session
    // switch, handled elsewhere) and when a discrete non-streaming message
    // lands while the user is already pinned to the bottom.
    //
    // The effect runs once (Unit), so we mirror the params through
    // rememberUpdatedState and read them live inside the flow/collect — that
    // keeps the lambda bound to the latest values instead of first-composition
    // closure captures. streamingMessage is intentionally NOT a flow key, so
    // the flow doesn't churn per token; we just read it live to gate scrolling.
    val latestMessages by rememberUpdatedState(messages)
    val latestStreaming by rememberUpdatedState(streamingMessage)
    val latestIsThinking by rememberUpdatedState(isThinking)
    val latestSessionId by rememberUpdatedState(currentSessionId)
    LaunchedEffect(Unit) {
        snapshotFlow {
            Pair(latestMessages.size, latestIsThinking)
        }.collectLatest { (msgCount, thinking) ->
            val totalItems = msgCount + (if (thinking) 1 else 0)
            if (totalItems <= 0) return@collectLatest
            // While a message is streaming, leave scrolling free (no auto-follow).
            if (latestStreaming != null) return@collectLatest
            val isSessionSwitch = latestSessionId != lastSessionId
            if (isSessionSwitch) {
                lastSessionId = latestSessionId
                listState.scrollToBottom(animated = false)
                return@collectLatest
            }
            if (!listState.isAtBottom()) return@collectLatest
            // Discrete new message while pinned to the bottom — follow it.
            listState.scrollToBottom(animated = true)
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
