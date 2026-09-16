package com.m57.hermescontrol.data.repository

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
    private var reloadJob: Job? = null
    private var pollJob: Job? = null
    private var nudgeJob: Job? = null

    private val _syncStatus = MutableStateFlow<KanbanSyncStatus>(KanbanSyncStatus.Disconnected)
    val syncStatus: StateFlow<KanbanSyncStatus> = _syncStatus.asStateFlow()

    fun connect(
        scope: CoroutineScope,
        board: String,
        onReload: () -> Unit,
    ) {
        if (activeBoard == board && _syncStatus.value == KanbanSyncStatus.Live) return
        activeBoard = board

        val client = eventsClient ?: eventsClientProvider().also { eventsClient = it }
        client.connect(
            scope = scope,
            board = board,
            onEvents = {
                reloadJob?.cancel()
                reloadJob =
                    scope.launch {
                        delay(reloadDebounceMs)
                        onReload()
                    }
            },
            onStatus = { status ->
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
        activeBoard = null
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
