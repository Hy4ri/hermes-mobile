package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.authlogin.AuthProviderOption
import com.m57.hermescontrol.ui.authlogin.BrowserLoginResult
import com.m57.hermescontrol.ui.authlogin.GatewayAuthInfo
import com.m57.hermescontrol.ui.authlogin.preferredProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Gateway calls behind the in-chat re-login dialog. */
interface ReloginBackend {
    suspend fun probe(): GatewayAuthInfo?

    suspend fun lastProvider(): String?

    /** Returns null on success, else a failure message. */
    suspend fun passwordLogin(
        username: String,
        password: String,
        provider: String,
    ): String?

    suspend fun browserLogin(
        provider: String,
        openBrowser: (String) -> Unit,
    ): BrowserLoginResult
}

data class ReloginUiState(
    val visible: Boolean = false,
    val probing: Boolean = false,
    val unreachable: Boolean = false,
    val providers: List<AuthProviderOption> = emptyList(),
    val selectedProvider: String? = null,
    val browserSupported: Boolean = true,
    val submitting: Boolean = false,
    val browserWaiting: Boolean = false,
    val error: String? = null,
) {
    val selected: AuthProviderOption? get() = providers.firstOrNull { it.name == selectedProvider }

    /** A gateway without a provider list (old or ungated) keeps the legacy password form. */
    val usesPassword: Boolean get() = selected?.supportsPassword ?: true

    val busy: Boolean get() = probing || submitting || browserWaiting
}

/**
 * State of the in-chat re-login dialog (issue #1641). It asks the gateway which providers it
 * offers, preselects the one the expired session used, and signs in with the password form or
 * the system browser to match, the same way the login screen does.
 */
class ChatReloginController(
    private val scope: CoroutineScope,
    private val backend: ReloginBackend,
    private val browserErrorText: (BrowserLoginResult) -> String?,
    private val onAuthenticated: () -> Unit,
) {
    private val _state = MutableStateFlow(ReloginUiState())
    val state: StateFlow<ReloginUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun open() {
        if (_state.value.visible) return
        _state.value = ReloginUiState(visible = true)
        probe()
    }

    fun retry() {
        val current = _state.value
        if (current.visible && current.unreachable) probe()
    }

    fun dismiss() {
        job?.cancel()
        job = null
        _state.value = ReloginUiState()
    }

    fun selectProvider(name: String) {
        val current = _state.value
        if (current.busy || current.providers.none { it.name == name }) return
        _state.update { it.copy(selectedProvider = name, error = null) }
    }

    fun submitPassword(
        username: String,
        password: String,
    ) {
        val current = _state.value
        if (!current.visible || current.busy || current.unreachable || !current.usesPassword) return
        val provider = current.selectedProvider ?: DEFAULT_PASSWORD_PROVIDER
        _state.update { it.copy(submitting = true, error = null) }
        job =
            scope.launch {
                val error = backend.passwordLogin(username, password, provider)
                if (error == null) {
                    finish()
                } else {
                    _state.update { it.copy(submitting = false, error = error) }
                }
            }
    }

    fun startBrowser(openBrowser: (String) -> Unit) {
        val current = _state.value
        val provider = current.selected ?: return
        if (current.busy || provider.supportsPassword || !current.browserSupported) return
        _state.update { it.copy(browserWaiting = true, error = null) }
        job =
            scope.launch {
                val result = backend.browserLogin(provider.name, openBrowser)
                if (result is BrowserLoginResult.Success) {
                    finish()
                } else {
                    _state.update { it.copy(browserWaiting = false, error = browserErrorText(result)) }
                }
            }
    }

    private fun probe() {
        job?.cancel()
        _state.update { it.copy(probing = true, unreachable = false, error = null) }
        job =
            scope.launch {
                val info = backend.probe()
                if (info == null) {
                    _state.update { it.copy(probing = false, unreachable = true) }
                    return@launch
                }
                val providers = info.providers
                val selected =
                    if (providers.size >
                        1
                    ) {
                        preferredProvider(providers, backend.lastProvider())
                    } else {
                        providers.firstOrNull()
                    }
                _state.update {
                    it.copy(
                        probing = false,
                        providers = providers,
                        selectedProvider = selected?.name,
                        browserSupported = info.browserSupported,
                    )
                }
            }
    }

    private fun finish() {
        _state.value = ReloginUiState()
        onAuthenticated()
    }

    private companion object {
        const val DEFAULT_PASSWORD_PROVIDER = "basic"
    }
}
