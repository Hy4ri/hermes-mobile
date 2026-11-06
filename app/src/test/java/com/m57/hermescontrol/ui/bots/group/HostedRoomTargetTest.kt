package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.ws.HostedDriverStatus
import com.m57.hermescontrol.data.ws.HostedMemberActivity
import com.m57.hermescontrol.data.ws.HostedTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedRoomTargetTest {
    private fun driver(
        running: Boolean = true,
        working: Boolean = false,
        blocked: Boolean = false,
        counts: Map<String, Int> = emptyMap(),
        pendingActions: List<HostedTask> = emptyList(),
        memberActivity: List<HostedMemberActivity>? = null,
    ) = HostedDriverStatus(
        running = running,
        working = working,
        blocked = blocked,
        counts = counts,
        pendingActions = pendingActions,
        memberActivity = memberActivity,
    )

    private fun action(kind: String) = HostedTask(kind = kind, memberId = "bot-1", taskId = "task-$kind")

    @Test
    fun `absent or stopped driver reports unavailable instead of idle`() {
        assertEquals(HostedRoomTargetState.UNAVAILABLE, hostedRoomTarget(null).state)
        assertEquals(
            HostedRoomTargetState.UNAVAILABLE,
            hostedRoomTarget(driver(running = false, working = true)).state,
        )
    }

    @Test
    fun `approval outranks retry and live work`() {
        val target =
            hostedRoomTarget(
                driver(
                    working = true,
                    counts = mapOf("running" to 1),
                    pendingActions = listOf(action("retry"), action("approval")),
                ),
            )
        assertEquals(HostedRoomTargetState.WAITING_APPROVAL, target.state)
        assertEquals(1, target.approvals)
        assertEquals(1, target.retries)
        assertTrue(target.hasAttention)
    }

    @Test
    fun `retry outranks live work`() {
        val target =
            hostedRoomTarget(
                driver(
                    working = true,
                    counts = mapOf("running" to 1),
                    pendingActions = listOf(action("retry")),
                ),
            )
        assertEquals(HostedRoomTargetState.WAITING_RETRY, target.state)
        assertEquals(0, target.approvals)
        assertEquals(1, target.retries)
        assertTrue(target.hasAttention)
    }

    @Test
    fun `blocked needs an unresolved task rather than a settling stop`() {
        assertEquals(
            HostedRoomTargetState.BLOCKED,
            hostedRoomTarget(driver(blocked = true, counts = mapOf("indeterminate" to 1))).state,
        )
        // A gateway that omits counts still reports the block the same way the desktop reads it.
        assertEquals(HostedRoomTargetState.BLOCKED, hostedRoomTarget(driver(blocked = true)).state)
        // The gateway also calls a room blocked while a stopped task settles; that is Stop at work.
        assertEquals(
            HostedRoomTargetState.WORKING,
            hostedRoomTarget(driver(working = true, blocked = true, counts = mapOf("stopping" to 1))).state,
        )
        assertEquals(
            HostedRoomTargetState.WORKING,
            hostedRoomTarget(driver(blocked = true, counts = mapOf("stopping" to 1))).state,
        )
    }

    @Test
    fun `queued or reported live activity counts as working when the flag is off`() {
        assertEquals(HostedRoomTargetState.WORKING, hostedRoomTarget(driver(working = true)).state)
        assertEquals(HostedRoomTargetState.WORKING, hostedRoomTarget(driver(counts = mapOf("queued" to 2))).state)
        assertEquals(HostedRoomTargetState.WORKING, hostedRoomTarget(driver(counts = mapOf("deferred" to 1))).state)
        assertEquals(HostedRoomTargetState.WORKING, hostedRoomTarget(driver(counts = mapOf("stopping" to 1))).state)
        assertEquals(
            HostedRoomTargetState.WORKING,
            hostedRoomTarget(driver(memberActivity = listOf(HostedMemberActivity("bot-1", "queued")))).state,
        )
    }

    @Test
    fun `finished counts leave the room idle`() {
        val target =
            hostedRoomTarget(
                driver(counts = mapOf("settled" to 3, "failed" to 1, "cancelled" to 2)),
            )
        assertEquals(HostedRoomTargetState.IDLE, target.state)
        assertFalse(target.hasAttention)
    }

    @Test
    fun `cancel counts exactly the task statuses groups stop cancels`() {
        val target =
            hostedRoomTarget(
                driver(
                    counts =
                        mapOf(
                            "queued" to 2,
                            "deferred" to 1,
                            "running" to 3,
                            "indeterminate" to 1,
                            "stopping" to 4,
                            "settled" to 5,
                            "failed" to 1,
                        ),
                ),
            )
        assertEquals(7, target.cancellable)
        assertTrue(target.canCancel)
    }

    @Test
    fun `an idle room offers nothing to cancel`() {
        assertFalse(hostedRoomTarget(driver()).canCancel)
        assertFalse(hostedRoomTarget(driver(counts = mapOf("settled" to 1))).canCancel)
        assertTrue(hostedRoomTarget(driver(counts = mapOf("queued" to 1))).canCancel)
        assertTrue(hostedRoomTarget(driver(counts = mapOf("indeterminate" to 1))).canCancel)
    }
}
