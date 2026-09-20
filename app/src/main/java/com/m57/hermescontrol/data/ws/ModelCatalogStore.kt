package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private var activeResult: CompletableDeferred<NetworkResult<ModelOptionsResponse>>? = null
    private val inFlightFetches = mutableListOf<Job>()
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
                resetForScopeLocked(newScope)
            }
        }
    }

    /**
     * Invalidates all work belonging to the previous data scope.
     *
     * Unlike force-refresh supersession, a scope switch really does make the
     * old network work invalid, so those fetches are cancelled.
     */
    private fun resetForScopeLocked(newScope: DataScope?) {
        inFlightFetches.forEach { it.cancel() }
        inFlightFetches.clear()

        activeResult?.cancel()
        activeResult = null
        activeIsForced = false
        activeGeneration++
        lastLoadedTime = 0L
        _state.value = ModelCatalogState(scope = newScope)
    }

    /**
     * Starts one physical catalog request for the current logical load.
     *
     * Multiple callers await [resultGate]. A forced refresh may supersede a
     * normal physical request without cancelling that gate, so callers already
     * waiting on the normal request transparently receive the forced result.
     */
    private fun startFetchLocked(
        refresh: Boolean,
        requestScope: DataScope?,
        generation: Long,
        resultGate: CompletableDeferred<NetworkResult<ModelOptionsResponse>>,
    ) {
        inFlightFetches.removeAll { it.isCompleted }
        inFlightFetches +=
            scope.launch {
                val result =
                    try {
                        repository.load(refresh = refresh)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        NetworkResult.Failure(
                            NetworkError.Unknown(t.message ?: "Unknown error", t),
                        )
                    }

                mutex.withLock {
                    // A newer forced request or scope switch owns publication.
                    if (
                        generation != activeGeneration ||
                        _state.value.scope != requestScope ||
                        activeResult !== resultGate
                    ) {
                        return@withLock
                    }

                    activeResult = null
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

                    // Complete only from the authoritative generation.
                    // Every consumer of this logical load receives the same result.
                    resultGate.complete(result)
                }
            }
    }

    suspend fun ensureLoaded(forceRefresh: Boolean = false): NetworkResult<ModelOptionsResponse> {
        val currentScope = getCurrentScope()

        val resultGate =
            mutex.withLock {
                // Scope switch check
                if (_state.value.scope != currentScope) {
                    resetForScopeLocked(currentScope)
                }

                val now = clock()
                val isFresh = _state.value.hasLoaded && (now - lastLoadedTime < ttlMs)

                if (!forceRefresh && isFresh) {
                    return NetworkResult.Success(ModelOptionsResponse(_state.value.providers))
                }

                val existing = activeResult
                if (existing?.isActive == true) {
                    if (forceRefresh && !activeIsForced) {
                        // Supersede the physical request, but deliberately keep
                        // the shared result gate alive. Existing consumers must
                        // not be cancelled just because another screen refreshed.
                        val generation = ++activeGeneration
                        activeIsForced = true
                        _state.update { it.copy(isRefreshing = true) }
                        startFetchLocked(
                            refresh = true,
                            requestScope = currentScope,
                            generation = generation,
                            resultGate = existing,
                        )
                    }

                    existing
                } else {
                    val generation = ++activeGeneration
                    val newResult = CompletableDeferred<NetworkResult<ModelOptionsResponse>>()

                    activeResult = newResult
                    activeIsForced = forceRefresh
                    _state.update { it.copy(isRefreshing = true) }

                    startFetchLocked(
                        refresh = forceRefresh,
                        requestScope = currentScope,
                        generation = generation,
                        resultGate = newResult,
                    )

                    newResult
                }
            }

        return resultGate.await()
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
