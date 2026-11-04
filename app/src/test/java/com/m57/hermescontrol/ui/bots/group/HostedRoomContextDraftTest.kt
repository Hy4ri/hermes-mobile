package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.ws.HostedRoomMember
import com.m57.hermescontrol.ui.bots.group.components.isExactHostedContextRetry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedRoomContextDraftTest {
    private val lostAck = "Connection lost before the settings ACK"
    private val pending =
        HostedPendingContextSave(
            eventId = "original-save-id",
            purpose = "Release planning",
            notes = "Keep the agreed checklist",
            routing = "lead",
            leadMemberId = "member-1",
        )

    @Test
    fun `lost ACK allows exact pending retry after its selected lead leaves the roster`() {
        val remainingMembers = listOf(HostedRoomMember(memberId = "member-2", profile = "reviewer"))
        assertFalse(remainingMembers.any { it.memberId == pending.leadMemberId })

        assertTrue(
            isExactHostedContextRetry(
                pending = pending,
                error = lostAck,
                purpose = pending.purpose,
                notes = pending.notes,
                routing = pending.routing,
                leadMemberId = pending.leadMemberId,
            ),
        )
    }

    @Test
    fun `altered purpose notes routing or lead cannot use pending retry to bypass lead validation`() {
        val changedDrafts =
            listOf(
                pending.copy(purpose = "Changed purpose"),
                pending.copy(notes = "Changed notes"),
                pending.copy(routing = "everyone"),
                pending.copy(leadMemberId = "member-2"),
                pending.copy(purpose = " ${pending.purpose} "),
                pending.copy(notes = "${pending.notes}\n"),
                pending.copy(routing = null),
                pending.copy(leadMemberId = null),
                pending.copy(leadMemberId = ""),
            )

        for (draft in changedDrafts) {
            assertFalse(
                "Changed submitted payload must not reuse the pending save: $draft",
                isExactHostedContextRetry(
                    pending = pending,
                    error = lostAck,
                    purpose = draft.purpose,
                    notes = draft.notes,
                    routing = draft.routing,
                    leadMemberId = draft.leadMemberId,
                ),
            )
        }
    }

    @Test
    fun `matching draft without pending save or save error cannot bypass lead validation`() {
        for ((pendingSave, error) in listOf(null to lostAck, pending to null, null to null)) {
            assertFalse(
                isExactHostedContextRetry(
                    pending = pendingSave,
                    error = error,
                    purpose = pending.purpose,
                    notes = pending.notes,
                    routing = pending.routing,
                    leadMemberId = pending.leadMemberId,
                ),
            )
        }
    }

    @Test
    fun `exact notes-only retry keeps omitted routing and lead fields omitted`() {
        val notesOnly = pending.copy(routing = null, leadMemberId = null)

        assertTrue(
            isExactHostedContextRetry(
                pending = notesOnly,
                error = lostAck,
                purpose = notesOnly.purpose,
                notes = notesOnly.notes,
                routing = null,
                leadMemberId = null,
            ),
        )
        assertFalse(
            isExactHostedContextRetry(
                pending = notesOnly,
                error = lostAck,
                purpose = notesOnly.purpose,
                notes = notesOnly.notes,
                routing = "lead",
                leadMemberId = "member-1",
            ),
        )
    }

    @Test
    fun `explicit everyone retry is distinct from omitted routing and omitted lead reset`() {
        val everyone = pending.copy(routing = "everyone", leadMemberId = "")

        assertTrue(
            isExactHostedContextRetry(
                pending = everyone,
                error = lostAck,
                purpose = everyone.purpose,
                notes = everyone.notes,
                routing = "everyone",
                leadMemberId = "",
            ),
        )
        for ((routing, lead) in listOf(null to null, "everyone" to null)) {
            assertFalse(
                isExactHostedContextRetry(
                    pending = everyone,
                    error = lostAck,
                    purpose = everyone.purpose,
                    notes = everyone.notes,
                    routing = routing,
                    leadMemberId = lead,
                ),
            )
        }
    }
}
