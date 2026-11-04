package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.HostedCapabilities
import com.m57.hermescontrol.data.ws.HostedEvent
import com.m57.hermescontrol.data.ws.HostedRoom
import com.m57.hermescontrol.data.ws.HostedRoomSnapshot
import com.m57.hermescontrol.data.ws.HostedRoomsSource
import com.m57.hermescontrol.data.ws.HostedTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.security.MessageDigest
import java.util.UUID

/** A socket generation is transient; the authenticated connection/profile key owns the room and retry key. */
data class HostedOwner(
    val scopeKey: String,
    val profile: String,
    val generation: Int,
    val connected: Boolean,
)

data class HostedPendingSend(
    val eventId: String,
    val text: String,
)

/** Unconfirmed save retained only for an explicit retry, never sent during refresh or reconnect. */
data class HostedPendingContextSave(
    val eventId: String,
    val purpose: String,
    val notes: String,
    val routing: String? = null,
    val leadMemberId: String? = null,
)

data class HostedRoomsState(
    val owner: HostedOwner? = null,
    val capabilities: HostedCapabilities? = null,
    val unsupported: Boolean = false,
    val rooms: List<HostedRoom> = emptyList(),
    val selectedRoomId: String? = null,
    val snapshot: HostedRoomSnapshot? = null,
    val events: List<HostedEvent> = emptyList(),
    val cursor: Long = 0,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val pendingSend: HostedPendingSend? = null,
    val error: String? = null,
    val contextSaving: Boolean = false,
    val contextError: String? = null,
    val pendingContextSave: HostedPendingContextSave? = null,
    val contextSavedEventId: String? = null,
) {
    val writable: Boolean
        get() =
            owner?.connected == true && capabilities?.supported == true &&
                capabilities.driver && snapshot?.driverStatus?.running == true &&
                snapshot.room.disbandedAt == null && error == null && !loading && !busy && !contextSaving

    /** Settings do not require a running discussion driver; the server owns write authorization. */
    val contextWritable: Boolean
        get() =
            owner?.connected == true && capabilities?.supported == true && capabilities.contextSupported &&
                snapshot?.context != null && snapshot.room.roomId == selectedRoomId &&
                snapshot.room.disbandedAt == null && !loading && !busy && !contextSaving
}

/** No legacy sessions, profile metadata writes, or local discussion driver exist on this route. */
class HostedRoomsController(
    private val source: HostedRoomsSource,
    private val ownerProvider: () -> HostedOwner?,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutableState = MutableStateFlow(HostedRoomsState())
    val state = mutableState.asStateFlow()
    private val refreshLock = Mutex()
    private var revision = 0L
    private val suppressedMemberActivities = mutableMapOf<String, Set<MemberActivityIdentity>>()

    private data class MemberActivityIdentity(
        val threadId: String?,
        val roundIndex: Int?,
        val startedAt: Double?,
    )

    private fun reconcileMemberActivity(snapshot: HostedRoomSnapshot): HostedRoomSnapshot {
        val driverStatus = snapshot.driverStatus ?: return snapshot
        val memberActivity = driverStatus.memberActivity ?: return snapshot
        if (suppressedMemberActivities.isEmpty()) return snapshot

        // Keep suppression only while those exact old rows remain in a fresh, serialized snapshot.
        // A member can have more than one queued task, so a distinct row must not release older rows.
        suppressedMemberActivities.keys.toList().forEach { memberId ->
            val observed =
                memberActivity
                    .filter { it.memberId == memberId }
                    .map {
                        MemberActivityIdentity(it.threadId, it.roundIndex, it.startedAt)
                    }.toSet()
            val remaining = suppressedMemberActivities.getValue(memberId).intersect(observed)
            if (remaining.isEmpty()) {
                suppressedMemberActivities.remove(memberId)
            } else {
                suppressedMemberActivities[memberId] = remaining
            }
        }
        val reconciledList =
            memberActivity.filter { activity ->
                MemberActivityIdentity(activity.threadId, activity.roundIndex, activity.startedAt) !in
                    suppressedMemberActivities[activity.memberId].orEmpty()
            }

        return snapshot.copy(driverStatus = driverStatus.copy(memberActivity = reconciledList))
    }

    /** Called immediately on authentication/profile/socket changes, even while an RPC is in flight. */
    fun syncOwner() {
        val owner = ownerProvider()
        val old = state.value.owner
        if (old == owner) return
        revision++
        suppressedMemberActivities.clear()
        mutableState.value =
            if (owner == null || old == null || old.scopeKey != owner.scopeKey || old.profile != owner.profile) {
                HostedRoomsState(owner = owner)
            } else {
                state.value.copy(
                    owner = owner,
                    capabilities = null,
                    unsupported = false,
                    snapshot = null,
                    loading = false,
                    busy = false,
                    error = null,
                    contextSaving = false,
                    contextError = null,
                    contextSavedEventId = null,
                )
            }
    }

    fun select(roomId: String?) {
        syncOwner()
        if (roomId != null && state.value.rooms.none { it.roomId == roomId }) return
        if (state.value.selectedRoomId == roomId) return
        // Do not silently abandon an unacknowledged submission to select a different room.
        if (state.value.pendingSend != null) return
        revision++
        suppressedMemberActivities.clear()
        mutableState.update {
            it.copy(
                selectedRoomId = roomId,
                snapshot = null,
                events = emptyList(),
                cursor = 0,
                error = null,
                loading = false,
                busy = false,
                contextSaving = false,
                contextError = null,
                pendingContextSave = null,
                contextSavedEventId = null,
            )
        }
    }

    private fun owns(
        owner: HostedOwner,
        stamp: Long,
    ): Boolean {
        syncOwner()
        return revision == stamp && state.value.owner == owner && owner.connected
    }

    suspend fun refresh() {
        syncOwner()
        refreshLock.lock()
        syncOwner()
        val owner = state.value.owner
        if (owner?.connected != true) {
            refreshLock.unlock()
            return
        }
        val stamp = revision
        mutableState.update {
            it.copy(
                loading = it.capabilities == null || (it.selectedRoomId != null && it.snapshot == null),
            )
        }
        try {
            var capabilities = state.value.capabilities
            if (capabilities == null || (capabilities.supported && !capabilities.driver)) {
                capabilities = source.capabilities(owner.profile)
                if (!owns(owner, stamp)) return
                mutableState.update {
                    it.copy(
                        capabilities = capabilities,
                        unsupported = !capabilities.supported,
                        error = if (capabilities.supported) it.error else null,
                    )
                }
            }
            if (!capabilities.supported) return
            val roomId = state.value.selectedRoomId
            if (roomId == null) {
                val rooms = mutableListOf<HostedRoom>()
                var offset = 0
                do {
                    val page = source.list(owner.profile, offset)
                    if (!owns(owner, stamp)) return
                    rooms += page.rooms
                    val next = page.nextOffset
                    require(next == null || next > offset) { "Hosted room list did not advance" }
                    offset = next ?: break
                } while (true)
                mutableState.update { it.copy(rooms = rooms.distinctBy(HostedRoom::roomId)) }
            } else {
                val rawSnapshot = source.state(owner.profile, roomId)
                if (!owns(owner, stamp)) return
                require(rawSnapshot.room.roomId == roomId) { "Hosted room identity mismatch" }
                val snapshot = reconcileMemberActivity(rawSnapshot)
                mutableState.update { it.copy(snapshot = snapshot) }
                do {
                    val before = state.value.cursor
                    val page = source.log(owner.profile, roomId, before, capabilities.maxLogLimit.coerceIn(1, 100))
                    if (!owns(owner, stamp)) return
                    // Validate the entire page before publishing it. Never hide a missing event behind a later cursor.
                    var expected = before + 1
                    for (event in page.events) {
                        require(
                            event.roomId == roomId && event.seq == expected,
                        ) { "Hosted room replay sequence mismatch" }
                        expected++
                    }
                    require(
                        page.cursor == expected - 1 && page.latestSeq >= page.cursor,
                    ) { "Invalid hosted replay cursor" }
                    require(!page.hasMore || page.cursor > before) { "Hosted room replay did not advance" }
                    mutableState.update {
                        val events = it.events + page.events
                        val pending = it.pendingSend
                        val confirmed =
                            pending != null && events.any { event -> event.eventId == wireEventId(pending.eventId) }
                        it.copy(events = events, cursor = page.cursor, pendingSend = if (confirmed) null else pending)
                    }
                } while (page.hasMore)
            }
            if (owns(owner, stamp)) mutableState.update { it.copy(error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (owns(owner, stamp)) {
                mutableState.update {
                    val clearedSnapshot =
                        it.snapshot?.let { snapshot ->
                            val driverStatus = snapshot.driverStatus
                            if (driverStatus?.memberActivity != null) {
                                snapshot.copy(driverStatus = driverStatus.copy(memberActivity = null))
                            } else {
                                snapshot
                            }
                        }
                    if (e is HermesWsClient.HermesRpcException && e.code == -32601 && it.capabilities == null) {
                        it.copy(unsupported = true, snapshot = clearedSnapshot)
                    } else {
                        it.copy(error = e.message ?: "Hosted room request failed", snapshot = clearedSnapshot)
                    }
                }
            }
        } finally {
            if (owns(owner, stamp)) mutableState.update { it.copy(loading = false) }
            refreshLock.unlock()
        }
    }

    /** Each intended save gets a fresh id; omitted routing stays omitted, not copied from a stale snapshot. */
    suspend fun saveContext(
        purpose: String,
        notes: String,
        routing: String? = null,
        leadMemberId: String? = null,
    ) {
        syncOwner()
        val current = state.value
        if (!current.contextWritable) return
        try {
            require(routing == null || routing in setOf("everyone", "lead")) { "Invalid hosted room routing" }
            require(routing != null || leadMemberId == null) { "Select routing when changing the lead" }
            if (routing == "lead") {
                require(
                    !leadMemberId.isNullOrBlank() &&
                        current.snapshot?.room?.members.orEmpty().any {
                            it.memberId == leadMemberId
                        },
                ) { "The lead must be a member of this hosted room" }
            }
        } catch (e: IllegalArgumentException) {
            mutableState.update { it.copy(contextError = e.message) }
            return
        }
        val pending =
            HostedPendingContextSave(
                eventId = newId(),
                purpose = purpose,
                notes = notes,
                routing = routing,
                leadMemberId = if (routing == "everyone") "" else leadMemberId,
            )
        submitContextSave(pending)
    }

    /** An uncertain save is retried only by the user, with the exact original identity and payload. */
    suspend fun retryContextSave() {
        syncOwner()
        val current = state.value
        if (!current.contextWritable) return
        val pending = current.pendingContextSave ?: return
        // Do not revalidate the roster: an already accepted id can be retried after its lead was removed.
        submitContextSave(pending)
    }

    private suspend fun submitContextSave(pending: HostedPendingContextSave) {
        val owner = state.value.owner ?: return
        val roomId = state.value.selectedRoomId ?: return
        val stamp = revision
        var locked = false
        var confirmed = false
        mutableState.update { it.copy(contextSaving = true, contextError = null, pendingContextSave = pending) }
        try {
            // A refresh started before this save must finish before its ACK can update the snapshot.
            refreshLock.lock()
            locked = true
            if (!owns(owner, stamp) || !state.value.copy(contextSaving = false).contextWritable) return
            val receipt =
                source.context(
                    profile = owner.profile,
                    roomId = roomId,
                    eventId = pending.eventId,
                    purpose = pending.purpose,
                    notes = pending.notes,
                    routing = pending.routing,
                    leadMemberId = pending.leadMemberId,
                )
            if (!owns(owner, stamp)) return
            require(
                receipt.event.roomId == roomId && receipt.event.eventId == pending.eventId &&
                    receipt.event.kind == "room.context_set",
            ) { "Hosted room context receipt identity mismatch" }
            mutableState.update {
                it.copy(
                    snapshot = it.snapshot?.copy(context = receipt.context),
                    pendingContextSave = null,
                    contextSavedEventId = pending.eventId,
                    contextError = null,
                )
            }
            confirmed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (owns(owner, stamp)) {
                mutableState.update { it.copy(contextError = e.message ?: "Hosted room settings save failed") }
            }
        } finally {
            if (owns(owner, stamp)) mutableState.update { it.copy(contextSaving = false) }
            if (locked) refreshLock.unlock()
        }
        if (confirmed && owns(owner, stamp)) refresh()
    }

    suspend fun send(text: String) {
        if (text.isBlank() || state.value.pendingSend != null) return
        mutate { owner, roomId ->
            val pending = HostedPendingSend(newId(), text)
            mutableState.update { it.copy(pendingSend = pending) }
            source.send(owner.profile, roomId, pending.eventId, pending.text)
            mutableState.update { if (it.owner == owner) it.copy(pendingSend = null) else it }
        }
    }

    /** Only explicit user action retransmits an uncertain send, always with its original id and payload. */
    suspend fun retryPendingSend() {
        val pending = state.value.pendingSend ?: return
        mutate { owner, roomId ->
            source.send(owner.profile, roomId, pending.eventId, pending.text)
            mutableState.update { if (it.owner == owner) it.copy(pendingSend = null) else it }
        }
    }

    suspend fun stop() = mutate { owner, roomId -> source.stop(owner.profile, roomId, newId()) }

    suspend fun retry(task: HostedTask) {
        syncOwner()
        val current = state.value
        if (!current.writable) return
        val currentPending =
            current.snapshot
                ?.driverStatus
                ?.pendingActions
                .orEmpty()
        if (task.kind != "retry" || task !in currentPending) {
            mutableState.update { it.copy(error = "Hosted retry action is no longer available") }
            return
        }
        revision++
        val oldActivity =
            current.snapshot?.driverStatus?.memberActivity.orEmpty().filter {
                it.memberId == task.memberId
            }
        if (oldActivity.isNotEmpty()) {
            suppressedMemberActivities[task.memberId] =
                oldActivity
                    .map {
                        MemberActivityIdentity(it.threadId, it.roundIndex, it.startedAt)
                    }.toSet()
        }
        mutableState.update { state ->
            val snapshot = state.snapshot ?: return@update state
            val driverStatus = snapshot.driverStatus ?: return@update state
            val activity = driverStatus.memberActivity ?: return@update state
            val filteredActivity = activity.filterNot { it.memberId == task.memberId }
            state.copy(
                snapshot =
                    snapshot.copy(
                        driverStatus = driverStatus.copy(memberActivity = filteredActivity),
                    ),
            )
        }
        mutate { owner, roomId ->
            source.retry(owner.profile, roomId, task)
        }
    }

    suspend fun approve(
        task: HostedTask,
        choice: String,
    ) = mutate { owner, roomId ->
        require(
            task.kind == "approval" && task in
                state.value.snapshot
                    ?.driverStatus
                    ?.pendingActions
                    .orEmpty(),
        )
        require(choice in task.approval?.choices.orEmpty() && choice in setOf("once", "deny"))
        val requestId = requireNotNull(task.requestId).also { require(it.isNotBlank()) }
        source.approve(owner.profile, roomId, task, requestId, choice)
    }

    private suspend fun mutate(block: suspend (HostedOwner, String) -> Unit) {
        syncOwner()
        val current = state.value
        if (!current.writable) return
        val owner = requireNotNull(current.owner)
        val roomId = current.selectedRoomId ?: return
        val stamp = revision
        var completed = false
        mutableState.update { it.copy(busy = true, error = null) }
        try {
            if (!owns(owner, stamp)) return
            block(owner, roomId)
            completed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (owns(owner, stamp)) mutableState.update { it.copy(error = e.message ?: "Hosted room request failed") }
        } finally {
            if (owns(owner, stamp)) mutableState.update { it.copy(busy = false) }
        }
        if (completed && owns(owner, stamp)) refresh()
    }

    private fun wireEventId(id: String): String =
        "user:" +
            MessageDigest
                .getInstance("SHA-256")
                .digest(id.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
}
