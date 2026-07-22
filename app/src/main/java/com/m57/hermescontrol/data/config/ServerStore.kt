package com.m57.hermescontrol.data.config

import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.m57.hermescontrol.data.remote.ServerEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

class ServerStore(
    private val dataStore: DataStore<ServerStoreState>,
    private val scope: CoroutineScope,
) {
    private val _stateFlow: MutableStateFlow<ServerStoreState>
    val stateFlow: StateFlow<ServerStoreState>

    init {
        val initial =
            runBlocking(Dispatchers.IO) {
                try {
                    dataStore.data.first().selfHealed()
                } catch (e: Exception) {
                    ServerStoreSerializer.defaultValue
                }
            }
        _stateFlow = MutableStateFlow(initial)
        stateFlow = _stateFlow.asStateFlow()
    }

    fun getLatestState(): ServerStoreState = _stateFlow.value

    fun update(transform: (ServerStoreState) -> ServerStoreState) {
        val current = getLatestState()
        val updated = transform(current).selfHealed()
        _stateFlow.value = updated
        scope.launch(Dispatchers.IO) {
            try {
                dataStore.updateData { updated }
            } catch (e: Exception) {
                // Fail-safe
            }
        }
    }
}

object ServerStoreSerializer : Serializer<ServerStoreState> {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    override val defaultValue: ServerStoreState =
        ServerStoreState(baseUrl = ServerEndpoint.DEFAULT_BASE_URL)

    override suspend fun readFrom(input: InputStream): ServerStoreState =
        try {
            json.decodeFromString(
                ServerStoreState.serializer(),
                input.readBytes().decodeToString(),
            )
        } catch (e: Exception) {
            defaultValue
        }

    override suspend fun writeTo(
        t: ServerStoreState,
        output: OutputStream,
    ) {
        val serialized = json.encodeToString(ServerStoreState.serializer(), t)
        output.write(serialized.toByteArray())
    }
}
