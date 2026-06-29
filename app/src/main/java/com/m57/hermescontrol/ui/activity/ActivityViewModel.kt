package com.m57.hermescontrol.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.ActivityItem
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ActivityViewModel : ViewModel() {
    private val _items = MutableStateFlow<List<ActivityItem>>(emptyList())
    val items: StateFlow<List<ActivityItem>> = _items

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        load()
    }

    fun load() {
        if (_isLoading.value) return
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            val result = safeApiCall { ApiClient.hermesApi.getCronJobs() }
            result.onSuccess { jobs ->
                _items.value =
                    jobs
                        .filter { it.lastRunStatus == "ok" }
                        .map {
                            ActivityItem(
                                id = it.id,
                                name = it.name,
                                status = "ok",
                                lastRunAt = it.last_run_at,
                                lastError = it.last_error,
                            )
                        }
                        .sortedByDescending { it.lastRunAt ?: "" }
            }
            result.onFailure { e ->
                _error.value = e.message ?: "Failed to load activity"
            }
            _isLoading.value = false
        }
    }

    fun refresh() {
        load()
    }
}
