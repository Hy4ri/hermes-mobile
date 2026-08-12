package com.m57.hermescontrol.data.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder of the latest update-check result (issue #890). The
 * launch check ([UpdateNoticeManager]) writes here so the chat banner can
 * appear without a second network call, and the About tab adopts the same
 * result so both surfaces agree on what the last check found.
 */
object AppUpdateCache {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    /**
     * Session-only dismissal of the chat banner. Snapshot-backed so the chat
     * screen recomposes when it flips; dies with the process, so a dismissed
     * banner returns on the next launch (the persisted latest tag drives it).
     */
    var dismissed by mutableStateOf(false)
        private set

    fun update(state: AppUpdateState) {
        _state.value = state
    }

    fun dismiss() {
        dismissed = true
    }

    /** Test hook: clear state and dismissal between tests. */
    internal fun reset() {
        dismissed = false
        _state.value = AppUpdateState.Idle
    }
}
