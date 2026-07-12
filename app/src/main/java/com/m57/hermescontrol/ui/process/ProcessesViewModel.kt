package com.m57.hermescontrol.ui.process

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.ProcessInfo
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProcessesUiState(
    val isLoading: Boolean = false,
    val processes: List<ProcessInfo> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    /** The process id currently being killed (drives the in-row spinner). */
    val killingId: String? = null,
    /** The active session id the list is scoped to (null = no chat session yet). */
    val sessionId: String? = null,
)

class ProcessesViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ProcessesUiState())
    val uiState: StateFlow<ProcessesUiState> = _uiState.asStateFlow()

    init {
        // Keep the session id in sync and auto-load whenever it becomes known.
        viewModelScope.launch {
            ActiveSessionHolder.activeSessionId.collect { sid ->
                _uiState.update { it.copy(sessionId = sid) }
                if (sid != null) {
                    load()
                } else {
                    _uiState.update {
                        it.copy(
                            processes = emptyList(),
                            errorMessage = null,
                        )
                    }
                }
            }
        }
    }

    /** Pull the active session's process snapshot from the gateway. */
    fun load() {
        val sessionId =
            ActiveSessionHolder.activeSessionId.value
                ?: run {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            processes = emptyList(),
                            errorMessage = "Open a chat session first — process list is session-scoped.",
                        )
                    }
                    return
                }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val result =
                    HermesWsClient
                        .request(
                            WsMethods.PROCESS_LIST,
                            mapOf("session_id" to sessionId),
                        ).await()
                val processes = parseProcessList(result)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        processes = processes,
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Could not load processes: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    /** Kill a single process by id, then refresh the list. */
    fun kill(processId: String) {
        val sessionId =
            ActiveSessionHolder.activeSessionId.value
                ?: run {
                    _uiState.update { it.copy(toastMessage = "No active session — cannot kill.") }
                    return
                }
        if (_uiState.value.killingId != null) return // one kill at a time

        _uiState.update { it.copy(killingId = processId) }
        viewModelScope.launch {
            try {
                HermesWsClient
                    .request(
                        WsMethods.PROCESS_KILL,
                        mapOf(
                            "process_id" to processId,
                            "session_id" to sessionId,
                        ),
                    ).await()
                _uiState.update { it.copy(killingId = null, toastMessage = "Process killed") }
                load()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        killingId = null,
                        toastMessage = "Kill failed: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    /**
     * Parse the `process.list` RPC result (`{ "processes": [ {…} ] }`) into
     * [ProcessInfo]s. The result is a `kotlinx` `toAny()` map (numbers arrive as
     * Double/Long/Int, booleans as Boolean) — see [HermesWsClient].
     */
    private fun parseProcessList(result: Any?): List<ProcessInfo> {
        val map = result as? Map<*, *> ?: return emptyList()
        val rawList = map["processes"] as? List<*> ?: return emptyList()
        return rawList.mapNotNull { entry ->
            val proc = entry as? Map<*, *> ?: return@mapNotNull null
            ProcessInfo.fromMap(proc)
        }
    }
}

private fun Map<*, *>.str(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }

private fun Map<*, *>.intOrNull(key: String): Int? = (this[key] as? Number)?.toInt()

private fun Map<*, *>.bool(key: String): Boolean = (this[key] as? Boolean) ?: false

/** Build a [ProcessInfo] from a raw `process.list` entry map. */
fun ProcessInfo.Companion.fromMap(raw: Map<*, *>): ProcessInfo? {
    val m = raw.mapKeys { it.key.toString() }
    val sessionId = m.str("session_id") ?: return null
    return ProcessInfo(
        sessionId = sessionId,
        command = m.str("command"),
        cwd = m.str("cwd"),
        pid = m.intOrNull("pid"),
        startedAt = m.str("started_at"),
        uptimeSeconds = m.intOrNull("uptime_seconds"),
        status = m.str("status"),
        outputPreview = m.str("output_preview"),
        outputTail = m.str("output_tail"),
        exitCode = m.intOrNull("exit_code"),
        sessionScoped = m.bool("session_scoped"),
    )
}
