package com.m57.hermescontrol.data.ws

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class ConnectionHealth(
    private val scope: CoroutineScope,
    private val isConnected: () -> Boolean,
    private val pingRequest: suspend (Long) -> Unit,
    private val cancelSocket: () -> Unit,
    private val pendingReply: () -> Boolean,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val log: (String) -> Unit = {},
    private val debugLog: (String) -> Unit = {},
) {
    companion object {
        internal const val HEARTBEAT_INTERVAL_MS = 15_000L
        internal const val STALE_THRESHOLD_MS = 30_000L
        internal const val LIVENESS_PROBE_TIMEOUT_MS = 5_000L
    }

    @Volatile
    var lastPongTimestamp: Long = 0L
        internal set

    private val _lastLatencyMs = MutableStateFlow<Long?>(null)
    val lastLatencyMs: StateFlow<Long?> = _lastLatencyMs.asStateFlow()

    val isHealthy: Boolean
        get() = isConnected() && (nowMs() - lastPongTimestamp < STALE_THRESHOLD_MS)

    private var healthJob: Job? = null
    private var foregroundProbeJob: Job? = null
    private var transportProbeJob: Job? = null

    fun start() {
        healthJob?.cancel()
        lastPongTimestamp = nowMs()
        healthJob =
            scope.launch {
                while (isConnected()) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    if (isConnected()) {
                        try {
                            ping(timeoutMs = LIVENESS_PROBE_TIMEOUT_MS)
                        } catch (e: Exception) {
                            log("Health check ping failed: ${e.message}")
                        }
                    }
                    if (!runHealthCheckPass()) break
                }
            }
    }

    fun stop() {
        healthJob?.cancel()
        healthJob = null
        _lastLatencyMs.value = null
    }

    fun onInboundFrame() {
        lastPongTimestamp = nowMs()
    }

    suspend fun ping(timeoutMs: Long = LIVENESS_PROBE_TIMEOUT_MS): Long {
        val start = nowMs()
        pingRequest(timeoutMs)
        val latency = (nowMs() - start).coerceAtLeast(0L)
        lastPongTimestamp = nowMs()
        _lastLatencyMs.value = latency
        return latency
    }

    @VisibleForTesting
    internal fun setLastLatencyForTest(latency: Long?) {
        _lastLatencyMs.value = latency
    }

    private fun runHealthCheckPass(): Boolean {
        if (!isConnected()) return false
        val staleMs = nowMs() - lastPongTimestamp
        if (staleMs <= STALE_THRESHOLD_MS) return true
        log("WebSocket stale (${staleMs / 1000}s without frames) — cancelling to trigger reconnect")
        cancelSocket()
        return false
    }

    @VisibleForTesting
    internal fun forceHealthCheckForTest(staleMillis: Long) {
        lastPongTimestamp = nowMs() - staleMillis
        runHealthCheckPass()
    }

    fun probeLivenessOnWake(deferredOnce: Boolean = false) {
        if (!isConnected()) return
        foregroundProbeJob?.cancel()
        foregroundProbeJob =
            scope.launch {
                val alive = runCatching { ping(LIVENESS_PROBE_TIMEOUT_MS) }.isSuccess
                if (alive) return@launch
                if (pendingReply() && !deferredOnce) {
                    delay(3_000L)
                    probeLivenessOnWake(deferredOnce = true)
                    return@launch
                }
                log("Foreground liveness probe failed — cancelling socket")
                cancelSocket()
            }
    }

    fun cancelForegroundProbe() {
        foregroundProbeJob?.cancel()
        foregroundProbeJob = null
    }

    fun probeLivenessOnTransportChange(
        timeoutMs: Long = LIVENESS_PROBE_TIMEOUT_MS,
        onFailureAction: () -> Unit = cancelSocket,
    ) {
        if (!isConnected()) return
        transportProbeJob?.cancel()
        transportProbeJob =
            scope.launch {
                debugLog("Network transport changed — probing WebSocket liveness")
                val alive = runCatching { ping(timeoutMs) }.isSuccess
                if (alive) {
                    debugLog("WebSocket liveness probe succeeded on new transport")
                    return@launch
                }
                log("Transport change liveness probe failed — cancelling socket")
                onFailureAction()
            }
    }
}
