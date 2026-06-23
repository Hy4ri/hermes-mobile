package com.m57.hermescontrol.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CronJobsUiState(
    val isLoading: Boolean = false,
    val jobs: List<CronJob> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class CronJobsViewModel : ViewModel(), ToastHost {
    private val _uiState = MutableStateFlow(CronJobsUiState())
    val uiState: StateFlow<CronJobsUiState> = _uiState.asStateFlow()

    fun loadCronJobs() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getCronJobs() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update { it.copy(isLoading = false, jobs = data.orEmpty()) }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load cron jobs: $errorMsg",
                    )
                }
            },
        )
    }

    fun pauseCronJob(id: String) {
        val originalJobs = _uiState.value.jobs
        // Optimistic update
        _uiState.update { state ->
            state.copy(
                jobs =
                    state.jobs.map {
                        if (it.id == id) it.copy(state = "paused") else it
                    },
            )
        }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.pauseCronJob(id) }
                }
            if (result is NetworkResult.Failure) {
                revertJobs(originalJobs, "Failed to pause cron job: ${result.error.message}")
            }
        }
    }

    fun resumeCronJob(id: String) {
        val originalJobs = _uiState.value.jobs
        // Optimistic update
        _uiState.update { state ->
            state.copy(
                jobs =
                    state.jobs.map {
                        if (it.id == id) it.copy(state = "active") else it
                    },
            )
        }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.resumeCronJob(id) }
                }
            if (result is NetworkResult.Failure) {
                revertJobs(originalJobs, "Failed to resume cron job: ${result.error.message}")
            }
        }
    }

    fun triggerCronJob(id: String) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.triggerCronJob(id) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Job triggered successfully") }
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to trigger cron job: ${result.error.message}") }
                }
            }
        }
    }

    fun deleteCronJob(id: String) {
        val originalJobs = _uiState.value.jobs
        // Optimistic update
        _uiState.update { state ->
            state.copy(jobs = state.jobs.filter { it.id != id })
        }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.deleteCronJob(id) }
                }
            if (result is NetworkResult.Failure) {
                revertJobs(originalJobs, "Failed to delete cron job: ${result.error.message}")
            }
        }
    }

    private fun revertJobs(
        originalJobs: List<CronJob>,
        errorMsg: String,
    ) {
        _uiState.update { it.copy(jobs = originalJobs, toastMessage = errorMsg) }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
