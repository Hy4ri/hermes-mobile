package com.m57.hermescontrol.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.SwrCache
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
 * Executes a read-only API call with Stale-While-Revalidate caching.
 * If cached data exists in [cache] for [cacheKey], [onCacheHit] is invoked immediately
 * on the caller thread, avoiding full-screen loading spinners on screen revisits.
 * The network call runs in the background to update the cache and invoke [onSuccess].
 */
inline fun <T : Any> ViewModel.safeLaunchSwrLoad(
    cache: SwrCache<String, T>,
    cacheKey: String = "default",
    currentJob: Job? = null,
    crossinline onCacheHit: (T) -> Unit,
    crossinline apiCall: suspend () -> NetworkResult<T>,
    crossinline onStart: () -> Unit,
    crossinline onSuccess: (T) -> Unit,
    crossinline onError: (String) -> Unit,
): Job {
    if (currentJob?.isActive == true) return currentJob
    val cached = cache.get(cacheKey)
    if (cached != null) {
        onCacheHit(cached)
    } else {
        onStart()
    }
    return viewModelScope.launch {
        val result = apiCall()
        when (result) {
            is NetworkResult.Success -> {
                cache.put(cacheKey, result.data)
                onSuccess(result.data)
            }

            is NetworkResult.Failure -> {
                if (cached == null) {
                    onError(result.error.message)
                }
            }
        }
    }
}

/**
 * Executes a mutating API call inside viewModelScope with onStart, onSuccess, onError, and onComplete lifecycle callbacks.
 * Like safeLaunchLoad, avoids Dispatchers.IO hop to protect tests from dispatcher poisoning.
 */
inline fun <T> ViewModel.safeLaunchAction(
    currentJob: Job? = null,
    crossinline onStart: () -> Unit = {},
    crossinline apiCall: suspend () -> NetworkResult<T>,
    crossinline onSuccess: (T) -> Unit = {},
    crossinline onError: (String) -> Unit = {},
    crossinline onComplete: () -> Unit = {},
): Job {
    if (currentJob?.isActive == true) return currentJob
    onStart()
    return viewModelScope.launch {
        try {
            val result = apiCall()
            when (result) {
                is NetworkResult.Success -> onSuccess(result.data)
                is NetworkResult.Failure -> onError(result.error.message)
            }
        } finally {
            onComplete()
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
