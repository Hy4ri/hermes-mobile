package com.m57.hermescontrol.shortcuts

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Android-free signal so successful session mutations refresh launcher metadata, even on old gateways. */
object ShortcutRefreshEvents {
    private val requests =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val events = requests.asSharedFlow()

    fun refresh() {
        requests.tryEmit(Unit)
    }
}
