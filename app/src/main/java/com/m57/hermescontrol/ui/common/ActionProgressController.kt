package com.m57.hermescontrol.ui.common

import com.m57.hermescontrol.data.model.ActionStatusResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Lifecycle of a backend action tracked through [ActionProgressController]. */
enum class ActionProgressPhase {
    /** The trigger request is in flight (dialog shown, nothing to poll yet). */
    STARTING,

    /** The action is running; the controller is polling its status log. */
    RUNNING,

    /** The action exited with code 0. */
    SUCCEEDED,

    /** The action exited non-zero, the trigger was rejected, or tracking was lost. */
    FAILED,
}

/**
 * UI state backing [ActionProgressDialog]. Driven exclusively by
 * [ActionProgressController]; screens read it via `controller.state`.
 */
data class ActionProgressState(
    val visible: Boolean = false,
    val phase: ActionProgressPhase = ActionProgressPhase.STARTING,
    val actionName: String? = null,
    val lines: List<String> = emptyList(),
    val exitCode: Int? = null,
    val error: String? = null,
    /**
     * Host-appended lines shown after the log tail once the action finishes
     * (e.g. a structured update receipt). Empty unless the host pushes them.
     */
    val trailingLines: List<String> = emptyList(),
)

/**
 * Reusable tracker for backend actions spawned through the action API.
 *
 * The backend pattern is: the trigger POST (`/api/.../action`) returns
 * immediately with `{ok, name, ...}` and the work runs in the background;
 * `GET /api/actions/{name}/status?lines=N` exposes a live log tail
 * (`{running, exit_code, lines}`). This controller opens the dialog, polls
 * the status every [pollIntervalMs] while it runs, and settles on
 * [ActionProgressPhase.SUCCEEDED] / [ActionProgressPhase.FAILED] when the
 * action exits. [onFinished] fires once with the final status so the host
 * can refresh whatever the action mutated.
 *
 * Cancel-safe: [dismiss] and [open]/[markStarted] re-entry cancel the poll
 * job, and the host's [CoroutineScope] (e.g. `viewModelScope`) stops the
 * loop when the screen leaves composition. The backend action itself keeps
 * running — dismissing only stops tracking it.
 *
 * Host a dialog with [ActionProgressDialog] off [state].
 */
class ActionProgressController(
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 1_500L,
    private val onFinished: ((ActionStatusResponse?) -> Unit)? = null,
) {
    private val _state = MutableStateFlow(ActionProgressState())
    val state: StateFlow<ActionProgressState> = _state.asStateFlow()

    private var pollJob: Job? = null

    /** Show the dialog (starting phase) before firing the trigger request. */
    fun open() {
        pollJob?.cancel()
        _state.value = ActionProgressState(visible = true)
    }

    /**
     * Trigger accepted; start polling the named action's status log.
     *
     * @param actionName action name returned by the trigger response.
     */
    fun markStarted(actionName: String) {
        pollJob?.cancel()
        _state.update {
            it.copy(
                phase = ActionProgressPhase.RUNNING,
                actionName = actionName,
                lines = emptyList(),
                exitCode = null,
                error = null,
            )
        }
        pollJob =
            scope.launch {
                var consecutiveFailures = 0
                while (isActive) {
                    delay(pollIntervalMs)
                    when (
                        val result =
                            safeApiCall { ApiClient.hermesApi.getActionStatus(actionName) }
                    ) {
                        is NetworkResult.Success -> {
                            consecutiveFailures = 0
                            val status = result.data
                            val done = status.running != true
                            _state.update {
                                it.copy(
                                    lines = status.lines ?: it.lines,
                                    exitCode = status.exit_code,
                                    phase =
                                        when {
                                            !done -> ActionProgressPhase.RUNNING
                                            status.exit_code == 0 -> ActionProgressPhase.SUCCEEDED
                                            else -> ActionProgressPhase.FAILED
                                        },
                                )
                            }
                            if (done) {
                                onFinished?.invoke(status)
                                break
                            }
                        }

                        is NetworkResult.Failure -> {
                            // Transient poll failures are ignored (the action may
                            // briefly restart the gateway); give up after a run of
                            // consecutive failures so the dialog can't spin forever.
                            consecutiveFailures++
                            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                _state.update {
                                    it.copy(
                                        phase = ActionProgressPhase.FAILED,
                                        error = result.error.message,
                                    )
                                }
                                break
                            }
                        }
                    }
                }
            }
    }

    /** Trigger was rejected — surface the error in the dialog. */
    fun fail(error: String) {
        pollJob?.cancel()
        _state.update { it.copy(phase = ActionProgressPhase.FAILED, error = error) }
    }

    /**
     * Append host-supplied lines after the log tail (e.g. an update receipt
     * summary). Safe to call after the action has finished; only affects
     * [ActionProgressState.trailingLines], never the poll loop.
     */
    fun pushTrailingLines(lines: List<String>) {
        if (lines.isEmpty()) return
        _state.update { it.copy(trailingLines = it.trailingLines + lines) }
    }

    /** Stop tracking and hide the dialog. Safe mid-run (action keeps going). */
    fun dismiss() {
        pollJob?.cancel()
        _state.value = ActionProgressState()
    }

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 5
    }
}
