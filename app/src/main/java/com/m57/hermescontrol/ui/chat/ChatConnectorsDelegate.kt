package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.ConnectorConnectResult
import com.m57.hermescontrol.data.model.ConnectorConnectStatus
import com.m57.hermescontrol.data.model.ConnectorError
import com.m57.hermescontrol.data.model.ConnectorItem
import com.m57.hermescontrol.data.model.ConnectorListResult
import com.m57.hermescontrol.data.model.ConnectorStatus
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.ConnectorRepository
import com.m57.hermescontrol.util.ConnectorUrlValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explicit load phases for connector list rendering.
 */
sealed interface ConnectorsLoadPhase {
    data object Initial : ConnectorsLoadPhase

    data object Loading : ConnectorsLoadPhase

    data class Refreshing(
        val previousItems: List<ConnectorUiItem>,
    ) : ConnectorsLoadPhase

    data class Loaded(
        val available: Boolean,
    ) : ConnectorsLoadPhase

    data class Error(
        val error: ConnectorError,
        val canRetry: Boolean = true,
    ) : ConnectorsLoadPhase

    data class Unavailable(
        val message: String,
    ) : ConnectorsLoadPhase

    data class UnsupportedRuntime(
        val message: String,
    ) : ConnectorsLoadPhase

    data class UnsupportedBackend(
        val message: String,
    ) : ConnectorsLoadPhase

    data class NotOwner(
        val message: String,
    ) : ConnectorsLoadPhase
}

/**
 * Mutating action phases for individual connector operations.
 */
sealed interface ConnectorActionState {
    data object Idle : ConnectorActionState

    data class Connecting(
        val slug: String,
        val isReconnect: Boolean,
    ) : ConnectorActionState

    data class AwaitingAuthorization(
        val slug: String,
    ) : ConnectorActionState
}

/**
 * Immutable safe UI item representation. Raw connect URLs are strictly omitted.
 */
data class ConnectorUiItem(
    val slug: String,
    val name: String,
    val isConnected: Boolean,
    val isEnabled: Boolean,
    val status: ConnectorStatus,
    val connectionStatus: String?,
) {
    companion object {
        fun from(item: ConnectorItem): ConnectorUiItem =
            ConnectorUiItem(
                slug = item.connector,
                name = item.name,
                isConnected = item.connected,
                isEnabled = item.enabled,
                status = item.status,
                connectionStatus = item.connectionStatus,
            )
    }
}

/**
 * Transient one-shot browser launch event. Redacts URL in [toString].
 */
data class ConnectBrowserEvent(
    val slug: String,
    val url: String,
    val eventId: Long = System.nanoTime(),
    val sessionId: String? = null,
    val generation: Long = 0L,
) {
    override fun toString(): String =
        "ConnectBrowserEvent(slug=$slug, url=[REDACTED], eventId=$eventId, sessionId=$sessionId, generation=$generation)"
}

/**
 * Complete UI state exposed to the session integrations view.
 */
data class ChatConnectorsUiState(
    val isVisible: Boolean = false,
    val loadPhase: ConnectorsLoadPhase = ConnectorsLoadPhase.Initial,
    val items: List<ConnectorUiItem> = emptyList(),
    val actionState: ConnectorActionState = ConnectorActionState.Idle,
    val browserLaunchEvent: ConnectBrowserEvent? = null,
    val errorMessage: String? = null,
    val isOldBackend: Boolean = false,
    val sessionId: String? = null,
    val generation: Long = 0L,
) {
    val isLoading: Boolean
        get() = loadPhase is ConnectorsLoadPhase.Loading || actionState is ConnectorActionState.Connecting
}

/**
 * Controller / Delegate managing session Integrations (connectors) state,
 * lifecycle, deduplication, and authorization flows (issue #1091).
 */
class ChatConnectorsDelegate(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val repository: ConnectorRepository = ConnectorRepository,
    private val runtimeSessionId: () -> String? = { ActiveSessionHolder.activeSessionId.value },
) {
    private val _uiState = MutableStateFlow(ChatConnectorsUiState())
    val uiState: StateFlow<ChatConnectorsUiState> = _uiState.asStateFlow()

    private var isForeground: Boolean = false
    private var sessionGeneration: Long = 0L
    private var refreshRequestId: Long = 0L
    private var refreshJob: Job? = null
    private var connectJob: Job? = null
    private var pendingRefreshAfterMutation: Boolean = false
    private var pendingTrailingRefresh: Boolean = false
    private var pendingBrowserEvent: ConnectBrowserEvent? = null
    private var browserLaunched: Boolean = false

    /**
     * Show integrations panel and trigger load if in foreground.
     */
    fun show() {
        if (_uiState.value.isVisible) return
        val sid = runtimeSessionId()
        _uiState.update {
            it.copy(
                isVisible = true,
                sessionId = sid,
                generation = sessionGeneration,
            )
        }
        if (isForeground) {
            if (!sid.isNullOrBlank() && !_uiState.value.isOldBackend) {
                refresh(force = false)
            }
        }
    }

    /**
     * Hide integrations panel, cancelling ongoing jobs, incrementing generation,
     * and clearing transient state.
     */
    fun hide() {
        sessionGeneration++
        refreshJob?.cancel()
        refreshJob = null
        connectJob?.cancel()
        connectJob = null
        pendingRefreshAfterMutation = false
        pendingTrailingRefresh = false
        pendingBrowserEvent = null
        browserLaunched = false
        _uiState.update {
            it.copy(
                isVisible = false,
                actionState = ConnectorActionState.Idle,
                browserLaunchEvent = null,
                generation = sessionGeneration,
            )
        }
    }

    /**
     * Lifecycle resume hook. Refreshes once when returning from external browser authorization.
     * Guarded to only execute on true paused -> resume transitions.
     */
    fun onResume() {
        val wasForeground = isForeground
        isForeground = true
        if (wasForeground) return
        if (!_uiState.value.isVisible) return

        val sid = runtimeSessionId()
        val pending = pendingBrowserEvent
        if (pending != null) {
            pendingBrowserEvent = null
            if (pending.generation == sessionGeneration &&
                pending.sessionId == sid &&
                _uiState.value.actionState is ConnectorActionState.AwaitingAuthorization
            ) {
                _uiState.update { it.copy(browserLaunchEvent = pending) }
            }
        }

        if (pendingRefreshAfterMutation || pendingTrailingRefresh) {
            pendingRefreshAfterMutation = false
            pendingTrailingRefresh = false
            refresh(force = true)
            return
        }

        val currentAction = _uiState.value.actionState
        if (currentAction is ConnectorActionState.AwaitingAuthorization) {
            if (browserLaunched) {
                // Actual return from external browser authorization: refresh once to confirm authorization
                browserLaunched = false
                refresh(force = true)
                return
            }
            // Minted unconsumed event waiting to be launched: do NOT prematurely refresh
            return
        }

        // Returned from background: initial load or reload interrupted read if needed and not old backend
        val currentPhase = _uiState.value.loadPhase
        val needsCatalogLoad =
            (
                currentPhase is ConnectorsLoadPhase.Initial ||
                    currentPhase is ConnectorsLoadPhase.Loading ||
                    currentPhase is ConnectorsLoadPhase.Refreshing
            ) &&
                refreshJob?.isActive != true &&
                !_uiState.value.isOldBackend

        if (needsCatalogLoad) {
            refresh(force = false)
        }
    }

    /**
     * Lifecycle pause hook. Retains unconsumed browser event bound to session + generation.
     */
    fun onPause() {
        isForeground = false
        val unconsumed = _uiState.value.browserLaunchEvent ?: pendingBrowserEvent
        if (unconsumed != null &&
            unconsumed.generation == sessionGeneration &&
            unconsumed.sessionId == runtimeSessionId()
        ) {
            pendingBrowserEvent = unconsumed
        }
        _uiState.update { it.copy(browserLaunchEvent = null) }
    }

    /**
     * Notify of active session change with generation guard (A -> B -> A).
     */
    fun onActiveSessionChanged(newSessionId: String?) {
        sessionGeneration++
        val currentGen = sessionGeneration

        refreshJob?.cancel()
        refreshJob = null
        connectJob?.cancel()
        connectJob = null
        pendingRefreshAfterMutation = false
        pendingTrailingRefresh = false
        pendingBrowserEvent = null
        browserLaunched = false

        if (newSessionId.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    loadPhase = ConnectorsLoadPhase.Initial,
                    items = emptyList(),
                    actionState = ConnectorActionState.Idle,
                    browserLaunchEvent = null,
                    errorMessage = null,
                    isOldBackend = false,
                    sessionId = null,
                    generation = currentGen,
                )
            }
            return
        }

        if (_uiState.value.isVisible && isForeground) {
            _uiState.update {
                it.copy(
                    loadPhase = ConnectorsLoadPhase.Loading,
                    items = emptyList(),
                    actionState = ConnectorActionState.Idle,
                    browserLaunchEvent = null,
                    errorMessage = null,
                    isOldBackend = false,
                    sessionId = newSessionId,
                    generation = currentGen,
                )
            }
            executeRefresh(newSessionId, currentGen)
        } else {
            _uiState.update {
                it.copy(
                    loadPhase = ConnectorsLoadPhase.Initial,
                    items = emptyList(),
                    actionState = ConnectorActionState.Idle,
                    browserLaunchEvent = null,
                    errorMessage = null,
                    isOldBackend = false,
                    sessionId = newSessionId,
                    generation = currentGen,
                )
            }
        }
    }

    /**
     * Transport reconnected notification. Refreshes if visible, foreground, and backend supports connectors.
     */
    fun onTransportReconnected() {
        if (_uiState.value.isVisible && isForeground && !_uiState.value.isOldBackend) {
            val sessionId = runtimeSessionId()
            if (!sessionId.isNullOrBlank()) {
                refresh(force = true)
            }
        }
    }

    /**
     * Request connector catalog and authorization status.
     * Deduplicated and coalesced for both normal and forced calls, and blocked in background.
     */
    fun refresh(force: Boolean = false) {
        if (!_uiState.value.isVisible || !isForeground) return
        if (_uiState.value.isOldBackend && !force) return

        val sessionId = runtimeSessionId()
        if (sessionId.isNullOrBlank()) return

        if (_uiState.value.actionState is ConnectorActionState.Connecting) {
            pendingRefreshAfterMutation = true
            return
        }

        if (refreshJob?.isActive == true) {
            if (force) {
                pendingTrailingRefresh = true
            }
            return
        }

        val gen = sessionGeneration
        val previousItems = _uiState.value.items
        _uiState.update { current ->
            if (previousItems.isEmpty()) {
                current.copy(
                    loadPhase = ConnectorsLoadPhase.Loading,
                    sessionId = sessionId,
                    generation = gen,
                )
            } else {
                current.copy(
                    loadPhase = ConnectorsLoadPhase.Refreshing(previousItems),
                    sessionId = sessionId,
                    generation = gen,
                )
            }
        }

        executeRefresh(sessionId, gen)
    }

    private fun executeRefresh(
        targetSessionId: String,
        generation: Long,
    ) {
        val requestId = ++refreshRequestId
        refreshJob =
            scope.launch {
                var currentSession = targetSessionId
                try {
                    var result = withContext(ioDispatcher) { repository.listConnectors(currentSession) }

                    // Bounded NOT_OWNER reconciliation: re-read active session once (READ ONLY)
                    if (result is ConnectorListResult.Error && result.error is ConnectorError.NotOwner) {
                        val freshSession = runtimeSessionId()
                        if (!freshSession.isNullOrBlank() && freshSession != currentSession) {
                            currentSession = freshSession
                            result = withContext(ioDispatcher) { repository.listConnectors(currentSession) }
                        }
                    }

                    // Check target sid AND generation AND request token AND visibility before applying
                    val activeSid = runtimeSessionId()
                    if (requestId != refreshRequestId ||
                        generation != sessionGeneration ||
                        currentSession != activeSid ||
                        !_uiState.value.isVisible
                    ) {
                        return@launch
                    }

                    when (result) {
                        is ConnectorListResult.Success -> {
                            if (!result.available) {
                                pendingRefreshAfterMutation = false
                                pendingTrailingRefresh = false
                                pendingBrowserEvent = null
                                _uiState.update { current ->
                                    current.copy(
                                        loadPhase =
                                            ConnectorsLoadPhase.Unavailable(
                                                "Connectors are unavailable for this session.",
                                            ),
                                        items = emptyList(),
                                        actionState = ConnectorActionState.Idle,
                                        browserLaunchEvent = null,
                                        errorMessage = null,
                                        sessionId = currentSession,
                                        generation = generation,
                                    )
                                }
                                return@launch
                            }

                            val uiItems = result.connectors.map { ConnectorUiItem.from(it) }
                            _uiState.update { current ->
                                val currentAction = current.actionState
                                val updatedAction =
                                    if (currentAction is ConnectorActionState.AwaitingAuthorization) {
                                        val matchingItem = uiItems.find { it.slug == currentAction.slug }
                                        // End authorization if connector removed from catalog or now connected
                                        if (matchingItem == null || matchingItem.isConnected) {
                                            ConnectorActionState.Idle
                                        } else {
                                            currentAction
                                        }
                                    } else {
                                        currentAction
                                    }

                                current.copy(
                                    loadPhase = ConnectorsLoadPhase.Loaded(result.available),
                                    items = uiItems,
                                    actionState = updatedAction,
                                    isOldBackend = false,
                                    errorMessage = null, // error clears on refresh success
                                    sessionId = currentSession,
                                    generation = generation,
                                )
                            }
                        }

                        is ConnectorListResult.Error -> {
                            when (val err = result.error) {
                                is ConnectorError.UnsupportedBackend -> {
                                    _uiState.update {
                                        it.copy(
                                            loadPhase = ConnectorsLoadPhase.UnsupportedBackend(err.message),
                                            isOldBackend = true,
                                            items = emptyList(),
                                            actionState = ConnectorActionState.Idle,
                                            sessionId = currentSession,
                                            generation = generation,
                                        )
                                    }
                                }

                                is ConnectorError.Unavailable -> {
                                    _uiState.update {
                                        it.copy(
                                            loadPhase = ConnectorsLoadPhase.Unavailable(err.message),
                                            items = emptyList(),
                                            actionState = ConnectorActionState.Idle,
                                            sessionId = currentSession,
                                            generation = generation,
                                        )
                                    }
                                }

                                is ConnectorError.UnsupportedRuntime -> {
                                    _uiState.update {
                                        it.copy(
                                            loadPhase = ConnectorsLoadPhase.UnsupportedRuntime(err.message),
                                            items = emptyList(),
                                            actionState = ConnectorActionState.Idle,
                                            sessionId = currentSession,
                                            generation = generation,
                                        )
                                    }
                                }

                                is ConnectorError.NotOwner -> {
                                    val notOwnerMsg =
                                        "Session is not owned by this transport. Please reconnect the session."
                                    _uiState.update {
                                        it.copy(
                                            loadPhase =
                                                ConnectorsLoadPhase.NotOwner(
                                                    notOwnerMsg,
                                                ),
                                            items = emptyList(),
                                            actionState = ConnectorActionState.Idle,
                                            sessionId = currentSession,
                                            generation = generation,
                                        )
                                    }
                                }

                                else -> {
                                    _uiState.update {
                                        it.copy(
                                            loadPhase = ConnectorsLoadPhase.Error(err, canRetry = true),
                                            sessionId = currentSession,
                                            generation = generation,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    _uiState.update {
                        it.copy(
                            loadPhase =
                                ConnectorsLoadPhase.Error(
                                    ConnectorError.NetworkError(t.message ?: "Failed to load connectors"),
                                    canRetry = true,
                                ),
                            sessionId = currentSession,
                            generation = generation,
                        )
                    }
                } finally {
                    refreshJob = null
                    if (pendingTrailingRefresh &&
                        generation == sessionGeneration &&
                        _uiState.value.isVisible
                    ) {
                        if (isForeground) {
                            pendingTrailingRefresh = false
                            refresh(force = true)
                        }
                    } else {
                        pendingTrailingRefresh = false
                    }
                }
            }
    }

    /**
     * Initiate authorization flow for connector [slug].
     * Guarded by visibility, foreground state, catalog readiness, and item enabled status.
     * Never replays connect mutations into a different session.
     */
    fun connect(
        slug: String,
        reconnect: Boolean = false,
    ) {
        if (!_uiState.value.isVisible || !isForeground) return

        if (_uiState.value.isOldBackend || _uiState.value.loadPhase is ConnectorsLoadPhase.UnsupportedBackend) {
            _uiState.update { it.copy(errorMessage = "Connectors are not supported by this backend.") }
            return
        }

        val currentPhase = _uiState.value.loadPhase
        if (currentPhase !is ConnectorsLoadPhase.Loaded && currentPhase !is ConnectorsLoadPhase.Refreshing) {
            _uiState.update { it.copy(errorMessage = "Cannot connect: connectors catalog is not ready.") }
            return
        }

        val targetSession = runtimeSessionId()
        if (targetSession.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = "No active session.") }
            return
        }

        if (!ConnectorRepository.isValidSlug(slug)) {
            _uiState.update { it.copy(errorMessage = "Invalid connector slug.") }
            return
        }

        val catalogItem = _uiState.value.items.firstOrNull { it.slug == slug }
        if (catalogItem == null) {
            _uiState.update { it.copy(errorMessage = "Connector '$slug' not found in catalog.") }
            return
        }
        if (!catalogItem.isEnabled) {
            _uiState.update { it.copy(errorMessage = "Connector '$slug' is disabled.") }
            return
        }

        if (_uiState.value.actionState is ConnectorActionState.Connecting) {
            return
        }

        // Cancel any prior in-flight read before starting mutation
        refreshJob?.cancel()
        refreshJob = null
        pendingTrailingRefresh = false

        val gen = sessionGeneration
        _uiState.update {
            it.copy(
                actionState = ConnectorActionState.Connecting(slug, reconnect),
                errorMessage = null,
                sessionId = targetSession,
                generation = gen,
            )
        }

        connectJob =
            scope.launch {
                var needsRefresh = false
                try {
                    val result =
                        withContext(ioDispatcher) {
                            repository.connect(targetSession, listOf(slug), reconnect)
                        }

                    // Verify generation, session equality, and visibility before applying
                    val activeSid = runtimeSessionId()
                    if (gen != sessionGeneration ||
                        targetSession != activeSid ||
                        !_uiState.value.isVisible
                    ) {
                        return@launch
                    }

                    when (result) {
                        is ConnectorConnectResult.Success -> {
                            // Require exact slug match; never fallback to unrelated connector
                            val item = result.results.firstOrNull { it.connector == slug }
                            if (item == null) {
                                _uiState.update {
                                    it.copy(
                                        actionState = ConnectorActionState.Idle,
                                        errorMessage = "Connector '$slug' not found in response.",
                                    )
                                }
                                return@launch
                            }

                            when (item.status) {
                                ConnectorConnectStatus.ACTIVE -> {
                                    _uiState.update { it.copy(actionState = ConnectorActionState.Idle) }
                                    needsRefresh = true
                                }

                                ConnectorConnectStatus.INITIATED -> {
                                    val safeUrl = item.safeConnectUrl
                                    if (safeUrl != null && ConnectorUrlValidator.isValidHttpsUrl(safeUrl)) {
                                        val event =
                                            ConnectBrowserEvent(
                                                slug = slug,
                                                url = safeUrl,
                                                sessionId = targetSession,
                                                generation = gen,
                                            )
                                        browserLaunched = false
                                        if (isForeground) {
                                            _uiState.update {
                                                it.copy(
                                                    actionState = ConnectorActionState.AwaitingAuthorization(slug),
                                                    browserLaunchEvent = event,
                                                )
                                            }
                                        } else {
                                            // In background: hold transiently for foreground resume
                                            pendingBrowserEvent = event
                                            _uiState.update {
                                                it.copy(
                                                    actionState = ConnectorActionState.AwaitingAuthorization(slug),
                                                    browserLaunchEvent = null,
                                                )
                                            }
                                        }
                                    } else {
                                        // Unsafe or absent URL is a retryable generic failure
                                        _uiState.update {
                                            it.copy(
                                                actionState = ConnectorActionState.Idle,
                                                errorMessage = "Invalid or unsafe authorization URL received.",
                                            )
                                        }
                                    }
                                }

                                ConnectorConnectStatus.FAILED -> {
                                    _uiState.update {
                                        it.copy(
                                            actionState = ConnectorActionState.Idle,
                                            errorMessage = "Connector authorization failed. Please try again.",
                                        )
                                    }
                                }

                                else -> {
                                    _uiState.update {
                                        it.copy(
                                            actionState = ConnectorActionState.Idle,
                                            errorMessage = "Connector operation returned unexpected status.",
                                        )
                                    }
                                }
                            }
                        }

                        is ConnectorConnectResult.Error -> {
                            when (val err = result.error) {
                                is ConnectorError.UnsupportedBackend -> {
                                    _uiState.update {
                                        it.copy(
                                            loadPhase = ConnectorsLoadPhase.UnsupportedBackend(err.message),
                                            actionState = ConnectorActionState.Idle,
                                            isOldBackend = true,
                                            items = emptyList(),
                                            errorMessage = err.message,
                                        )
                                    }
                                }

                                is ConnectorError.Unavailable -> {
                                    _uiState.update {
                                        it.copy(
                                            loadPhase = ConnectorsLoadPhase.Unavailable(err.message),
                                            actionState = ConnectorActionState.Idle,
                                            items = emptyList(),
                                            errorMessage = err.message,
                                        )
                                    }
                                }

                                is ConnectorError.UnsupportedRuntime -> {
                                    _uiState.update {
                                        it.copy(
                                            loadPhase = ConnectorsLoadPhase.UnsupportedRuntime(err.message),
                                            actionState = ConnectorActionState.Idle,
                                            items = emptyList(),
                                            errorMessage = err.message,
                                        )
                                    }
                                }

                                is ConnectorError.NotOwner -> {
                                    val msg = "Session is not owned by this transport. Please reconnect the session."
                                    _uiState.update {
                                        it.copy(
                                            loadPhase = ConnectorsLoadPhase.NotOwner(msg),
                                            actionState = ConnectorActionState.Idle,
                                            errorMessage = msg,
                                        )
                                    }
                                }

                                else -> {
                                    _uiState.update {
                                        it.copy(
                                            actionState = ConnectorActionState.Idle,
                                            errorMessage = err.message,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    _uiState.update {
                        if (it.actionState is ConnectorActionState.Connecting) {
                            it.copy(actionState = ConnectorActionState.Idle)
                        } else {
                            it
                        }
                    }
                    throw e
                } catch (t: Throwable) {
                    _uiState.update {
                        it.copy(
                            actionState = ConnectorActionState.Idle,
                            errorMessage = t.message ?: "Connector connection failed.",
                        )
                    }
                } finally {
                    connectJob = null
                    _uiState.update {
                        if (it.actionState is ConnectorActionState.Connecting) {
                            it.copy(actionState = ConnectorActionState.Idle)
                        } else {
                            it
                        }
                    }
                    val shouldRefresh = needsRefresh || pendingRefreshAfterMutation
                    if (shouldRefresh &&
                        gen == sessionGeneration &&
                        _uiState.value.isVisible
                    ) {
                        if (isForeground) {
                            pendingRefreshAfterMutation = false
                            refresh(force = true)
                        } else {
                            pendingRefreshAfterMutation = true
                        }
                    } else if (gen != sessionGeneration || !_uiState.value.isVisible) {
                        pendingRefreshAfterMutation = false
                    }
                }
            }
    }

    /**
     * Consume transient browser launch event (legacy no-arg API).
     */
    fun consumeBrowserEvent() {
        val currentSid = runtimeSessionId()
        val event = _uiState.value.browserLaunchEvent ?: pendingBrowserEvent
        if (event != null &&
            isForeground &&
            _uiState.value.isVisible &&
            event.generation == sessionGeneration &&
            event.sessionId == currentSid
        ) {
            browserLaunched = true
        }
        pendingBrowserEvent = null
        _uiState.update { it.copy(browserLaunchEvent = null) }
    }

    /**
     * Consume transient browser launch event validating identity, session, generation, and foreground state.
     */
    fun consumeBrowserEvent(eventId: Long): ConnectBrowserEvent? {
        var consumed: ConnectBrowserEvent? = null
        val currentSid = runtimeSessionId()
        val pending = pendingBrowserEvent
        if (pending != null && pending.eventId == eventId) {
            if (isForeground &&
                _uiState.value.isVisible &&
                pending.generation == sessionGeneration &&
                pending.sessionId == currentSid
            ) {
                consumed = pending
                pendingBrowserEvent = null
            } else {
                pendingBrowserEvent = null
            }
        }
        _uiState.update { current ->
            val event = current.browserLaunchEvent
            if (event != null && event.eventId == eventId) {
                if (isForeground &&
                    current.isVisible &&
                    event.generation == sessionGeneration &&
                    event.sessionId == currentSid
                ) {
                    consumed = event
                    current.copy(browserLaunchEvent = null)
                } else {
                    // Invalid context: clear stale event and reject handoff
                    current.copy(browserLaunchEvent = null)
                }
            } else {
                current
            }
        }
        if (consumed != null) {
            browserLaunched = true
        }
        return consumed
    }

    /**
     * Safely take transient browser launch event if valid.
     */
    fun takeBrowserEvent(eventId: Long? = null): ConnectBrowserEvent? {
        val targetId =
            eventId
                ?: _uiState.value.browserLaunchEvent?.eventId
                ?: pendingBrowserEvent?.eventId
                ?: return null
        return consumeBrowserEvent(targetId)
    }

    /**
     * Report an error launching the external browser intent.
     */
    fun launchError(message: String) {
        pendingBrowserEvent = null
        browserLaunched = false
        _uiState.update {
            it.copy(
                browserLaunchEvent = null,
                actionState = ConnectorActionState.Idle,
                errorMessage = message,
            )
        }
    }

    fun onLaunchError(message: String) = launchError(message)

    /**
     * Clear active error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Teardown ongoing coroutines and invalidate state generation.
     */
    fun destroy() {
        sessionGeneration++
        refreshJob?.cancel()
        refreshJob = null
        connectJob?.cancel()
        connectJob = null
        pendingRefreshAfterMutation = false
        pendingTrailingRefresh = false
        pendingBrowserEvent = null
        browserLaunched = false
    }
}
