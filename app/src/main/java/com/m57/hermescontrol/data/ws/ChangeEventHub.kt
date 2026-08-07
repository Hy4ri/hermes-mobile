package com.m57.hermescontrol.data.ws

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fan-out hub for gateway change events ([WsEvent.ChangeEvent]).
 *
 * [HermesWsClient] forwards the change events it parses into this hub; screens
 * subscribe here instead of touching the [HermesWsClient] singleton directly.
 *
 * Why a separate hub: [HermesWsClient] is an Android-touching object whose real
 * static init must never run inside unit tests (it poisons every later
 * `mockkObject(HermesWsClient)` in the JVM). This hub is pure Kotlin — safe to
 * subscribe to from a ViewModel's `init` in any environment. When the client is
 * mocked in tests, nothing forwards into the hub and subscribers simply sit
 * idle, which is also the correct behavior for backends without
 * `change_events` (issue #784).
 */
object ChangeEventHub {
    private val _events =
        MutableSharedFlow<WsEvent.ChangeEvent>(
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Collect this from ViewModels to react to gateway change events. */
    val events: SharedFlow<WsEvent.ChangeEvent> = _events.asSharedFlow()

    /** Non-suspending emit — never blocks the WS event loop. */
    fun emit(event: WsEvent.ChangeEvent) {
        _events.tryEmit(event)
    }
}
