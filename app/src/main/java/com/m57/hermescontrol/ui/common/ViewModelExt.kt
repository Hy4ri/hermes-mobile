package com.m57.hermescontrol.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.remote.NetworkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        val result = withContext(Dispatchers.IO) { apiCall() }
        when (result) {
            is NetworkResult.Success -> onSuccess(result.data)
            is NetworkResult.Failure -> onError(result.error.message)
        }
    }
}
