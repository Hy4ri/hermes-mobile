package com.m57.hermescontrol.data.config

import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * Persists per-app model-control preferences that should survive a cold start
 * (e.g. the last reasoning effort the user picked, so the composer doesn't
 * reset to "medium" every launch). Mirrors the [ServerStore] DataStore pattern.
 */
class ModelPrefsStore(
    private val dataStore: DataStore<ModelPrefsState>,
    private val scope: CoroutineScope,
) {
    private val _stateFlow: MutableStateFlow<ModelPrefsState>
    val stateFlow: StateFlow<ModelPrefsState>
    val state: Flow<ModelPrefsState>

    init {
        val initial =
            runBlocking(Dispatchers.IO) {
                try {
                    dataStore.data.first().selfHealed()
                } catch (e: Exception) {
                    ModelPrefsState()
                }
            }
        _stateFlow = MutableStateFlow(initial)
        stateFlow = _stateFlow.asStateFlow()
        state = stateFlow
    }

    fun getLatestState(): ModelPrefsState = _stateFlow.value

    fun update(transform: (ModelPrefsState) -> ModelPrefsState) {
        val current = getLatestState()
        val updated = transform(current).selfHealed()
        _stateFlow.value = updated
        scope.launch(Dispatchers.IO) {
            try {
                dataStore.updateData { updated }
            } catch (e: Exception) {
                // Fail-safe: keep in-memory value even if disk write fails.
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class ModelPrefsState(
    /** Last reasoning effort picked in the chat composer (minimal..max). */
    val lastReasoningEffort: String = "medium",
) {
    fun selfHealed(): ModelPrefsState {
        val valid =
            lastReasoningEffort in
                setOf("minimal", "low", "medium", "high", "xhigh", "max", "none")
        return if (valid) this else copy(lastReasoningEffort = "medium")
    }
}

object ModelPrefsSerializer : Serializer<ModelPrefsState> {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    override val defaultValue: ModelPrefsState = ModelPrefsState()

    override suspend fun readFrom(input: InputStream): ModelPrefsState =
        try {
            json.decodeFromString(
                ModelPrefsState.serializer(),
                input.readBytes().decodeToString(),
            )
        } catch (e: Exception) {
            defaultValue
        }

    override suspend fun writeTo(
        t: ModelPrefsState,
        output: OutputStream,
    ) {
        val serialized = json.encodeToString(ModelPrefsState.serializer(), t)
        output.write(serialized.toByteArray())
    }
}
