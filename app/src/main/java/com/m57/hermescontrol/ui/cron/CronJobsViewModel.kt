package com.m57.hermescontrol.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.CreateCronJobRequest
import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.data.model.UpdateCronJobRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.toJsonElement
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class CronJobsUiState(
    val isLoading: Boolean = false,
    val jobs: List<CronJob> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    // Editor state
    val editorState: CronJobEditorState = CronJobEditorState(),
)

data class CronJobEditorState(
    val isOpen: Boolean = false,
    val isNew: Boolean = false,
    val jobId: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val toastMessage: String? = null,
    // Form fields
    val name: String = "",
    val schedule: String = "",
    val prompt: String = "",
    val deliver: String = "local",
    val skills: String = "",
    val model: String = "",
    val provider: String = "",
    val base_url: String = "",
    val script: String = "",
    val workdir: String = "",
    val enabled: Boolean = true,
    val no_agent: Boolean = false,
)

class CronJobsViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(CronJobsUiState())
    val uiState: StateFlow<CronJobsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun loadCronJobs() {
        loadJob =
            safeLaunchLoad(
                currentJob = loadJob,
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

    // ── Editor ──

    fun openNewJobDialog() {
        _uiState.update {
            it.copy(
                editorState =
                    CronJobEditorState(
                        isOpen = true,
                        isNew = true,
                    ),
            )
        }
    }

    fun openEditJobDialog(id: String) {
        _uiState.update {
            it.copy(
                editorState =
                    CronJobEditorState(
                        isOpen = true,
                        isNew = false,
                        jobId = id,
                        isLoading = true,
                    ),
            )
        }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getCronJob(id) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val job = result.data ?: return@launch
                    _uiState.update {
                        it.copy(
                            editorState =
                                CronJobEditorState(
                                    isOpen = true,
                                    isNew = false,
                                    jobId = id,
                                    name = job.name,
                                    schedule = extractScheduleString(job),
                                    prompt = job.prompt.orEmpty(),
                                    deliver = job.deliver ?: "local",
                                    skills = (job.skills ?: emptyList()).joinToString("\n"),
                                    model = job.model.orEmpty(),
                                    provider = job.provider.orEmpty(),
                                    base_url = job.base_url.orEmpty(),
                                    script = job.script.orEmpty(),
                                    workdir = job.workdir.orEmpty(),
                                    enabled = job.enabled ?: true,
                                    no_agent = job.no_agent ?: false,
                                ),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            editorState =
                                CronJobEditorState(
                                    isOpen = true,
                                    isNew = false,
                                    jobId = id,
                                    toastMessage = "Failed to load job: ${result.error.message}",
                                ),
                        )
                    }
                }
            }
        }
    }

    fun closeEditor() {
        _uiState.update { it.copy(editorState = CronJobEditorState()) }
    }

    fun updateEditorField(
        name: String,
        value: String,
    ) {
        _uiState.update { state ->
            state.copy(
                editorState =
                    state.editorState.applyFieldChange(name, value).copy(
                        toastMessage = null,
                    ),
            )
        }
    }

    fun saveEditor() {
        val editor = _uiState.value.editorState
        if (editor.schedule.isBlank()) {
            _uiState.update { it.copy(editorState = editor.copy(toastMessage = "Schedule is required")) }
            return
        }
        _uiState.update { it.copy(editorState = editor.copy(isSaving = true)) }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    if (editor.isNew) {
                        safeApiCall {
                            ApiClient.hermesApi.createCronJob(
                                CreateCronJobRequest(
                                    name = editor.name,
                                    schedule = editor.schedule,
                                    prompt = editor.prompt,
                                    deliver = editor.deliver,
                                    skills = parseLines(editor.skills),
                                    model = editor.model.ifBlank { null },
                                    provider = editor.provider.ifBlank { null },
                                    base_url = editor.base_url.ifBlank { null },
                                    script = editor.script.ifBlank { null },
                                    workdir = editor.workdir.ifBlank { null },
                                    no_agent = editor.no_agent,
                                ),
                            )
                        }
                    } else {
                        val updates = mutableMapOf<String, Any?>()
                        if (editor.name.isNotEmpty()) updates["name"] = editor.name
                        updates["schedule"] = editor.schedule
                        updates["prompt"] = editor.prompt
                        updates["deliver"] = editor.deliver
                        updates["skills"] = parseLines(editor.skills)
                        updates["model"] = editor.model.ifBlank { null }
                        updates["provider"] = editor.provider.ifBlank { null }
                        updates["base_url"] = editor.base_url.ifBlank { null }
                        updates["script"] = editor.script.ifBlank { null }
                        updates["workdir"] = editor.workdir.ifBlank { null }
                        updates["no_agent"] = editor.no_agent
                        safeApiCall {
                            ApiClient.hermesApi.updateCronJob(
                                editor.jobId ?: "",
                                UpdateCronJobRequest(updates = updates.mapValues { it.value.toJsonElement() }),
                            )
                        }
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    closeEditor()
                    loadCronJobs()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            editorState =
                                it.editorState.copy(
                                    isSaving = false,
                                    toastMessage = "Failed to save: ${result.error.message}",
                                ),
                        )
                    }
                }
            }
        }
    }

    fun clearEditorToast() {
        _uiState.update { it.copy(editorState = it.editorState.copy(toastMessage = null)) }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun revertJobs(
        originalJobs: List<CronJob>,
        errorMsg: String,
    ) {
        _uiState.update { it.copy(jobs = originalJobs, toastMessage = errorMsg) }
    }

    private fun extractScheduleString(job: CronJob): String {
        val s = job.schedule
        return when (s) {
            is JsonPrimitive -> s.content
            is JsonObject -> (s["value"] as? JsonPrimitive)?.content ?: job.scheduleText
            else -> job.scheduleText
        }
    }

    private fun parseLines(skills: String): List<String> =
        skills
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun CronJobEditorState.applyFieldChange(
        name: String,
        value: String,
    ): CronJobEditorState =
        when (name) {
            "name" -> copy(name = value)
            "schedule" -> copy(schedule = value)
            "prompt" -> copy(prompt = value)
            "deliver" -> copy(deliver = value)
            "skills" -> copy(skills = value)
            "model" -> copy(model = value)
            "provider" -> copy(provider = value)
            "base_url" -> copy(base_url = value)
            "script" -> copy(script = value)
            "workdir" -> copy(workdir = value)
            else -> this
        }

    fun toggleNoAgent() {
        _uiState.update {
            it.copy(editorState = it.editorState.copy(no_agent = !it.editorState.no_agent))
        }
    }
}
