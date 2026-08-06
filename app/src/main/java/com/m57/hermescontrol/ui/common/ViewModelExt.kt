package com.m57.hermescontrol.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.remote.NetworkResult
import kotlinx.coroutines.Job
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
