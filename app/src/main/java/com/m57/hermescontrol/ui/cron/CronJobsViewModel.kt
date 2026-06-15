package com.m57.hermescontrol.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.data.remote.ApiClient
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

class CronJobsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(CronJobsUiState())
    val uiState: StateFlow<CronJobsUiState> = _uiState.asStateFlow()

    fun loadCronJobs() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getCronJobs()
                    }
                if (response.isSuccessful) {
                    _uiState.update { it.copy(isLoading = false, jobs = response.body().orEmpty()) }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load cron jobs: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to load cron jobs: ${e.message}") }
            }
        }
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
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.pauseCronJob(id)
                    }
                if (!response.isSuccessful) {
                    revertJobs(originalJobs, "Failed to pause cron job: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                revertJobs(originalJobs, "Failed to pause cron job: ${e.message}")
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
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.resumeCronJob(id)
                    }
                if (!response.isSuccessful) {
                    revertJobs(originalJobs, "Failed to resume cron job: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                revertJobs(originalJobs, "Failed to resume cron job: ${e.message}")
            }
        }
    }

    fun triggerCronJob(id: String) {
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.triggerCronJob(id)
                    }
                if (response.isSuccessful) {
                    _uiState.update { it.copy(toastMessage = "Job triggered successfully") }
                } else {
                    _uiState.update { it.copy(toastMessage = "Failed to trigger cron job: HTTP ${response.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Failed to trigger cron job: ${e.message}") }
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
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.deleteCronJob(id)
                    }
                if (!response.isSuccessful) {
                    revertJobs(originalJobs, "Failed to delete cron job: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                revertJobs(originalJobs, "Failed to delete cron job: ${e.message}")
            }
        }
    }

    private fun revertJobs(
        originalJobs: List<CronJob>,
        errorMsg: String,
    ) {
        _uiState.update { it.copy(jobs = originalJobs, toastMessage = errorMsg) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
