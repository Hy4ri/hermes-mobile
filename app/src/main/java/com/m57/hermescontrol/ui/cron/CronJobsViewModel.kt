package com.m57.hermescontrol.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.CreateCronJobRequest
import com.m57.hermescontrol.data.model.CronBlueprint
import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.data.model.DeliveryTarget
import com.m57.hermescontrol.data.model.InstantiateBlueprintRequest
import com.m57.hermescontrol.data.model.UpdateCronJobRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.data.ws.toJsonElement
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.refreshOnChange
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
    // Delete-confirm state (non-null while the confirm dialog is up)
    val deleteTarget: CronJob? = null,
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
    // Run continuity: when true, the job's own previous output is injected
    // into each subsequent run via context_from=["self"] (backend cron
    // self-continuity). Only meaningful for recurring jobs.
    val runContinuity: Boolean = false,
    // Monitor mode: one of monitor_script / monitor_url set at a time
    // (mutually exclusive on the backend; incompatible with no_agent).
    val monitorMode: String = "off", // "off" | "script" | "url"
    val monitor_script: String = "",
    val monitor_url: String = "",
    // Blueprint start-from state (new jobs only)
    val blueprints: List<CronBlueprint> = emptyList(),
    val deliveryTargets: List<DeliveryTarget> = emptyList(),
    val selectedBlueprintKey: String? = null,
    val blueprintValues: Map<String, String> = emptyMap(),
) {
    val selectedBlueprint: CronBlueprint?
        get() = blueprints.firstOrNull { it.key == selectedBlueprintKey }

    /** Delivery ids offered by the picker: origin, then local + connected platforms. */
    val deliveryOptions: List<String>
        get() =
            buildList {
                add("origin")
                addAll(deliveryTargets.map { it.id })
                if (deliveryTargets.none { it.id == "local" }) {
                    add("local")
                }
            }
}

class CronJobsViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(CronJobsUiState())
    val uiState: StateFlow<CronJobsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        // Issue #784: gateway broadcasts cron.changed — refresh the list
        // silently on change instead of waiting for a manual pull.
        refreshOnChange(
            eventType = ChangeEvents.CRON,
            apiCall = { safeApiCall { ApiClient.hermesApi.getCronJobs() } },
            onSuccess = { data -> _uiState.update { it.copy(jobs = data.orEmpty()) } },
        )
    }

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

    fun requestDeleteJob(job: CronJob) {
        _uiState.update { it.copy(deleteTarget = job) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(deleteTarget = null) }
    }

    fun confirmDeleteJob() {
        val target = _uiState.value.deleteTarget ?: return
        _uiState.update { it.copy(deleteTarget = null) }
        deleteCronJob(target.id)
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
        loadEditorBlueprints()
        loadDeliveryTargets()
    }

    fun loadEditorBlueprints() {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getCronBlueprints() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(editorState = it.editorState.copy(blueprints = result.data.blueprints))
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            editorState =
                                it.editorState.copy(
                                    toastMessage = "Failed to load blueprints: ${result.error.message}",
                                ),
                        )
                    }
                }
            }
        }
    }

    fun loadDeliveryTargets() {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getCronDeliveryTargets() }
                }
            if (result is NetworkResult.Success) {
                _uiState.update {
                    it.copy(editorState = it.editorState.copy(deliveryTargets = result.data.targets))
                }
            }
            // Failure: editor keeps working; the deliver picker falls back to origin/local.
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
                    val job = result.data
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
                                    runContinuity = job.context_from?.contains("self") ?: false,
                                    monitorMode =
                                        when {
                                            !job.monitor_script.isNullOrBlank() -> "script"
                                            !job.monitor_url.isNullOrBlank() -> "url"
                                            else -> "off"
                                        },
                                    monitor_script = job.monitor_script.orEmpty(),
                                    monitor_url = job.monitor_url.orEmpty(),
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
        loadDeliveryTargets()
    }

    fun closeEditor() {
        _uiState.update { it.copy(editorState = CronJobEditorState()) }
    }

    /** Pick a start-from blueprint (null = blank job); seeds its slot defaults. */
    fun selectBlueprint(key: String?) {
        _uiState.update { state ->
            val editor = state.editorState
            val blueprint = key?.let { k -> editor.blueprints.firstOrNull { it.key == k } }
            val values = blueprint?.fields?.associate { it.name to it.defaultText }.orEmpty()
            state.copy(
                editorState =
                    editor.copy(
                        selectedBlueprintKey = key,
                        blueprintValues = values,
                        toastMessage = null,
                    ),
            )
        }
    }

    fun updateBlueprintValue(
        name: String,
        value: String,
    ) {
        _uiState.update { state ->
            state.copy(
                editorState =
                    state.editorState.copy(
                        blueprintValues = state.editorState.blueprintValues + (name to value),
                        toastMessage = null,
                    ),
            )
        }
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
        if (editor.selectedBlueprintKey == null && editor.schedule.isBlank()) {
            _uiState.update { it.copy(editorState = editor.copy(toastMessage = "Schedule is required")) }
            return
        }
        _uiState.update { it.copy(editorState = editor.copy(isSaving = true)) }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    if (editor.isNew && editor.selectedBlueprintKey != null) {
                        safeApiCall {
                            ApiClient.hermesApi.instantiateBlueprint(
                                InstantiateBlueprintRequest(
                                    blueprint = editor.selectedBlueprintKey,
                                    values = editor.blueprintValues,
                                ),
                            )
                        }
                    } else if (editor.isNew) {
                        createNewJobWithMonitor(editor)
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
                        // Run continuity: send ["self"] when on, null (clears) when off.
                        updates["context_from"] = if (editor.runContinuity) listOf("self") else null
                        // Blank clears the monitor source on the backend (empty
                        // string or null both mean "clear the field").
                        updates["monitor_script"] = editor.monitor_script.ifBlank { null }
                        updates["monitor_url"] = editor.monitor_url.ifBlank { null }
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

    /**
     * Create a blank job, then apply monitor mode via a follow-up PUT.
     *
     * The backend REST create model (CronJobCreate) does not carry
     * monitor_script/monitor_url yet — a POST alone would silently drop them.
     * The PUT updates path accepts them (verified against web_server.py
     * _update_cron_job_sync / cron.jobs.update_job), so create first, then
     * apply. If the monitor update fails, the just-created job is rolled back
     * (deleted) — a half-configured job is worse than no job.
     */
    private suspend fun createNewJobWithMonitor(editor: CronJobEditorState): NetworkResult<CronJob> {
        val createdResult =
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
                        context_from = if (editor.runContinuity) listOf("self") else null,
                        monitor_script = editor.monitor_script.ifBlank { null },
                        monitor_url = editor.monitor_url.ifBlank { null },
                    ),
                )
            }
        val monitorUpdates = editorMonitorUpdates(editor)
        if (createdResult !is NetworkResult.Success || monitorUpdates.isEmpty()) {
            return createdResult
        }
        val applyResult =
            safeApiCall {
                ApiClient.hermesApi.updateCronJob(
                    createdResult.data.id,
                    UpdateCronJobRequest(updates = monitorUpdates.mapValues { it.value.toJsonElement() }),
                )
            }
        if (applyResult is NetworkResult.Success) {
            return createdResult
        }
        // Roll back: the job exists but is missing its monitor source.
        safeApiCall { ApiClient.hermesApi.deleteCronJob(createdResult.data.id) }
        return applyResult
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
            "run_continuity" -> copy(runContinuity = value.toBooleanStrictOrNull() ?: false)
            // Monitor fields are mutually exclusive: entering one clears the other.
            "monitor_script" -> copy(monitor_script = value, monitor_url = if (value.isNotBlank()) "" else monitor_url)
            "monitor_url" -> copy(monitor_url = value, monitor_script = if (value.isNotBlank()) "" else monitor_script)
            else -> this
        }

    fun toggleRunContinuity() {
        _uiState.update {
            it.copy(
                editorState =
                    it.editorState.copy(
                        runContinuity = !it.editorState.runContinuity,
                        toastMessage = null,
                    ),
            )
        }
    }

    fun toggleNoAgent() {
        _uiState.update {
            it.copy(
                editorState =
                    it.editorState.copy(
                        no_agent = !it.editorState.no_agent,
                        // Monitor mode is incompatible with no_agent on the backend
                        // (cron/jobs.py _validate_job_mode_invariants) — clear it so
                        // a stale value can't 400 the save. Clear when turning
                        // no_agent ON (old value false).
                        monitorMode = if (!it.editorState.no_agent) "off" else it.editorState.monitorMode,
                        monitor_script = if (!it.editorState.no_agent) "" else it.editorState.monitor_script,
                        monitor_url = if (!it.editorState.no_agent) "" else it.editorState.monitor_url,
                    ),
            )
        }
    }

    /**
     * Switch the editor's monitor mode. Selecting a different source clears
     * the other one (backend invariant: monitor_script XOR monitor_url);
     * selecting "off" clears both.
     */
    fun setMonitorMode(mode: String) {
        _uiState.update { state ->
            val editor = state.editorState
            state.copy(
                editorState =
                    editor.copy(
                        monitorMode = mode,
                        monitor_script = if (mode != "script") "" else editor.monitor_script,
                        monitor_url = if (mode != "url") "" else editor.monitor_url,
                    ),
            )
        }
    }

    /** Monitor fields configured in the editor (at most one is non-blank). */
    private fun editorMonitorUpdates(editor: CronJobEditorState): Map<String, String> {
        return buildMap {
            if (editor.monitor_script.isNotBlank()) {
                put("monitor_script", editor.monitor_script.trim())
            }
            if (editor.monitor_url.isNotBlank()) {
                put("monitor_url", editor.monitor_url.trim())
            }
        }
    }
}
