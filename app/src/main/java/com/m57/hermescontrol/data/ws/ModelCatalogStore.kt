package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ModelCatalogState(
    val scope: DataScope? = null,
    val providers: List<ModelProvider> = emptyList(),
    val hasLoaded: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: NetworkError? = null,
)

/**
 * Shared in-memory catalog cache partitioned by [DataScope].
 * Deduplicates in-flight catalog loads and shares parsed [ModelProvider]s
 * across Chat and Model screens.
 */
class ModelCatalogStore(
    private val repository: ModelOptionsRepository = ModelOptionsRepository(),
    private val getCurrentScope: () -> DataScope? = { AuthManager.currentDataScope() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val ttlMs: Long = 60_000L,
) {
    private val _state = MutableStateFlow(ModelCatalogState())
    val state: StateFlow<ModelCatalogState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var lastLoadedTime: Long = 0L
    private var activeJob: Deferred<NetworkResult<ModelOptionsResponse>>? = null
    private var activeIsForced: Boolean = false
    private var activeGeneration: Long = 0L

    fun attachScopeObserver(
        externalScope: CoroutineScope,
        dataScopeFlow: Flow<DataScope?>,
    ) {
        externalScope.launch {
            dataScopeFlow.collect { newScope ->
                onScopeChanged(newScope)
            }
        }
    }

    suspend fun onScopeChanged(newScope: DataScope?) {
        mutex.withLock {
            if (_state.value.scope != newScope) {
                activeJob?.cancel()
                activeJob = null
                activeIsForced = false
                activeGeneration++
                lastLoadedTime = 0L
                _state.value = ModelCatalogState(scope = newScope)
            }
        }
    }

    suspend fun ensureLoaded(forceRefresh: Boolean = false): NetworkResult<ModelOptionsResponse> {
        val currentScope = getCurrentScope()
        val job: Deferred<NetworkResult<ModelOptionsResponse>>
        val generation: Long

        mutex.withLock {
            // Scope switch check
            if (_state.value.scope != currentScope) {
                activeJob?.cancel()
                activeJob = null
                activeIsForced = false
                activeGeneration++
                lastLoadedTime = 0L
                _state.value = ModelCatalogState(scope = currentScope)
            }

            val now = clock()
            val isFresh = _state.value.hasLoaded && (now - lastLoadedTime < ttlMs)

            if (!forceRefresh && isFresh) {
                return NetworkResult.Success(ModelOptionsResponse(_state.value.providers))
            }

            // In-flight request reuse / supersession logic
            if (activeJob?.isActive == true) {
                if (!forceRefresh || activeIsForced) {
                    // Reuse in-flight job
                    job = activeJob!!
                    generation = activeGeneration
                } else {
                    // Force refresh supersedes normal in-flight
                    activeJob?.cancel()
                    generation = ++activeGeneration
                    activeIsForced = true
                    _state.update { it.copy(isRefreshing = true) }
                    val newDeferred =
                        scope.async {
                            repository.load(refresh = true)
                        }
                    activeJob = newDeferred
                    job = newDeferred
                }
            } else {
                generation = ++activeGeneration
                activeIsForced = forceRefresh
                _state.update { it.copy(isRefreshing = true) }
                val newDeferred =
                    scope.async {
                        repository.load(refresh = forceRefresh)
                    }
                activeJob = newDeferred
                job = newDeferred
            }
        }

        return try {
            val result = job.await()
            mutex.withLock {
                if (generation == activeGeneration && _state.value.scope == currentScope) {
                    activeJob = null
                    activeIsForced = false
                    when (result) {
                        is NetworkResult.Success -> {
                            lastLoadedTime = clock()
                            _state.update {
                                it.copy(
                                    providers = result.data.providers.orEmpty(),
                                    hasLoaded = true,
                                    isRefreshing = false,
                                    error = null,
                                )
                            }
                        }

                        is NetworkResult.Failure -> {
                            _state.update {
                                it.copy(
                                    isRefreshing = false,
                                    error = result.error,
                                )
                            }
                        }
                    }
                }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            mutex.withLock {
                if (generation == activeGeneration) {
                    activeJob = null
                    activeIsForced = false
                    _state.update { it.copy(isRefreshing = false) }
                }
            }
            NetworkResult.Failure(NetworkError.Unknown(t.message ?: "Unknown error", t))
        }
    }

    companion object {
        val shared by lazy {
            val store = ModelCatalogStore()
            store.attachScopeObserver(
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                AuthManager.dataScopeFlow,
            )
            store
        }
    }
}
