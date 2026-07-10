package com.m57.hermescontrol.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.MessagingPlatform
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.data.model.TelegramOnboardingApplyRequest
import com.m57.hermescontrol.data.model.TelegramOnboardingStartRequest
import com.m57.hermescontrol.data.model.TelegramOnboardingStartResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingPhase {
    IDLE,
    STARTING,
    WAITING,
    READY,
    APPLYING,
}

data class ChannelsUiState(
    val isLoading: Boolean = false,
    val platforms: List<MessagingPlatform> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val restartNeeded: Boolean = false,
    val isRestarting: Boolean = false,
    val togglingId: String? = null,
    val testingId: String? = null,
    val removingId: String? = null,
    val gatewayStartCommand: String = "hermes gateway start",
    // Telegram onboarding
    val onboardingPhase: OnboardingPhase = OnboardingPhase.IDLE,
    val onboardingSetup: TelegramOnboardingStartResponse? = null,
    val onboardingQrDataUrl: String? = null,
    val onboardingBotUsername: String? = null,
    val onboardingAllowedIds: List<String> = emptyList(),
    val onboardingDetectedOwnerId: String? = null,
    val onboardingNewAllowedId: String = "",
    val onboardingError: String? = null,
    val onboardingExpiresIn: String = "",
    // Admin section
    val envPath: String = "~/.hermes/.env",
)

class ChannelsViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ChannelsUiState())
    val uiState: StateFlow<ChannelsUiState> = _uiState.asStateFlow()

    private var launchJob: Job? = null

    fun loadPlatforms() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getMessagingPlatforms() } },
            onStart = {
                _uiState.update {
                    it.copy(isLoading = true, errorMessage = null)
                }
            },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        platforms = data?.platforms.orEmpty(),
                        gatewayStartCommand = data?.gatewayStartCommand ?: "hermes gateway start",
                        envPath = data?.envPath ?: "~/.hermes/.env",
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load platforms: $errorMsg",
                    )
                }
            },
        )
    }

    /** Toggle a platform's enabled state without a full reload. */
    fun togglePlatform(
        platformId: String,
        enabled: Boolean,
    ) {
        _uiState.update { it.copy(togglingId = platformId) }
        safeLaunchLoad(
            apiCall = {
                safeApiCall {
                    ApiClient.hermesApi.configurePlatform(
                        platformId,
                        MessagingPlatformUpdate(enabled = enabled),
                    )
                }
            },
            onStart = {},
            onSuccess = {
                _uiState.update { state ->
                    state.copy(
                        platforms =
                            state.platforms.map { p ->
                                if (p.id == platformId) {
                                    p.copy(
                                        enabled = enabled,
                                        state = if (enabled) "pending_restart" else "disabled",
                                    )
                                } else {
                                    p
                                }
                            },
                        restartNeeded = true,
                        togglingId = null,
                    )
                }
            },
            onError = { error ->
                _uiState.update {
                    it.copy(
                        togglingId = null,
                        toastMessage = "Toggle failed: $error",
                    )
                }
            },
        )
    }

    /** Remove an entire platform. */
    fun removePlatform(platformId: String) {
        _uiState.update { it.copy(removingId = platformId) }
        safeLaunchLoad(
            apiCall = {
                safeApiCall {
                    ApiClient.hermesApi.removeMessagingPlatform(platformId)
                }
            },
            onStart = {},
            onSuccess = {
                _uiState.update { state ->
                    state.copy(
                        platforms = state.platforms.filter { it.id != platformId },
                        removingId = null,
                        toastMessage = "Platform removed",
                    )
                }
            },
            onError = { error ->
                _uiState.update {
                    it.copy(
                        removingId = null,
                        toastMessage = "Failed to remove: $error",
                    )
                }
            },
        )
    }

    /** Configure a platform's env vars and/or settings. Reloads on success. */
    fun configurePlatform(
        platformId: String,
        update: MessagingPlatformUpdate,
    ) {
        safeLaunchLoad(
            apiCall = {
                safeApiCall {
                    ApiClient.hermesApi.configurePlatform(platformId, update)
                }
            },
            onStart = {
                _uiState.update {
                    it.copy(isLoading = true, errorMessage = null)
                }
            },
            onSuccess = {
                val message = "$platformId configured successfully — restart the gateway for changes to take effect"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        restartNeeded = true,
                        toastMessage = message,
                    )
                }
                loadPlatforms()
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = "Failed to configure: $errorMsg",
                    )
                }
            },
        )
    }

    /** Convenience: configure a platform's env vars via a Map<String,String>. */
    fun configurePlatform(
        platformId: String,
        config: Map<String, String>,
    ) {
        configurePlatform(platformId, MessagingPlatformUpdate(env = config))
    }

    /** Test connectivity for a platform. */
    fun testPlatform(platformId: String) {
        _uiState.update { it.copy(testingId = platformId) }
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.testMessagingPlatform(platformId) } },
            onStart = {},
            onSuccess = { result ->
                val msg = result?.message ?: "Test complete"
                _uiState.update {
                    it.copy(testingId = null, toastMessage = msg)
                }
            },
            onError = { error ->
                _uiState.update {
                    it.copy(testingId = null, toastMessage = "Test failed: $error")
                }
            },
        )
    }

    /** Restart the whole gateway. Reloads platforms after a 4s delay. */
    fun restartGateway() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.restartGateway() } },
            onStart = {
                _uiState.update {
                    it.copy(isRestarting = true, errorMessage = null)
                }
            },
            onSuccess = {
                _uiState.update {
                    it.copy(isRestarting = false, restartNeeded = false)
                }
                viewModelScope.launch {
                    delay(4000)
                    loadPlatforms()
                }
            },
            onError = { error ->
                _uiState.update {
                    it.copy(
                        isRestarting = false,
                        toastMessage = "Restart failed: $error",
                    )
                }
            },
        )
    }

    fun dismissRestartNeeded() {
        _uiState.update { it.copy(restartNeeded = false) }
    }

    // ── Telegram onboarding ────────────────────────────────────────────────

    fun startTelegramOnboarding() {
        val state = _uiState.value
        if (state.onboardingPhase == OnboardingPhase.STARTING ||
            state.onboardingPhase == OnboardingPhase.WAITING
        ) {
            return
        }
        _uiState.update {
            it.copy(
                onboardingPhase = OnboardingPhase.STARTING,
                onboardingError = null,
                onboardingBotUsername = null,
                onboardingAllowedIds = emptyList(),
                onboardingDetectedOwnerId = null,
                onboardingNewAllowedId = "",
            )
        }
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.startTelegramOnboarding(
                        TelegramOnboardingStartRequest(botName = "Hermes Agent"),
                    )
                }
            _uiState.update {
                when (result) {
                    is com.m57.hermescontrol.data.remote.NetworkResult.Success -> {
                        val setup = result.data
                        it.copy(
                            onboardingPhase = OnboardingPhase.WAITING,
                            onboardingSetup = setup,
                            onboardingQrDataUrl = setup?.qrPayload,
                            onboardingExpiresIn = formatExpiry(setup?.expiresAt),
                        )
                    }

                    is com.m57.hermescontrol.data.remote.NetworkResult.Failure -> {
                        it.copy(
                            onboardingPhase = OnboardingPhase.IDLE,
                            onboardingError = result.error.message,
                        )
                    }
                }
            }
            if (_uiState.value.onboardingPhase == OnboardingPhase.WAITING) {
                pollOnboardingStatus()
            }
        }
    }

    private fun pollOnboardingStatus() {
        launchJob?.cancel()
        launchJob =
            viewModelScope.launch {
                while (_uiState.value.onboardingPhase == OnboardingPhase.WAITING) {
                    delay(2000)
                    val pairingId = _uiState.value.onboardingSetup?.pairingId ?: break
                    val result =
                        safeApiCall {
                            ApiClient.hermesApi.getTelegramOnboardingStatus(pairingId)
                        }
                    when (result) {
                        is com.m57.hermescontrol.data.remote.NetworkResult.Success -> {
                            val status = result.data
                            if (status?.status == "ready") {
                                _uiState.update {
                                    it.copy(
                                        onboardingPhase = OnboardingPhase.READY,
                                        onboardingBotUsername = status.botUsername,
                                        onboardingError = null,
                                    )
                                }
                                val ownerId = status.ownerUserId
                                if (ownerId != null && TELEGRAM_USER_ID_RE.matches(ownerId)) {
                                    _uiState.update {
                                        it.copy(
                                            onboardingDetectedOwnerId = ownerId,
                                            onboardingAllowedIds = listOf(ownerId),
                                        )
                                    }
                                }
                                return@launch
                            }
                            _uiState.update { it.copy(onboardingError = null) }
                        }

                        is com.m57.hermescontrol.data.remote.NetworkResult.Failure -> {
                            val expired = isOnboardingExpired(_uiState.value.onboardingSetup)
                            if (expired) {
                                _uiState.update {
                                    it.copy(
                                        onboardingPhase = OnboardingPhase.IDLE,
                                        onboardingSetup = null,
                                        onboardingQrDataUrl = null,
                                        onboardingError =
                                            "Telegram pairing expired. " +
                                                "Start a new QR setup to try again.",
                                    )
                                }
                                return@launch
                            }
                            _uiState.update {
                                it.copy(
                                    onboardingError = "Still waiting for Telegram. Retrying…",
                                )
                            }
                        }
                    }
                    // Update expiry countdown
                    _uiState.update {
                        it.copy(
                            onboardingExpiresIn = formatExpiry(it.onboardingSetup?.expiresAt),
                        )
                    }
                }
            }
    }

    fun cancelTelegramOnboarding() {
        val pairingId = _uiState.value.onboardingSetup?.pairingId
        launchJob?.cancel()
        if (pairingId != null) {
            viewModelScope.launch {
                safeApiCall {
                    ApiClient.hermesApi.cancelTelegramOnboarding(pairingId)
                }
            }
        }
        resetOnboarding()
    }

    fun setOnboardingNewAllowedId(id: String) {
        _uiState.update { it.copy(onboardingNewAllowedId = id) }
    }

    fun addOnboardingAllowedId() {
        val trimmed = _uiState.value.onboardingNewAllowedId.trim()
        if (!TELEGRAM_USER_ID_RE.matches(trimmed)) {
            _uiState.update {
                it.copy(
                    onboardingError = "Allowed Telegram user IDs must be numeric.",
                )
            }
            return
        }
        _uiState.update { state ->
            val ids = state.onboardingAllowedIds
            state.copy(
                onboardingError = null,
                onboardingNewAllowedId = "",
                onboardingAllowedIds =
                    if (ids.contains(trimmed)) ids else ids + trimmed,
            )
        }
    }

    fun removeOnboardingAllowedId(id: String) {
        _uiState.update { state ->
            state.copy(
                onboardingAllowedIds = state.onboardingAllowedIds.filter { it != id },
            )
        }
    }

    fun applyTelegramOnboarding() {
        val setup = _uiState.value.onboardingSetup ?: return
        val allowedIds = _uiState.value.onboardingAllowedIds
        if (allowedIds.isEmpty()) {
            _uiState.update {
                it.copy(
                    onboardingError = "Add at least one allowed Telegram user ID.",
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                onboardingPhase = OnboardingPhase.APPLYING,
                onboardingError = null,
            )
        }
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.applyTelegramOnboarding(
                        setup.pairingId,
                        TelegramOnboardingApplyRequest(
                            allowedUserIds = allowedIds,
                        ),
                    )
                }
            when (result) {
                is com.m57.hermescontrol.data.remote.NetworkResult.Success -> {
                    val applyResult = result.data
                    _uiState.update {
                        it.copy(
                            toastMessage = "Telegram saved. Restarting gateway…",
                        )
                    }
                    resetOnboarding()
                    if (applyResult?.needsRestart == true) {
                        restartGateway()
                    } else {
                        loadPlatforms()
                    }
                }

                is com.m57.hermescontrol.data.remote.NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            onboardingPhase = OnboardingPhase.READY,
                            onboardingError = result.error.message,
                        )
                    }
                }
            }
        }
    }

    private fun resetOnboarding() {
        launchJob?.cancel()
        _uiState.update {
            it.copy(
                onboardingPhase = OnboardingPhase.IDLE,
                onboardingSetup = null,
                onboardingQrDataUrl = null,
                onboardingBotUsername = null,
                onboardingAllowedIds = emptyList(),
                onboardingDetectedOwnerId = null,
                onboardingNewAllowedId = "",
                onboardingError = null,
                onboardingExpiresIn = "",
            )
        }
    }

    fun dismissOnboardingError() {
        _uiState.update { it.copy(onboardingError = null) }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}

private val TELEGRAM_USER_ID_RE = Regex("^\\d+$")

private val isoTimestampParser =
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

private fun formatExpiry(expiresAt: String?): String {
    if (expiresAt == null) return ""
    val ms =
        try {
            isoTimestampParser
                .parse(expiresAt)
                ?.time
                ?.minus(System.currentTimeMillis())
        } catch (_: Exception) {
            null
        }
            ?: return ""
    if (ms <= 0) return "expired"
    val seconds = (ms / 1000).toInt()
    val mins = seconds / 60
    val secs = seconds % 60
    return "$mins:${secs.toString().padStart(2, '0')}"
}

private fun isOnboardingExpired(setup: com.m57.hermescontrol.data.model.TelegramOnboardingStartResponse?): Boolean {
    val expiresAt = setup?.expiresAt ?: return false
    val ms =
        try {
            isoTimestampParser
                .parse(expiresAt)
                ?.time
        } catch (_: Exception) {
            null
        }
            ?: return false
    return System.currentTimeMillis() >= ms
}
