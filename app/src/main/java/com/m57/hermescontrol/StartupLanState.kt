package com.m57.hermescontrol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Shared by the activity's initial socket dial and the regression tests. */
internal suspend fun awaitStartupLanAdmission(states: Flow<StartupLanState>) {
    states.first { it.connectReady }
}

/** Admission state for a saved connection; only Ready allows navigation or startup dial. */
internal enum class StartupLanState {
    Checking,
    Request,
    Denied,
    ChooseConnection,
    Ready,
    ;

    val connectReady: Boolean get() = this == Ready

    fun next(event: StartupLanEvent): StartupLanState =
        when (event) {
            is StartupLanEvent.PermissionChecked -> {
                if (this == Checking) {
                    if (event.grantedOrNotRequired) Ready else Request
                } else {
                    this
                }
            }

            is StartupLanEvent.PermissionResult -> {
                if (this == Request) {
                    if (event.granted) Ready else Denied
                } else {
                    this
                }
            }

            is StartupLanEvent.Resume -> {
                if (this == Denied && event.granted) Ready else this
            }

            StartupLanEvent.Retry -> {
                if (this == Denied) Request else this
            }

            StartupLanEvent.ChooseConnection -> {
                if (this == Denied) ChooseConnection else this
            }

            StartupLanEvent.Back -> {
                if (this == ChooseConnection) Denied else this
            }

            StartupLanEvent.Connected -> {
                if (this == ChooseConnection) Ready else this
            }
        }
}

internal sealed interface StartupLanEvent {
    data class PermissionChecked(
        val grantedOrNotRequired: Boolean,
    ) : StartupLanEvent

    data class PermissionResult(
        val granted: Boolean,
    ) : StartupLanEvent

    data class Resume(
        val granted: Boolean,
    ) : StartupLanEvent

    data object Retry : StartupLanEvent

    data object ChooseConnection : StartupLanEvent

    data object Back : StartupLanEvent

    data object Connected : StartupLanEvent
}
