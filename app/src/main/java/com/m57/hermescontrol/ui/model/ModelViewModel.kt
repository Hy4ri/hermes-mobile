package com.m57.hermescontrol.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.AuxiliaryTaskAssignment
import com.m57.hermescontrol.data.model.MoaConfigResponse
import com.m57.hermescontrol.data.model.ModelAssignmentRequest
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.UpdateProfileModelRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AuxTaskInfo(
    val key: String,
    val label: String,
    val hint: String,
)

val AUX_TASKS =
    listOf(
        AuxTaskInfo("vision", "Vision", "image/video analysis"),
        AuxTaskInfo("session_search", "Session search", "past conversation search"),
        AuxTaskInfo("summarizer", "Compression", "context compression"),
        AuxTaskInfo("code_exec", "Code execution", "runtime for code blocks"),
        AuxTaskInfo("computer_use", "Computer use", "desktop/browser automation"),
        AuxTaskInfo("web_search", "Web search", "live search queries"),
    )

data class ModelUiState(
    val isLoading: Boolean = false,
    val providers: List<ModelProvider> = emptyList(),
    val activeProfile: ProfileInfo? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val pinnedModels: List<PinnedModel> = emptyList(),
    // Main model
    val mainModelProvider: String = "",
    val mainModelModel: String = "",
    // Auxiliary tasks
    val auxTasks: List<AuxiliaryTaskAssignment> = emptyList(),
    // MOA config
    val moaConfig: MoaConfigResponse? = null,
    // Dialog state
    val showAuxDialog: Boolean = false,
    val showMoaDialog: Boolean = false,
    val showMainModelPicker: Boolean = false,
    val showAuxModelPicker: Boolean = false,
    val auxPickerTask: String = "",
    // Model picker busy (for expensive model confirmation)
    val modelPickerBusy: Boolean = false,
    val modelPickerConfirmMessage: String? = null,
    // Pending resolve
    val pendingModelPickerResolve: ((Boolean) -> Unit)? = null,
)

class ModelViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ModelUiState())
    val uiState: StateFlow<ModelUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(pinnedModels = AuthManager.getPinnedModels()) }
    }

    fun loadAll(refresh: Boolean = false) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val optionsDeferred =
                async(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getModelOptions(refresh = refresh) }
                }
            val activeProfileDeferred =
                async(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getActiveProfile() }
                }
            val profilesDeferred =
                async(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getProfiles() }
                }
            val auxDeferred =
                async(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getAuxiliaryModels() }
                }
            val moaDeferred =
                async(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getMoaModels() }
                }

            val optionsResult = optionsDeferred.await()
            val activeProfileResult = activeProfileDeferred.await()
            val profilesResult = profilesDeferred.await()
            val auxResult = auxDeferred.await()
            val moaResult = moaDeferred.await()

            when (optionsResult) {
                is NetworkResult.Success -> {
                    val activeName =
                        if (activeProfileResult is NetworkResult.Success) {
                            activeProfileResult.data.active
                        } else {
                            null
                        }
                    val activeProfile =
                        if (profilesResult is NetworkResult.Success && activeName != null) {
                            profilesResult.data.profiles.find { it.name == activeName }
                        } else {
                            null
                        }
                    val mainModel =
                        if (auxResult is NetworkResult.Success) {
                            Pair(auxResult.data.main.provider, auxResult.data.main.model)
                        } else {
                            Pair("", "")
                        }
                    val auxTasks =
                        if (auxResult is NetworkResult.Success) {
                            auxResult.data.tasks
                        } else {
                            emptyList()
                        }
                    val moaConfig =
                        if (moaResult is NetworkResult.Success) {
                            moaResult.data
                        } else {
                            null
                        }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            providers = optionsResult.data.providers.orEmpty(),
                            activeProfile = activeProfile,
                            pinnedModels = AuthManager.getPinnedModels(),
                            mainModelProvider = mainModel.first,
                            mainModelModel = mainModel.second,
                            auxTasks = auxTasks,
                            moaConfig = moaConfig,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load model options: ${optionsResult.error.message}",
                        )
                    }
                }
            }
        }
    }

    /** Open the main model picker dialog. */
    fun openMainModelPicker() {
        _uiState.update { it.copy(showMainModelPicker = true) }
    }

    fun closeMainModelPicker() {
        _uiState.update { it.copy(showMainModelPicker = false) }
    }

    /** Set the main model via /api/model/set. */
    fun setMainModel(
        provider: String,
        model: String,
    ) {
        _uiState.update { it.copy(modelPickerBusy = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.setModelAssignment(
                            ModelAssignmentRequest(
                                scope = "main",
                                provider = provider,
                                model = model,
                            ),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val data = result.data
                    if (data.confirm_required == true) {
                        _uiState.update {
                            it.copy(
                                modelPickerConfirmMessage =
                                    data.confirm_message ?: "This model is expensive. Continue?",
                                pendingModelPickerResolve = { confirmed ->
                                    if (confirmed) {
                                        setMainModelWithConfirm(provider, model)
                                    }
                                    _uiState.update {
                                        it.copy(
                                            modelPickerConfirmMessage = null,
                                            pendingModelPickerResolve = null,
                                        )
                                    }
                                },
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                toastMessage = "Main model set to $provider / $model",
                                showMainModelPicker = false,
                                modelPickerBusy = false,
                            )
                        }
                        loadAll()
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Failed to set main model: ${result.error.message}",
                            modelPickerBusy = false,
                        )
                    }
                }
            }
        }
    }

    private fun setMainModelWithConfirm(
        provider: String,
        model: String,
    ) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.setModelAssignment(
                            ModelAssignmentRequest(
                                confirm_expensive_model = true,
                                scope = "main",
                                provider = provider,
                                model = model,
                            ),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Main model set to $provider / $model",
                            showMainModelPicker = false,
                            modelPickerBusy = false,
                        )
                    }
                    loadAll()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Failed to set main model: ${result.error.message}",
                            modelPickerBusy = false,
                        )
                    }
                }
            }
        }
    }

    fun dismissModelPickerConfirm() {
        _uiState.update {
            it.copy(
                modelPickerConfirmMessage = null,
                pendingModelPickerResolve = null,
            )
        }
        _uiState.value.pendingModelPickerResolve?.invoke(false)
    }

    fun confirmModelPickerExpensive() {
        _uiState.value.pendingModelPickerResolve?.invoke(true)
    }

    // -------------------- Auxiliary tasks --------------------

    fun openAuxDialog() {
        _uiState.update { it.copy(showAuxDialog = true) }
    }

    fun closeAuxDialog() {
        _uiState.update { it.copy(showAuxDialog = false) }
    }

    fun openAuxModelPicker(task: String) {
        _uiState.update { it.copy(showAuxModelPicker = true, auxPickerTask = task) }
    }

    fun closeAuxModelPicker() {
        _uiState.update { it.copy(showAuxModelPicker = false, auxPickerTask = "") }
    }

    /** Set an auxiliary task model override. */
    fun setAuxTask(
        task: String,
        provider: String,
        model: String,
    ) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.setModelAssignment(
                            ModelAssignmentRequest(
                                scope = "auxiliary",
                                task = task,
                                provider = provider,
                                model = model,
                            ),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Aux task '$task' set to $provider / $model",
                            showAuxModelPicker = false,
                            auxPickerTask = "",
                        )
                    }
                    loadAll()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Failed to set aux task: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    /** Reset all auxiliary tasks to auto. */
    fun resetAllAuxTasks() {
        viewModelScope.launch {
            _uiState.update { it.copy(modelPickerBusy = true) }
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.setModelAssignment(
                            ModelAssignmentRequest(
                                scope = "auxiliary",
                                task = "__reset__",
                                provider = "",
                                model = "",
                            ),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(toastMessage = "All auxiliary tasks reset to auto", modelPickerBusy = false)
                    }
                    loadAll()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Failed to reset aux tasks: ${result.error.message}",
                            modelPickerBusy = false,
                        )
                    }
                }
            }
        }
    }

    // -------------------- MOA config --------------------

    fun openMoaDialog() {
        _uiState.update { it.copy(showMoaDialog = true) }
    }

    fun closeMoaDialog() {
        _uiState.update { it.copy(showMoaDialog = false) }
    }

    fun saveMoaConfig(config: MoaConfigResponse) {
        viewModelScope.launch {
            _uiState.update { it.copy(modelPickerBusy = true) }
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.saveMoaModels(config)
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            moaConfig = result.data,
                            toastMessage = "MOA config saved",
                            showMoaDialog = false,
                            modelPickerBusy = false,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Failed to save MOA config: ${result.error.message}",
                            modelPickerBusy = false,
                        )
                    }
                }
            }
        }
    }

    // -------------------- Profile model selection (existing) --------------------

    fun selectModel(
        providerSlug: String,
        modelName: String,
    ) {
        viewModelScope.launch {
            val activeProfileNameResResult =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getActiveProfile() }
                }
            when (activeProfileNameResResult) {
                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage =
                                "Failed to fetch active profile: ${activeProfileNameResResult.error.message}",
                        )
                    }
                    return@launch
                }

                is NetworkResult.Success -> {
                    val activeProfileName = activeProfileNameResResult.data.active
                    val updateResResult =
                        withContext(Dispatchers.IO) {
                            safeApiCall {
                                ApiClient.hermesApi.updateProfileModel(
                                    activeProfileName,
                                    UpdateProfileModelRequest(providerSlug, modelName),
                                )
                            }
                        }
                    when (updateResResult) {
                        is NetworkResult.Success -> {
                            _uiState.update { it.copy(toastMessage = "Successfully set profile model to $modelName") }
                            loadAll()
                        }

                        is NetworkResult.Failure -> {
                            _uiState.update {
                                it.copy(
                                    toastMessage = "Failed to set profile model: ${updateResResult.error.message}",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    companion object {
        private const val MAX_PINNED_MODELS = 15
    }

    fun pinModel(
        providerSlug: String,
        modelName: String,
    ) {
        val currentPinned = _uiState.value.pinnedModels.toMutableList()
        val newPin = PinnedModel(providerSlug, modelName)
        if (currentPinned.contains(newPin)) return
        if (currentPinned.size >= MAX_PINNED_MODELS) {
            _uiState.update {
                it.copy(toastMessage = "Maximum of $MAX_PINNED_MODELS pinned models reached")
            }
            return
        }
        currentPinned.add(newPin)
        AuthManager.savePinnedModels(currentPinned)
        _uiState.update { it.copy(pinnedModels = currentPinned) }
    }

    fun unpinModel(
        providerSlug: String,
        modelName: String,
    ) {
        val currentPinned = _uiState.value.pinnedModels.toMutableList()
        val pinToRemove = PinnedModel(providerSlug, modelName)
        if (currentPinned.remove(pinToRemove)) {
            AuthManager.savePinnedModels(currentPinned)
            _uiState.update { it.copy(pinnedModels = currentPinned) }
        }
    }
}
