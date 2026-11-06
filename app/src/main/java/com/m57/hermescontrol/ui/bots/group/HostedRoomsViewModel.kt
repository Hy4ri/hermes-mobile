package com.m57.hermescontrol.ui.bots.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesHostedRoomsSource
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.HostedRoomSnapshot
import com.m57.hermescontrol.data.ws.HostedTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class HostedRoomsViewModel : ViewModel() {
    private val controller =
        HostedRoomsController(HermesHostedRoomsSource(), ownerProvider = {
            AuthManager.currentDataScope()?.let {
                HostedOwner(
                    scopeKey = it.inMemoryKey(),
                    profile = it.activeProfileId,
                    generation = HermesWsClient.connectionGeneration,
                    connected = HermesWsClient.connectionStatus.value == ConnectionStatus.CONNECTED,
                )
            }
        })
    val state = controller.state

    suspend fun refresh() = controller.refresh()

    fun syncOwner() = controller.syncOwner()

    /** Every opening reads fresh server state and rejects completion for a different owner or room. */
    suspend fun loadContext(
        owner: HostedOwner,
        roomId: String,
    ): Result<HostedRoomSnapshot> =
        contextResult {
            requireContextScope(owner, roomId)
            controller.refresh()
            requireContextScope(owner, roomId)
            val current = state.value
            check(current.error == null) { current.error.orEmpty() }
            check(current.capabilities?.contextSupported == true) { "Room settings are unavailable" }
            requireNotNull(current.snapshot).also {
                check(it.room.roomId == roomId && it.context != null) { "Room settings are unavailable" }
            }
        }

    suspend fun saveContext(
        owner: HostedOwner,
        roomId: String,
        purpose: String,
        notes: String,
        routing: String?,
        leadMemberId: String?,
        retry: Boolean,
    ): Result<Unit> =
        contextResult {
            requireContextScope(owner, roomId)
            check(state.value.contextWritable) { "Room settings are not writable" }
            val previousReceipt = state.value.contextSavedEventId
            val pending = state.value.pendingContextSave
            if (
                retry && pending != null && pending.purpose == purpose && pending.notes == notes &&
                pending.routing == routing && pending.leadMemberId == leadMemberId
            ) {
                controller.retryContextSave()
            } else {
                controller.saveContext(purpose, notes, routing, leadMemberId)
            }
            requireContextScope(owner, roomId)
            check(state.value.contextSavedEventId != previousReceipt) {
                state.value.contextError ?: "Room settings save was not confirmed"
            }
        }

    private fun requireContextScope(
        owner: HostedOwner,
        roomId: String,
    ) {
        controller.syncOwner()
        check(state.value.owner == owner && owner.connected && state.value.selectedRoomId == roomId) {
            "The hosted room connection changed"
        }
    }

    private suspend fun <T> contextResult(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    fun select(roomId: String?) {
        controller.select(roomId)
        viewModelScope.launch { controller.refresh() }
    }

    fun send(text: String) {
        viewModelScope.launch { controller.send(text) }
    }

    fun retrySend() {
        viewModelScope.launch { controller.retryPendingSend() }
    }

    fun cancelPendingSend() {
        controller.cancelPendingSend()
    }

    fun stop() {
        viewModelScope.launch { controller.stop() }
    }

    fun retry(task: HostedTask) {
        viewModelScope.launch { controller.retry(task) }
    }

    fun approve(
        task: HostedTask,
        choice: String,
    ) {
        viewModelScope.launch { controller.approve(task, choice) }
    }
}
