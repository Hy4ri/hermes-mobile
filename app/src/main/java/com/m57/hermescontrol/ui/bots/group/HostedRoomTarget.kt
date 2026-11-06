package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.ws.HostedDriverStatus

/** What one `groups.state` poll says the room is doing or waiting on. */
internal enum class HostedRoomTargetState {
    UNAVAILABLE,
    IDLE,
    WORKING,
    WAITING_APPROVAL,
    WAITING_RETRY,
    BLOCKED,
}

/**
 * Room work in the gateway's own terms (`HostedRoomService.status`), grouped for one line of UI.
 * [cancellable] is exactly the task statuses `groups.stop` cancels: queued, deferred, running and
 * indeterminate. `stopping` tasks are already on their way out, so they are not counted again.
 */
internal data class HostedRoomTarget(
    val state: HostedRoomTargetState,
    val approvals: Int = 0,
    val retries: Int = 0,
    val queued: Int = 0,
    val deferred: Int = 0,
    val running: Int = 0,
    val stopping: Int = 0,
    val indeterminate: Int = 0,
) {
    val cancellable: Int
        get() = queued + deferred + running + indeterminate

    /** Nothing to stop means a greyed button, not a tap that quietly does nothing. */
    val canCancel: Boolean
        get() = cancellable > 0

    val hasAttention: Boolean
        get() =
            state == HostedRoomTargetState.WAITING_APPROVAL ||
                state == HostedRoomTargetState.WAITING_RETRY ||
                state == HostedRoomTargetState.BLOCKED
}

/**
 * Same reading as the Desktop's group-chat attention line: approvals first, then retry rows, then a
 * block that still has an unresolved task. The gateway also reports `blocked` while a stopped task
 * settles; that is Stop at work, so it only counts as blocked when an indeterminate task holds the
 * room or no stop is settling.
 */
internal fun hostedRoomTarget(driver: HostedDriverStatus?): HostedRoomTarget {
    if (driver == null || !driver.running) return HostedRoomTarget(HostedRoomTargetState.UNAVAILABLE)
    val actions = driver.pendingActions
    val approvals = actions.count { it.kind == "approval" }
    val retries = actions.size - approvals
    val counts = driver.counts
    val queued = counts["queued"] ?: 0
    val deferred = counts["deferred"] ?: 0
    val running = counts["running"] ?: 0
    val stopping = counts["stopping"] ?: 0
    val indeterminate = counts["indeterminate"] ?: 0
    val blocked = driver.blocked && (indeterminate > 0 || stopping == 0)
    val live =
        driver.working || queued > 0 || deferred > 0 || running > 0 || stopping > 0 ||
            !driver.memberActivity.isNullOrEmpty()
    val state =
        when {
            approvals > 0 -> HostedRoomTargetState.WAITING_APPROVAL
            retries > 0 -> HostedRoomTargetState.WAITING_RETRY
            blocked -> HostedRoomTargetState.BLOCKED
            live -> HostedRoomTargetState.WORKING
            else -> HostedRoomTargetState.IDLE
        }
    return HostedRoomTarget(
        state = state,
        approvals = approvals,
        retries = retries,
        queued = queued,
        deferred = deferred,
        running = running,
        stopping = stopping,
        indeterminate = indeterminate,
    )
}
