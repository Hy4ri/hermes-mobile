package com.m57.hermescontrol.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Scoped ViewModel managing session connectors / integrations lifecycle (issue #1091).
 *
 * Hosts [ChatConnectorsDelegate] across configuration changes, wires [ActiveSessionHolder]
 * and server/profile context transitions to invalidate context on changes, and forwards
 * lifecycle and browser launch events safely.
 */
class ChatConnectorsViewModel(
    delegate: ChatConnectorsDelegate? = null,
) : ViewModel() {
    private val delegateInstance: ChatConnectorsDelegate =
        delegate ?: ChatConnectorsDelegate(
            scope = viewModelScope,
            runtimeSessionId = { ActiveSessionHolder.activeSessionId.value },
        )

    val uiState: StateFlow<ChatConnectorsUiState> = delegateInstance.uiState

    init {
        var lastContext: Triple<String?, String?, String?>? = null

        viewModelScope.launch {
            combine(
                ActiveSessionHolder.activeSessionId,
                AuthManager.activeProfileId,
                AuthManager.baseUrlFlow,
            ) { sessionId, profileId, baseUrl ->
                Triple(sessionId, profileId, baseUrl)
            }.collect { current ->
                if (lastContext == null || current != lastContext) {
                    lastContext = current
                    delegateInstance.onActiveSessionChanged(current.first)
                }
            }
        }

        viewModelScope.launch {
            HermesWsClient.connectionStatus
                .filter { it == ConnectionStatus.CONNECTED }
                .collect {
                    delegateInstance.onTransportReconnected()
                }
        }
    }

    fun show() = delegateInstance.show()

    fun hide() = delegateInstance.hide()

    fun onResume() = delegateInstance.onResume()

    fun onPause() = delegateInstance.onPause()

    fun refresh(force: Boolean = false) = delegateInstance.refresh(force)

    fun connect(
        slug: String,
        reconnect: Boolean = false,
    ) = delegateInstance.connect(slug, reconnect)

    fun takeBrowserEvent(eventId: Long? = null): ConnectBrowserEvent? = delegateInstance.takeBrowserEvent(eventId)

    fun launchError(message: String) = delegateInstance.launchError(message)

    fun clearError() = delegateInstance.clearError()

    override fun onCleared() {
        super.onCleared()
        delegateInstance.destroy()
    }
}
