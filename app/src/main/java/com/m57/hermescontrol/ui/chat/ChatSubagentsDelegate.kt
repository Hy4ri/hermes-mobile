package com.m57.hermescontrol.ui.chat

import android.util.Log
import com.m57.hermescontrol.data.model.SubagentListItem
import com.m57.hermescontrol.data.ws.SubagentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Delegate managing subagent roster hydration via `subagent.list` and live transcript
 * tail polling via `subagent.tail` (issue #1089).
 */
class ChatSubagentsDelegate(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val runtimeSessionId: () -> String?,
    private val subagentRepository: SubagentRepository = SubagentRepository,
    private val pollingIntervalMs: Long = 1500L,
) {
    companion object {
        private const val TAG = "ChatSubagentsDelegate"
        const val SUBAGENT_TAIL_MAX_BYTES = 16384
    }

    private var pollingJob: Job? = null
    private var hydrationJob: Job? = null

    /**
     * Request the authoritative subagent roster from the gateway and merge it into [ChatUiState].
     *
     * A failed request degrades gracefully without clearing existing push-event indicators.
     */
    fun hydrateSubagents(sessionId: String? = runtimeSessionId()) {
        val targetSession = sessionId?.trim()
        if (targetSession.isNullOrEmpty()) return

        val requestTime = System.currentTimeMillis()
        hydrationJob?.cancel()
        hydrationJob =
            scope.launch(ioDispatcher) {
                try {
                    val response = subagentRepository.listSubagents(targetSession) ?: return@launch
                    uiState.update { current ->
                        val merged = mergeSubagentList(current.subagentIndicators, response.subagents, requestTime)
                        current.copy(subagentIndicators = merged)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to hydrate subagents for session $targetSession: ${e.message}")
                }
            }
    }

    /**
     * Toggle transcript inspection for [subagentId].
     *
     * If already inspecting [subagentId], closes the transcript view; otherwise opens it.
     */
    fun toggleSubagentTranscript(subagentId: String) {
        if (uiState.value.inspectingSubagentId == subagentId) {
            closeSubagentTranscript()
        } else {
            inspectSubagentTranscript(subagentId)
        }
    }

    /**
     * Start inspecting a live subagent's execution transcript, initiating bounded polling.
     */
    fun inspectSubagentTranscript(subagentId: String) {
        if (subagentId.isBlank()) return
        pollingJob?.cancel()

        uiState.update { current ->
            current.copy(
                inspectingSubagentId = subagentId,
                subagentTranscript =
                    SubagentTranscriptUiState(
                        subagentId = subagentId,
                        isLoading = true,
                    ),
            )
        }

        pollingJob =
            scope.launch(ioDispatcher) {
                val sessionId = runtimeSessionId() ?: return@launch
                pollTranscriptLoop(sessionId, subagentId)
            }
    }

    /**
     * Retry loading the transcript for the currently inspected subagent.
     */
    fun retryTranscript() {
        val subagentId = uiState.value.inspectingSubagentId ?: return
        inspectSubagentTranscript(subagentId)
    }

    /**
     * Stop transcript polling and dismiss the transcript disclosure.
     */
    fun closeSubagentTranscript() {
        pollingJob?.cancel()
        pollingJob = null
        uiState.update { current ->
            current.copy(
                inspectingSubagentId = null,
                subagentTranscript = null,
            )
        }
    }

    private suspend fun pollTranscriptLoop(
        sessionId: String,
        subagentId: String,
    ) {
        while (scope.isActive) {
            try {
                val tailResponse =
                    subagentRepository.tailSubagent(
                        sessionId = sessionId,
                        subagentId = subagentId,
                        maxBytes = SUBAGENT_TAIL_MAX_BYTES,
                    )

                if (tailResponse != null) {
                    val content = tailResponse.content()
                    val truncated = tailResponse.truncated ?: false
                    val bytesRead = tailResponse.bytesRead

                    uiState.update { current ->
                        if (current.inspectingSubagentId != subagentId) return@update current
                        val prev = current.subagentTranscript
                        if (prev == null || prev.text != content || prev.isLoading || prev.error != null) {
                            current.copy(
                                subagentTranscript =
                                    SubagentTranscriptUiState(
                                        subagentId = subagentId,
                                        text = content,
                                        isLoading = false,
                                        isTruncated = truncated,
                                        bytesRead = bytesRead,
                                        error = null,
                                    ),
                            )
                        } else {
                            current
                        }
                    }
                } else {
                    uiState.update { current ->
                        if (current.inspectingSubagentId != subagentId) return@update current
                        val prev = current.subagentTranscript
                        if (prev == null || prev.text.isEmpty()) {
                            current.copy(
                                subagentTranscript =
                                    SubagentTranscriptUiState(
                                        subagentId = subagentId,
                                        isLoading = false,
                                        error = "Unable to load transcript",
                                    ),
                            )
                        } else {
                            current.copy(subagentTranscript = prev.copy(isLoading = false))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching transcript tail for subagent $subagentId: ${e.message}")
                uiState.update { current ->
                    if (current.inspectingSubagentId != subagentId) return@update current
                    val prev = current.subagentTranscript
                    if (prev == null || prev.text.isEmpty()) {
                        current.copy(
                            subagentTranscript =
                                SubagentTranscriptUiState(
                                    subagentId = subagentId,
                                    isLoading = false,
                                    error = e.message ?: "Unable to load transcript",
                                ),
                        )
                    } else {
                        current.copy(subagentTranscript = prev.copy(isLoading = false))
                    }
                }
            }

            // Stop polling once the subagent reaches a terminal state (one final refresh is enough)
            val currentIndicator = uiState.value.subagentIndicators.firstOrNull { it.subagentId == subagentId }
            if (currentIndicator != null && !currentIndicator.isRunning) {
                break
            }

            delay(pollingIntervalMs)
        }
    }

    /**
     * Merges snapshot items from `subagent.list` with current indicators.
     *
     * Rules:
     * - `subagent_id` is the primary identity.
     * - Protects against races: if an indicator received a push event with timestamp > [requestTime],
     *   its push-event status/type is preserved and it is not pruned.
     * - Preserves local details (logs, taskIndex, taskCount, summary) not provided in the snapshot.
     * - Reconciles stale running indicators (issue #1117): an identified ([SubagentIndicator.subagentId] != null),
     *   running ([SubagentIndicator.isRunning]) indicator omitted from an authoritative snapshot is removed
     *   if its [SubagentIndicator.lastEventTimestamp] <= [requestTime]. This clears indicators that completed
     *   while disconnected where `subagent.complete` was missed.
     * - Protects against races for new subagents: a running indicator spawned or updated by a push event
     *   newer than [requestTime] is preserved even if omitted from an older in-flight snapshot response.
     * - Preserves already-terminal indicators (completed, failed, cancelled) and unassigned placeholders
     *   ([SubagentIndicator.subagentId] == null) even if omitted from the snapshot.
     */
    internal fun mergeSubagentList(
        current: List<SubagentIndicator>,
        listItems: List<SubagentListItem>,
        requestTime: Long,
    ): List<SubagentIndicator> {
        val merged = current.toMutableList()

        for (item in listItems) {
            val subagentId = item.subagentId.trim()
            if (subagentId.isEmpty() && item.goal.isNullOrBlank()) continue

            val idx =
                if (subagentId.isNotEmpty()) {
                    merged.indexOfFirst { it.subagentId == subagentId }
                } else {
                    merged.indexOfFirst { it.subagentId == null && it.goal == item.goal }
                }

            if (idx >= 0) {
                val existing = merged[idx]
                val isPushEventNewer = existing.lastEventTimestamp > requestTime

                val finalStatus =
                    if (isPushEventNewer) {
                        existing.status ?: item.status ?: "running"
                    } else if (existing.isComplete || existing.isFailed || existing.isCancelled) {
                        // Terminal state guard: don't revert to running
                        if (item.status == "completed" || item.status == "failed" ||
                            item.status == "cancelled" || item.status == "stopped"
                        ) {
                            item.status
                        } else {
                            existing.status
                        }
                    } else {
                        item.status ?: existing.status ?: "running"
                    }

                val finalType =
                    if (isPushEventNewer) {
                        existing.type
                    } else if (finalStatus == "completed" || finalStatus == "done" || existing.isComplete) {
                        "subagent.complete"
                    } else if (existing.type.isNotBlank()) {
                        existing.type
                    } else {
                        "subagent.progress"
                    }

                merged[idx] =
                    existing.copy(
                        type = finalType,
                        goal = item.goal ?: existing.goal,
                        status = finalStatus,
                        subagentId = if (subagentId.isNotEmpty()) subagentId else existing.subagentId,
                        durationSeconds =
                            if (isPushEventNewer) {
                                existing.durationSeconds ?: item.elapsedSeconds
                            } else {
                                item.elapsedSeconds ?: existing.durationSeconds
                            },
                        model = item.model ?: existing.model,
                        lastEventTimestamp = maxOf(existing.lastEventTimestamp, requestTime),
                    )
            } else {
                val status = item.status ?: "running"
                val isComp = status == "completed" || status == "done"
                val type = if (isComp) "subagent.complete" else "subagent.progress"

                merged.add(
                    SubagentIndicator(
                        type = type,
                        goal = item.goal,
                        status = status,
                        subagentId = subagentId.takeIf { it.isNotEmpty() },
                        durationSeconds = item.elapsedSeconds,
                        model = item.model,
                        lastEventTimestamp = requestTime,
                    ),
                )
            }
        }

        val snapshotIds = listItems.mapNotNull { it.subagentId.trim().takeIf { id -> id.isNotEmpty() } }.toSet()
        merged.removeAll { indicator ->
            val id = indicator.subagentId?.trim()
            !id.isNullOrEmpty() &&
                indicator.isRunning &&
                id !in snapshotIds &&
                indicator.lastEventTimestamp <= requestTime
        }

        return merged
    }
}
