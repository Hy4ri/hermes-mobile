package com.m57.hermescontrol.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.ws.ChangeEventHub
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

interface ToastHost {
    fun clearToast()
}

inline fun <T> ViewModel.safeLaunchLoad(
    currentJob: Job? = null,
    crossinline apiCall: suspend () -> NetworkResult<T>,
    crossinline onStart: () -> Unit,
    crossinline onSuccess: (T) -> Unit,
    crossinline onError: (String) -> Unit,
): Job {
    if (currentJob?.isActive == true) return currentJob
    onStart()
    return viewModelScope.launch {
        // No withContext(Dispatchers.IO) hop: Retrofit suspend calls already
        // run off the caller thread, and an explicit hop forces every
        // consuming test to fake Dispatchers.IO with a static mock — the
        // JVM-wide bleed that flakes the suite (see the de-poisoned tests).
        val result = apiCall()
        when (result) {
            is NetworkResult.Success -> onSuccess(result.data)
            is NetworkResult.Failure -> onError(result.error.message)
        }
    }
}

/**
 * Collect a gateway change event ([com.m57.hermescontrol.data.ws.ChangeEvents])
 * from [ChangeEventHub] and run a **silent** refresh: no loading spinner, no
 * error surface — stale data stays in place on failure. At most one request
 * runs at a time; events arriving mid-flight are skipped, so a
 * `sessions.changed` burst during a long streaming turn (the gateway floors
 * those at 2s) cannot pile up requests.
 *
 * Call from `init`. Backends without `change_events` never broadcast, and in
 * unit tests the hub is never fed (HermesWsClient is mocked or uninitialized),
 * so this is a no-op there — exactly the "slow backstop" contract of issue
 * #784.
 */
inline fun <T> ViewModel.refreshOnChange(
    eventType: String,
    crossinline apiCall: suspend () -> NetworkResult<T>,
    crossinline onSuccess: (T) -> Unit,
): Job {
    var refreshInFlight = false
    return viewModelScope.launch {
        ChangeEventHub.events
            .filter { it.type == eventType }
            .collect { _ ->
                if (refreshInFlight) return@collect
                refreshInFlight = true
                try {
                    // No Dispatchers.IO hop — see safeLaunchLoad.
                    val result = apiCall()
                    if (result is NetworkResult.Success) {
                        onSuccess(result.data)
                    }
                } finally {
                    refreshInFlight = false
                }
            }
    }
}
