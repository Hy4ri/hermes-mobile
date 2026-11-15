package com.m57.hermescontrol.data.session

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Signals that stored sessions were deleted (History -> Chat). The chat ViewModel is
 * Activity-scoped and survives a trip to History: without this signal an open session deleted there
 * stayed on the chat screen, and every poll for it got 4001 not_found / 404.
 */
object DeletedSessionEvents {
    private val deleted =
        MutableSharedFlow<Set<String>>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Set<String>> = deleted.asSharedFlow()

    fun emit(sessionIds: Set<String>) {
        if (sessionIds.isNotEmpty()) deleted.tryEmit(sessionIds)
    }
}
