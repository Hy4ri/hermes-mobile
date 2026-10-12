package com.m57.hermescontrol.ui.chat

import android.util.Log
import com.m57.hermescontrol.data.model.ProcessInfo
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.contract.ProcessKillParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.SessionIdParams
import com.m57.hermescontrol.data.ws.toAny
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tracks the active session's running background processes (`process.list`) and kills them
 * (`process.kill`) so the chat task widget can surface them (issue #1503).
 *
 * Mirrors the desktop composer status stack: the gateway has no push event for process
 * start/exit, so callers poll [refresh] while the chat is visible. Only running processes
 * are kept; finished ones drop out on the next snapshot.
 */
class ChatProcessesDelegate(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val runtimeSessionId: () -> String?,
    private val listProcesses: suspend (String) -> List<ProcessInfo> = ::listViaGateway,
    private val killProcess: suspend (String, String) -> Unit = ::killViaGateway,
) {
    private var refreshJob: Job? = null

    /** Pull the session's process snapshot and keep only running entries. Failures keep the last list. */
    fun refresh(sessionId: String? = runtimeSessionId()) {
        val target = sessionId?.trim()
        if (target.isNullOrEmpty()) return
        refreshJob?.cancel()
        refreshJob =
            scope.launch(ioDispatcher) {
                try {
                    val running = listProcesses(target).filter { it.isRunning }
                    uiState.update { current ->
                        // The session may have switched while the request was in flight.
                        if (runtimeSessionId()?.trim() !=
                            target
                        ) {
                            current
                        } else {
                            current.copy(backgroundProcesses = running)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "process.list failed for session $target: ${e.message}")
                }
            }
    }

    /** Kill one process, then re-sync. One kill at a time; the failure is logged and the list re-synced. */
    fun kill(processId: String) {
        val sessionId = runtimeSessionId()?.trim()
        if (sessionId.isNullOrEmpty() || uiState.value.killingProcessId != null) return
        uiState.update { it.copy(killingProcessId = processId) }
        scope.launch(ioDispatcher) {
            try {
                killProcess(sessionId, processId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "process.kill failed for $processId: ${e.message}")
            } finally {
                uiState.update { it.copy(killingProcessId = null) }
            }
            refresh(sessionId)
        }
    }

    companion object {
        private const val TAG = "ChatProcessesDelegate"

        private suspend fun listViaGateway(sessionId: String): List<ProcessInfo> {
            val result =
                HermesWsClient.call(
                    RpcMethods.PROCESS_LIST,
                    SessionIdParams(sessionId),
                    suppressErrorEvent = true,
                )
            return parseProcessList(result.toAny())
        }

        private suspend fun killViaGateway(
            sessionId: String,
            processId: String,
        ) {
            HermesWsClient.call(
                RpcMethods.PROCESS_KILL,
                ProcessKillParams(sessionId, processId),
                suppressErrorEvent = true,
            )
        }

        /** Parse the `process.list` result (`{ "processes": [ {…} ] }`) into [ProcessInfo]s. */
        internal fun parseProcessList(result: Any?): List<ProcessInfo> {
            val rawList = (result as? Map<*, *>)?.get("processes") as? List<*> ?: return emptyList()
            return rawList.mapNotNull { (it as? Map<*, *>)?.let(ProcessInfo::fromMap) }
        }
    }
}
