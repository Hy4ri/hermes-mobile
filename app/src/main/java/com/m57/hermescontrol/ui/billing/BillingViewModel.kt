package com.m57.hermescontrol.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.SubscriptionChangeRequest
import com.m57.hermescontrol.data.model.SubscriptionStateResponse
import com.m57.hermescontrol.data.model.UsageBarsResponse
import com.m57.hermescontrol.data.ws.BillingRepository
import com.m57.hermescontrol.data.ws.HermesWsClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
                coroutineScope {
                    val subDeferred =
                        async {
                            var sub: SubscriptionStateResponse? = null
                            var unavailable = false
                            var error: String? = null
                            try {
                                sub = BillingRepository.getSubscriptionState()
                                if (sub?.ok == false) {
                                    unavailable = true
                                    error = sub.error ?: "Billing unavailable"
                                } else if (sub == null) {
                                    if (error == null) error = "No billing data returned"
                                }
                            } catch (e: HermesWsClient.HermesRpcException) {
                                unavailable = true
                                error = e.message
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                error = e.message ?: "Failed to load subscription"
                            }
                            Triple(sub, unavailable, error)
                        }

                    val barsDeferred =
                        async {
                            var bars: UsageBarsResponse? = null
                            var error: String? = null
                            try {
                                bars = BillingRepository.getUsageBars()
                                if (bars?.ok == false) {
                                    error = "Usage data unavailable"
                                }
                            } catch (e: HermesWsClient.HermesRpcException) {
                                // usage unavailable is non-fatal
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                error = e.message ?: "Failed to load usage"
                            }
                            Pair(bars, error)
                        }

                    val (sub, unavailable, subError) = subDeferred.await()
                    val (bars, barsError) = barsDeferred.await()
                    val error = subError ?: barsError

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            subscription = sub,
                            usage = bars,
                            featureUnavailable = unavailable,
                            errorMessage = if (sub == null) error else null,
                        )
                    }
                }
            }
    }

    fun preview(subscriptionTypeId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionInFlight = true, errorMessage = null, actionMessage = null) }
            runCatching { BillingRepository.previewSubscription(subscriptionTypeId) }
                .onSuccess { preview ->
                    if (preview == null) {
                        _uiState.update {
                            it.copy(isActionInFlight = false, errorMessage = "No billing data returned")
                        }
                        return@onSuccess
                    }
                    if (preview.ok == false) {
                        _uiState.update {
                            it.copy(
                                isActionInFlight = false,
                                errorMessage = preview.error ?: preview.message ?: "Preview unavailable",
                            )
                        }
                    } else {
                        _uiState.update { it.copy(isActionInFlight = false, preview = preview) }
                    }
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
                    if (resp == null) {
                        _uiState.update {
                            it.copy(isActionInFlight = false, errorMessage = "No billing data returned")
                        }
                        return@onSuccess
                    }
                    if (resp.ok == false) {
                        _uiState.update {
                            it.copy(
                                isActionInFlight = false,
                                errorMessage = resp.error ?: resp.message ?: "Upgrade unavailable",
                            )
                        }
                        return@onSuccess
                    }
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
                    if (resp == null) {
                        _uiState.update {
                            it.copy(isActionInFlight = false, errorMessage = "No billing data returned")
                        }
                        return@onSuccess
                    }
                    if (resp.ok == false) {
                        _uiState.update {
                            it.copy(
                                isActionInFlight = false,
                                errorMessage = resp.error ?: resp.message ?: "Plan change unavailable",
                            )
                        }
                        return@onSuccess
                    }
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
                    if (resp == null) {
                        _uiState.update {
                            it.copy(isActionInFlight = false, errorMessage = "No billing data returned")
                        }
                        return@onSuccess
                    }
                    if (resp.ok == false) {
                        _uiState.update {
                            it.copy(
                                isActionInFlight = false,
                                errorMessage = resp.error ?: resp.message ?: "Resume unavailable",
                            )
                        }
                        return@onSuccess
                    }
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
}
