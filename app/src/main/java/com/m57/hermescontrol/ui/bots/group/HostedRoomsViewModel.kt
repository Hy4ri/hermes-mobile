package com.m57.hermescontrol.ui.bots.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesHostedRoomsSource
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.HostedTask
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
