package com.m57.hermescontrol.data.repository

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.ws.KanbanEventsClient
import com.m57.hermescontrol.data.ws.KanbanLiveStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface KanbanSyncStatus {
    data object Live : KanbanSyncStatus

    data class Polling(
        val lastSyncEpoch: Long = System.currentTimeMillis(),
    ) : KanbanSyncStatus

    data object AuthFailed : KanbanSyncStatus

    data object BoardUnavailable : KanbanSyncStatus

    data object Disconnected : KanbanSyncStatus
}

class KanbanSyncCoordinator(
    private val repository: KanbanRepository,
    private val eventsClientProvider: () -> KanbanEventsClient = { KanbanEventsClient() },
    private val reloadDebounceMs: Long = 250L,
    private val pollIntervalMs: Long = 60_000L,
    private val nudgeDebounceMs: Long = 400L,
) {
    private var eventsClient: KanbanEventsClient? = null
    private var activeBoard: String? = null
    private var activeScope: DataScope? = null
    private var connectionGeneration = 0L
    private var connectJob: Job? = null
    private var reloadJob: Job? = null
    private var pollJob: Job? = null
    private var nudgeJob: Job? = null

    private val _syncStatus = MutableStateFlow<KanbanSyncStatus>(KanbanSyncStatus.Disconnected)
    val syncStatus: StateFlow<KanbanSyncStatus> = _syncStatus.asStateFlow()

    fun connect(
        scope: CoroutineScope,
        board: String,
        since: Long? = null,
        onReload: () -> Unit,
    ) {
        val requestScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
        if (activeBoard == board && activeScope == requestScope && _syncStatus.value == KanbanSyncStatus.Live) return
        val generation = ++connectionGeneration
        connectJob?.cancel()
        reloadJob?.cancel()
        eventsClient?.disconnect()
        activeBoard = board
        activeScope = requestScope
        _syncStatus.value = KanbanSyncStatus.Disconnected
        val isCurrent = {
            generation == connectionGeneration && activeBoard == board &&
                requestScope == runCatching { AuthManager.currentDataScope() }.getOrNull()
        }

        if (since != null) {
            subscribeWithCursor(scope, board, since, onReload, isCurrent)
        } else {
            connectJob =
                scope.launch {
                    val result = repository.getBoard(board = board)
                    if (!isCurrent()) return@launch
                    val cursor = (result as? NetworkResult.Success)?.data?.latestEventId
                    // #1557: a failed snapshot cannot establish a reliable tail subscription.
                    if (cursor == null) {
                        _syncStatus.value = KanbanSyncStatus.Polling()
                        onReload()
                        return@launch
                    }
                    onReload()
                    if (!isCurrent()) return@launch
                    subscribeWithCursor(scope, board, cursor, onReload, isCurrent)
                }
        }
    }

    private fun subscribeWithCursor(
        scope: CoroutineScope,
        board: String,
        cursor: Long,
        onReload: () -> Unit,
        isCurrent: () -> Boolean,
    ) {
        if (!isCurrent()) return
        val client = eventsClient ?: eventsClientProvider().also { eventsClient = it }
        client.connect(
            scope = scope,
            board = board,
            since = cursor,
            onEvents = {
                if (!isCurrent()) return@connect
                reloadJob?.cancel()
                reloadJob =
                    scope.launch {
                        delay(reloadDebounceMs)
                        if (isCurrent()) onReload()
                    }
            },
            onStatus = { status ->
                if (!isCurrent()) return@connect
                when (status) {
                    KanbanLiveStatus.CONNECTED -> {
                        _syncStatus.value = KanbanSyncStatus.Live
                    }

                    KanbanLiveStatus.DISCONNECTED -> {
                        _syncStatus.value = KanbanSyncStatus.Polling()
                    }

                    KanbanLiveStatus.AUTH_FAILED -> {
                        _syncStatus.value = KanbanSyncStatus.AuthFailed
                    }

                    KanbanLiveStatus.BOARD_UNAVAILABLE -> {
                        _syncStatus.value = KanbanSyncStatus.BoardUnavailable
                    }
                }
            },
        )
    }

    fun startVisiblePolling(
        scope: CoroutineScope,
        board: String,
        onPoll: () -> Unit,
    ) {
        pollJob?.cancel()
        pollJob =
            scope.launch {
                while (true) {
                    delay(pollIntervalMs)
                    if (_syncStatus.value !is KanbanSyncStatus.Live) {
                        onPoll()
                        _syncStatus.value = KanbanSyncStatus.Polling(System.currentTimeMillis())
                    }
                }
            }
    }

    fun stopVisiblePolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun scheduleNudge(
        scope: CoroutineScope,
        board: String,
    ) {
        nudgeJob?.cancel()
        nudgeJob =
            scope.launch {
                delay(nudgeDebounceMs)
                repository.nudgeDispatcher(board)
            }
    }

    fun disconnect() {
        connectionGeneration++
        connectJob?.cancel()
        connectJob = null
        activeBoard = null
        activeScope = null
        reloadJob?.cancel()
        reloadJob = null
        pollJob?.cancel()
        pollJob = null
        nudgeJob?.cancel()
        nudgeJob = null
        eventsClient?.disconnect()
        _syncStatus.value = KanbanSyncStatus.Disconnected
    }
}
