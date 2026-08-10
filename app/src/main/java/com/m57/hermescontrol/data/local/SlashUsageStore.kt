package com.m57.hermescontrol.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/**
 * Disk-backed per-command usage counts for the slash-autocomplete ranking
 * (issue #865).
 *
 * The backend's `commands.catalog` carries no usage data — the usage-ranked
 * ordering exists only in the desktop/TUI completion path. Mobile keeps its
 * own local counts (keyed by command name, lowercase, WITH leading slash,
 * e.g. "/model") and ranks the suggestion menu by them.
 */
@Serializable
private data class SlashUsageState(
    // Command name (lowercase, WITH leading slash, e.g. "/model") → count.
    val counts: Map<String, Int> = emptyMap(),
)

private object SlashUsageSerializer : Serializer<SlashUsageState> {
    override val defaultValue: SlashUsageState = SlashUsageState()

    override suspend fun readFrom(input: InputStream): SlashUsageState =
        try {
            // Use the app's wire Json (ignoreUnknownKeys=true) so a future
            // schema addition can't break the on-disk decode.
            OkHttpProvider.json.decodeFromString(
                SlashUsageState.serializer(),
                input.readBytes().decodeToString(),
            )
        } catch (e: Exception) {
            defaultValue
        }

    override suspend fun writeTo(
        t: SlashUsageState,
        output: OutputStream,
    ) {
        output.write(
            OkHttpProvider.json
                .encodeToString(SlashUsageState.serializer(), t)
                .toByteArray(),
        )
    }
}

private val Context.slashUsageDataStore: DataStore<SlashUsageState> by dataStore(
    fileName = "slash_usage.json",
    serializer = SlashUsageSerializer,
)

/**
 * Stores how often each slash command was dispatched so the autocomplete can
 * surface most-used commands first. Best-effort: read/write failures degrade
 * to the empty map / a dropped write — never a crash.
 */
open class SlashUsageStore(
    private val context: Context,
) {
    /** Current usage counts; empty map when nothing is recorded yet. */
    open fun counts(): Flow<Map<String, Int>> =
        context.slashUsageDataStore.data
            .catch { emit(SlashUsageState()) }
            .map { it.counts }

    /** Bump the count for [command] (e.g. "/model"). Never throws. */
    open suspend fun recordUse(command: String) {
        withContext(Dispatchers.IO) {
            try {
                context.slashUsageDataStore.updateData { state ->
                    state.copy(
                        counts =
                            state.counts +
                                (command to ((state.counts[command] ?: 0) + 1)),
                    )
                }
            } catch (e: Exception) {
                // Best-effort: a failed write must never block the dispatch.
            }
        }
    }
}
