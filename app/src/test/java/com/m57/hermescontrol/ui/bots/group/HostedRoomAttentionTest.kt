package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.ws.HostedDriverStatus
import com.m57.hermescontrol.data.ws.HostedTask
import org.junit.Assert.assertEquals
import org.junit.Test

class HostedRoomAttentionTest {
    private fun driver(
        blocked: Boolean = false,
        counts: Map<String, Int> = emptyMap(),
        pending: List<HostedTask> = emptyList(),
    ) = HostedDriverStatus(
        running = true,
        working = true,
        blocked = blocked,
        counts = counts,
        pendingActions = pending,
    )

    private fun approval() = HostedTask(kind = "approval")

    private fun retry() = HostedTask(kind = "retry")

    @Test
    fun `a gateway without driver status reports nothing`() {
        assertEquals(emptyList<HostedRoomAttention>(), hostedRoomAttention(null))
    }

    @Test
    fun `a settling stop is not a block`() {
        assertEquals(
            emptyList<HostedRoomAttention>(),
            hostedRoomAttention(driver(blocked = true, counts = mapOf("stopping" to 1))),
        )
    }

    @Test
    fun `an unresolved task makes a settling stop a block`() {
        assertEquals(
            listOf(HostedRoomAttention.Blocked),
            hostedRoomAttention(
                driver(blocked = true, counts = mapOf("stopping" to 1, "indeterminate" to 1)),
            ),
        )
    }

    @Test
    fun `a blocked room without a settling stop is blocked`() {
        assertEquals(
            listOf(HostedRoomAttention.Blocked),
            hostedRoomAttention(driver(blocked = true, counts = mapOf("queued" to 1))),
        )
        assertEquals(
            listOf(HostedRoomAttention.Blocked),
            hostedRoomAttention(driver(blocked = true)),
        )
    }

    @Test
    fun `approvals and other pending actions are counted separately`() {
        assertEquals(
            listOf(HostedRoomAttention.Approvals(1), HostedRoomAttention.NeedsAttention(1)),
            hostedRoomAttention(driver(pending = listOf(approval(), retry()))),
        )
    }

    @Test
    fun `approvals alone do not claim other attention`() {
        assertEquals(
            listOf(HostedRoomAttention.Approvals(2)),
            hostedRoomAttention(driver(pending = listOf(approval(), approval()))),
        )
    }

    @Test
    fun `blocked comes before approvals and other actions`() {
        assertEquals(
            listOf(
                HostedRoomAttention.Blocked,
                HostedRoomAttention.Approvals(1),
                HostedRoomAttention.NeedsAttention(1),
            ),
            hostedRoomAttention(driver(blocked = true, pending = listOf(approval(), retry()))),
        )
    }
}
