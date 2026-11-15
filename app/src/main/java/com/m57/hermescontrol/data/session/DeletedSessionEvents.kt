package com.m57.hermescontrol.data.session

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Announces stored sessions that were deleted (for example from History).
 * The chat ViewModel lives as long as the Activity, so it can still show a session that
 * History deleted. This signal lets it leave that session.
 */
object DeletedSessionEvents {
    private val deleted =
        MutableSharedFlow<Set<String>>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Set<String>> = deleted.asSharedFlow()

    fun emit(sessionIds: Set<String>) {
        if (sessionIds.isNotEmpty()) deleted.tryEmit(sessionIds)
    }
}
