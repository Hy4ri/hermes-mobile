package com.m57.hermescontrol.ui.connectors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.AccountConnectorResult
import com.m57.hermescontrol.data.model.ConnectorAccount
import com.m57.hermescontrol.data.model.ConnectorCatalogEntry
import com.m57.hermescontrol.data.model.ConnectorItem
import com.m57.hermescontrol.data.model.ConnectorPolicy
import com.m57.hermescontrol.data.model.ConnectorTool
import com.m57.hermescontrol.data.ws.AccountConnectorRepository
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.ui.chat.ChatConnectionOperationDelegate
import com.m57.hermescontrol.ui.chat.ConnectionOperationRequester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountConnectorsState(
    val loading: Boolean = true,
    val error: String? = null,
    val catalog: List<ConnectorCatalogEntry> = emptyList(),
    val connectorStates: List<ConnectorItem> = emptyList(),
    val accounts: List<ConnectorAccount> = emptyList(),
    val tools: Map<String, List<ConnectorTool>> = emptyMap(),
    val toolsLoading: Set<String> = emptySet(),
    val policy: ConnectorPolicy? = null,
    val busy: Boolean = false,
    val scope: DataScope? = null,
)

class AccountConnectorsViewModel(
    private val repository: AccountConnectorRepository = AccountConnectorRepository,
    private val scopes: StateFlow<DataScope?> = AuthManager.dataScopeFlow,
    private val currentScope: () -> DataScope? = AuthManager::currentDataScope,
    events: Flow<WsEvent> = HermesWsClient.events,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountConnectorsState())
    val state: StateFlow<AccountConnectorsState> = _state.asStateFlow()
    private var generation = 0L
    private var loadJob: Job? = null
    private val scopedJobs = mutableListOf<Job>()
    private val operation =
        ChatConnectionOperationDelegate(
            requester = ConnectionOperationRequester(repository::operationRequest),
            accountOwned = true,
        )
    val connectionState = operation.state

    init {
        viewModelScope.launch {
            scopes.collect { scope ->
                generation++
                loadJob?.cancel()
                scopedJobs.forEach(Job::cancel)
                scopedJobs.clear()
                operation.reset()
                _state.value = AccountConnectorsState(scope = scope)
                if (scope != null) load() else _state.update { it.copy(loading = false) }
            }
        }
        viewModelScope.launch {
            events.collect { event ->
                if (event is WsEvent.GatewayReady) refresh()
                val snapshot = (event as? WsEvent.ConnectionUpdate)?.snapshot ?: return@collect
                // Never adopt an unsolicited account event: only our acknowledged operation belongs here.
                if (isCurrent(generation) && snapshot.accountOwned &&
                    snapshot.opId ==
                    operation.state.value.operation
                        ?.opId && operation.acceptUpdate(snapshot)
                ) {
                    if (snapshot.settled) load()
                }
            }
        }
    }

    private fun isCurrent(epoch: Long): Boolean = epoch == generation && _state.value.scope == currentScope()

    fun refresh() {
        if (!_state.value.busy) {
            load()
            if (connectionState.value.operation != null) operationAction { }
        }
    }

    private fun load() {
        loadJob?.cancel()
        val epoch = generation
        _state.update { it.copy(loading = true, error = null) }
        loadJob =
            viewModelScope.launch {
                val list = async { repository.listConnectors() }
                val catalog = async { repository.catalog() }
                val accounts = async { repository.accounts() }
                val policy = async { repository.policy() }
                val listResult = list.await()
                val catalogResult = catalog.await()
                val accountsResult = accounts.await()
                val policyResult = policy.await()
                if (!isCurrent(epoch)) return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        error =
                            listResult.errorOrNull()?.message ?: catalogResult.errorMessage()
                                ?: accountsResult.errorMessage() ?: policyResult.errorMessage(),
                        catalog = catalogResult.valueOrNull().orEmpty(),
                        connectorStates = listResult.getOrNull().orEmpty(),
                        accounts = accountsResult.valueOrNull().orEmpty(),
                        policy = policyResult.valueOrNull(),
                    )
                }
            }
    }

    fun loadTools(slug: String) {
        scopedJobs.removeAll { it.isCompleted }
        if (slug in _state.value.toolsLoading || !isCurrent(generation)) return
        val epoch = generation
        _state.update { it.copy(toolsLoading = it.toolsLoading + slug) }
        scopedJobs +=
            viewModelScope.launch {
                val result = repository.tools(slug)
                if (!isCurrent(epoch)) return@launch
                _state.update {
                    it.copy(
                        tools = result.valueOrNull()?.let { tools -> it.tools + (slug to tools) } ?: it.tools,
                        toolsLoading = it.toolsLoading - slug,
                        error = result.errorMessage(),
                    )
                }
            }
    }

    fun connect(
        slug: String,
        reconnect: Boolean = false,
    ) = mutate {
        val epoch = generation
        val result = repository.connect(listOf(slug), reconnect)
        if (isCurrent(epoch)) {
            result.valueOrNull()?.let { snapshot ->
                operation.acceptRequest(snapshot)
                if (snapshot.settled) load()
            }
        }
        result.errorMessage()
    }

    fun remove(connectionId: String) =
        mutate {
            val epoch = generation
            val result = repository.remove(connectionId)
            if (isCurrent(epoch) && result is AccountConnectorResult.Success) load()
            result.errorMessage()
        }

    fun setEnabled(
        slug: String,
        enabled: Boolean,
    ) = updatePolicy {
        repository.setConnectorEnabled(
            slug,
            enabled,
            _state.value.policy
                ?.member
                ?.revision
                .orEmpty(),
        )
    }

    fun setDisabledTools(
        slug: String,
        disabled: List<String>,
    ) = updatePolicy {
        repository.setDisabledTools(
            slug,
            disabled,
            _state.value.policy
                ?.member
                ?.revision
                .orEmpty(),
        )
    }

    private fun updatePolicy(action: suspend () -> AccountConnectorResult<ConnectorPolicy>) =
        mutate {
            val epoch = generation
            val result = action()
            if (isCurrent(epoch)) result.valueOrNull()?.let { policy -> _state.update { it.copy(policy = policy) } }
            result.errorMessage()
        }

    // Reserve synchronously, not inside launch: repeated taps must not start concurrent writes.
    private fun mutate(action: suspend () -> String?) {
        scopedJobs.removeAll { it.isCompleted }
        if (_state.value.scope == null || _state.value.busy || _state.value.loading || !isCurrent(generation)) return
        val epoch = generation
        _state.update { it.copy(busy = true, error = null) }
        scopedJobs +=
            viewModelScope.launch {
                try {
                    if (!isCurrent(epoch)) return@launch
                    val error = action()
                    if (isCurrent(epoch)) _state.update { it.copy(busy = false, error = error) }
                } catch (e: CancellationException) {
                    throw e
                } finally {
                    if (isCurrent(epoch)) _state.update { it.copy(busy = false) }
                }
            }
    }

    fun respond(
        target: String,
        env: Map<String, String>,
        approved: Boolean,
    ) = operationAction {
        operation.respond(target, env, approved)
    }

    fun continueOperation() = operationAction { operation.continueOperation() }

    fun onBrowserReturn(opId: String) = operationAction { operation.wake(opId) }

    private fun operationAction(action: suspend () -> Unit) {
        scopedJobs.removeAll { it.isCompleted }
        if (!isCurrent(generation)) return
        val epoch = generation
        val opId = connectionState.value.operation?.opId ?: return
        scopedJobs +=
            viewModelScope.launch {
                action()
                if (!isCurrent(epoch)) return@launch
                val result = repository.operationStatus(opId)
                if (!isCurrent(epoch)) return@launch
                when (result) {
                    is AccountConnectorResult.Success -> {
                        operation.acceptUpdate(result.value)
                        if (result.value.settled) load()
                    }

                    is AccountConnectorResult.Failure -> {
                        _state.update { it.copy(error = result.error.message) }
                    }
                }
            }
    }

    fun browserLaunchFailed() {
        _state.update { it.copy(error = "Unable to open the authorization browser.") }
    }
}

private fun <T> AccountConnectorResult<T>.valueOrNull(): T? = (this as? AccountConnectorResult.Success)?.value

private fun AccountConnectorResult<*>.errorMessage(): String? =
    (this as? AccountConnectorResult.Failure)?.error?.message
