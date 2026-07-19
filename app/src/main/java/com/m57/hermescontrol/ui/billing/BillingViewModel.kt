package com.m57.hermescontrol.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.SubscriptionChangeRequest
import com.m57.hermescontrol.data.model.SubscriptionStateResponse
import com.m57.hermescontrol.data.model.UsageBarsResponse
import com.m57.hermescontrol.data.ws.BillingRepository
import com.m57.hermescontrol.data.ws.HermesWsClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Billing / Plan screen (issue #628). Pulls plan state + usage bars
 * over the WebSocket JSON-RPC surface and exposes upgrade / change / resume /
 * cancel actions.
 *
 * `-32601` (removed `credits.view` or any unknown method) arrives as
 * [HermesWsClient.HermesRpcException]; we map it to [BillingUiState.featureUnavailable]
 * so an old cached build or a backend mismatch degrades gracefully instead of crashing.
 */
data class BillingUiState(
    val isLoading: Boolean = false,
    val isActionInFlight: Boolean = false,
    val subscription: SubscriptionStateResponse? = null,
    val usage: UsageBarsResponse? = null,
    val preview: com.m57.hermescontrol.data.model.SubscriptionPreviewResponse? = null,
    /** Set when an RPC method is unavailable on the connected backend (e.g. -32601). */
    val featureUnavailable: Boolean = false,
    val errorMessage: String? = null,
    /** Transient success/info toast after an action (upgrade/resume/cancel). */
    val actionMessage: String? = null,
)

class BillingViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(BillingUiState())
    val uiState: StateFlow<BillingUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun load() {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, errorMessage = null, featureUnavailable = false) }
        loadJob =
            viewModelScope.launch {
                var sub: SubscriptionStateResponse? = null
                var bars: UsageBarsResponse? = null
                var unavailable = false
                var error: String? = null
                try {
                    sub = BillingRepository.getSubscriptionState()
                } catch (e: HermesWsClient.HermesRpcException) {
                    unavailable = true
                    error = e.message
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    error = e.message ?: "Failed to load subscription"
                }
                try {
                    bars = BillingRepository.getUsageBars()
                } catch (e: HermesWsClient.HermesRpcException) {
                    unavailable = true
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (error == null) error = e.message ?: "Failed to load usage"
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        subscription = sub,
                        usage = bars,
                        featureUnavailable = unavailable,
                        errorMessage = if (sub == null && bars == null) error else null,
                    )
                }
            }
    }

    fun preview(subscriptionTypeId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionInFlight = true, errorMessage = null, actionMessage = null) }
            runCatching { BillingRepository.previewSubscription(subscriptionTypeId) }
                .onSuccess { preview ->
                    _uiState.update { it.copy(isActionInFlight = false, preview = preview) }
                }.onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isActionInFlight = false,
                            errorMessage = (e as? HermesWsClient.HermesRpcException)?.message ?: e.message,
                        )
                    }
                }
        }
    }

    fun upgrade(subscriptionTypeId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionInFlight = true, errorMessage = null, actionMessage = null) }
            runCatching { BillingRepository.upgradeSubscription(subscriptionTypeId) }
                .onSuccess { resp ->
                    val msg =
                        when {
                            resp.requires_action == true && resp.recovery_url != null ->
                                "Action required — open: ${resp.recovery_url}"
                            resp.payment_failed == true -> resp.message ?: "Payment failed"
                            resp.ok == true -> "Upgrade successful"
                            else -> resp.message ?: "Upgrade requested"
                        }
                    _uiState.update { it.copy(isActionInFlight = false, actionMessage = msg) }
                    load()
                }.onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isActionInFlight = false,
                            errorMessage = (e as? HermesWsClient.HermesRpcException)?.message ?: e.message,
                        )
                    }
                }
        }
    }

    fun change(
        subscriptionTypeId: String? = null,
        cancel: Boolean? = null,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionInFlight = true, errorMessage = null, actionMessage = null) }
            runCatching { BillingRepository.changeSubscription(SubscriptionChangeRequest(subscriptionTypeId, cancel)) }
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(
                            isActionInFlight = false,
                            actionMessage =
                                resp.message ?: if (cancel == true) "Cancellation scheduled" else "Plan changed",
                        )
                    }
                    load()
                }.onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isActionInFlight = false,
                            errorMessage = (e as? HermesWsClient.HermesRpcException)?.message ?: e.message,
                        )
                    }
                }
        }
    }

    fun resume() {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionInFlight = true, errorMessage = null, actionMessage = null) }
            runCatching { BillingRepository.resumeSubscription() }
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(
                            isActionInFlight = false,
                            actionMessage = resp.message ?: "Subscription resumed",
                        )
                    }
                    load()
                }.onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isActionInFlight = false,
                            errorMessage = (e as? HermesWsClient.HermesRpcException)?.message ?: e.message,
                        )
                    }
                }
        }
    }

    fun clearActionMessage() = _uiState.update { it.copy(actionMessage = null) }
}
