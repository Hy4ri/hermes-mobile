package com.m57.hermescontrol.ui.webhooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.SwrCache
import com.m57.hermescontrol.data.model.CreateWebhookRequest
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.data.model.WebhookSubscription
import com.m57.hermescontrol.data.model.WebhookToggleSubscriptionRequest
import com.m57.hermescontrol.data.model.WebhooksResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.remote.safeApiRead
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import com.m57.hermescontrol.ui.common.safeLaunchSwrLoad
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WebhooksUiState(
    val isLoading: Boolean = false,
    val isActionRunning: Boolean = false,
    val enabled: Boolean = false,
    val baseUrl: String? = null,
    val subscriptions: List<WebhookSubscription> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val toastIsError: Boolean = false,
    // Create form state
    val showCreateDialog: Boolean = false,
    val createFormName: String = "",
    val createFormDescription: String = "",
    val createFormEvents: String = "",
    val createFormDeliver: String = "log",
    val createFormSecret: String = "",
    val createFormError: String? = null,
    // Delete confirmation state
    val deleteTarget: WebhookSubscription? = null,
    val isDeleting: Boolean = false,
    // Toggle tracking
    val togglingName: String? = null,
)

class WebhooksViewModel(
    // Injectable so tests drive IO on the virtual clock; the default keeps `WebhooksViewModel()` callers unchanged.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(WebhooksUiState())
    val uiState: StateFlow<WebhooksUiState> = _uiState.asStateFlow()

    private val webhooksCache = SwrCache<String, WebhooksResponse>()
    private var toggleGeneration = 0L
    private var toggleJob: Job? = null

    fun clearScopeOwnedState() {
        // #1550: a stale toggle must neither lock nor roll back a newly selected profile.
        toggleGeneration++
        toggleJob?.cancel()
        toggleJob = null
        _uiState.update {
            it.copy(
                isLoading = false,
                isActionRunning = false,
                enabled = false,
                baseUrl = null,
                subscriptions = emptyList(),
                errorMessage = null,
                deleteTarget = null,
                togglingName = null,
            )
        }
    }

    fun loadWebhooks(forceRefresh: Boolean = false) {
        safeLaunchSwrLoad(
            cache = webhooksCache,
            forceRefresh = forceRefresh,
            onCacheHit = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        enabled = data.enabled,
                        baseUrl = data.base_url,
                        subscriptions = data.subscriptions.orEmpty(),
                        errorMessage = null,
                    )
                }
            },
            apiCall = { safeApiRead { ApiClient.hermesApi.getWebhooks() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                val body = data
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        enabled = body.enabled,
                        baseUrl = body.base_url,
                        subscriptions = body.subscriptions.orEmpty(),
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load webhooks: $errorMsg",
                    )
                }
            },
        )
    }

    fun toggleWebhooks(targetEnabled: Boolean) {
        if (_uiState.value.isActionRunning) return

        val originalEnabled = _uiState.value.enabled
        val capturedScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
        val capturedProfile = AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID
        val capturedApi = ApiClient.hermesApi
        val generation = ++toggleGeneration

        _uiState.update {
            it.copy(
                isActionRunning = true,
                enabled = targetEnabled,
            )
        }

        toggleJob =
            viewModelScope.launch {
                try {
                    val result =
                        withContext(ioDispatcher) {
                            safeApiCall(retries = 0) {
                                capturedApi.configurePlatform(
                                    platformId = "webhook",
                                    config =
                                        MessagingPlatformUpdate(
                                            enabled = targetEnabled,
                                            profile = capturedProfile,
                                        ),
                                )
                            }
                        }

                    val currentScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
                    val currentProfile = AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID
                    if (generation != toggleGeneration || currentScope != capturedScope ||
                        currentProfile != capturedProfile
                    ) {
                        return@launch
                    }

                    when (result) {
                        is NetworkResult.Success -> {
                            if (result.data.ok) {
                                _uiState.update {
                                    it.copy(
                                        toastMessage =
                                            "Webhooks global status ${if (targetEnabled) "enabled" else "disabled"}",
                                    )
                                }
                                loadWebhooks(forceRefresh = true)
                            } else {
                                _uiState.update {
                                    it.copy(
                                        enabled = originalEnabled,
                                        toastIsError = true,
                                        toastMessage = "Failed to toggle webhooks: Gateway refused update",
                                    )
                                }
                            }
                        }

                        is NetworkResult.Failure -> {
                            _uiState.update {
                                it.copy(
                                    enabled = originalEnabled,
                                    toastIsError = true,
                                    toastMessage = "Failed to toggle webhooks: ${result.error.message}",
                                )
                            }
                        }
                    }
                } finally {
                    if (generation == toggleGeneration) {
                        _uiState.update { it.copy(isActionRunning = false) }
                        toggleJob = null
                    }
                }
            }
    }

    // ── Create dialog ────────────────────────────────────────────────

    fun showCreateDialog() {
        _uiState.update {
            it.copy(
                showCreateDialog = true,
                createFormName = "",
                createFormDescription = "",
                createFormEvents = "",
                createFormDeliver = "log",
                createFormSecret = "",
                createFormError = null,
            )
        }
    }

    fun dismissCreateDialog() {
        _uiState.update { it.copy(showCreateDialog = false, createFormError = null) }
    }

    fun updateCreateFormName(value: String) {
        _uiState.update { it.copy(createFormName = value, createFormError = null) }
    }

    fun updateCreateFormDescription(value: String) {
        _uiState.update { it.copy(createFormDescription = value) }
    }

    fun updateCreateFormEvents(value: String) {
        _uiState.update { it.copy(createFormEvents = value) }
    }

    fun updateCreateFormDeliver(value: String) {
        _uiState.update { it.copy(createFormDeliver = value) }
    }

    fun updateCreateFormSecret(value: String) {
        _uiState.update { it.copy(createFormSecret = value) }
    }

    fun createSubscription() {
        val state = _uiState.value
        val name = state.createFormName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(createFormError = "Name is required") }
            return
        }

        val events =
            state.createFormEvents
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        _uiState.update { it.copy(isActionRunning = true, createFormError = null) }

        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.createWebhook(
                            CreateWebhookRequest(
                                name = name,
                                description = state.createFormDescription.trim().ifEmpty { null },
                                events = events.ifEmpty { null },
                                deliver = state.createFormDeliver.ifBlank { null },
                                secret = state.createFormSecret.trim().ifEmpty { null },
                            ),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            showCreateDialog = false,
                            toastMessage = "Subscription \"$name\" created",
                        )
                    }
                    loadWebhooks(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            createFormError = result.error.message,
                        )
                    }
                }
            }
        }
    }

    // ── Per-subscription toggle ──────────────────────────────────────

    fun toggleSubscription(
        name: String,
        enabled: Boolean,
    ) {
        _uiState.update { it.copy(togglingName = name) }

        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.setWebhookEnabled(
                            name = name,
                            body = WebhookToggleSubscriptionRequest(enabled = enabled),
                        )
                    }
                }
            _uiState.update { it.copy(togglingName = null) }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Subscription \"$name\" ${if (enabled) "enabled" else "disabled"}",
                        )
                    }
                    loadWebhooks(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastIsError = true,
                            toastMessage = "Failed to toggle \"$name\": ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Delete confirmation ─────────────────────────────────────────

    fun promptDeleteSubscription(sub: WebhookSubscription) {
        _uiState.update { it.copy(deleteTarget = sub) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(deleteTarget = null) }
    }

    fun confirmDeleteSubscription() {
        val target = _uiState.value.deleteTarget ?: return
        _uiState.update { it.copy(isDeleting = true) }

        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.deleteWebhook(target.name) }
                }
            _uiState.update { it.copy(isDeleting = false, deleteTarget = null) }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(toastMessage = "Subscription \"${target.name}\" deleted")
                    }
                    loadWebhooks(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastIsError = true,
                            toastMessage = "Failed to delete \"${target.name}\": ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null, toastIsError = false) }
    }
}
