package com.m57.hermescontrol.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.m57.hermescontrol.data.model.AnalyticsResponse
import com.m57.hermescontrol.data.model.ModelsAnalyticsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * Disk-backed cache for analytics responses (issue #537 follow-up).
 *
 * The `/api/analytics/usage` endpoint is expensive on the backend (it runs the
 * insights engine over the full message history), so a cold load can take tens of
 * seconds. The ViewModel already keeps an in-memory cache, but that dies when the
 * app is killed. Persisting the last successful response to disk means even a cold
 * app launch can render *something* instantly and refresh in the background —
 * the user never stares at a spinner for a query we already answered recently.
 *
 * Keyed by trailing window (`days`) so the 7/30/90 tabs each keep their own
 * last-known-good payload.
 */
@Serializable
private data class CachedWindow(
    val usageJson: String = "",
    val modelsJson: String = "",
)

@Serializable
private data class AnalyticsCacheState(
    // Map key = days (as string, since @Serializable maps need string keys here).
    val windows: Map<String, CachedWindow> = emptyMap(),
)

private object AnalyticsCacheSerializer : Serializer<AnalyticsCacheState> {
    override val defaultValue: AnalyticsCacheState = AnalyticsCacheState()

    override suspend fun readFrom(input: InputStream): AnalyticsCacheState =
        try {
            Json.decodeFromString(
                AnalyticsCacheState.serializer(),
                input.readBytes().decodeToString(),
            )
        } catch (e: Exception) {
            defaultValue
        }

    override suspend fun writeTo(
        t: AnalyticsCacheState,
        output: OutputStream,
    ) {
        output.write(Json.encodeToString(AnalyticsCacheState.serializer(), t).toByteArray())
    }
}

private val Context.analyticsCacheDataStore: DataStore<AnalyticsCacheState> by dataStore(
    fileName = "analytics_cache.json",
    serializer = AnalyticsCacheSerializer,
)

class AnalyticsCacheStore(
    private val context: Context,
) {
    /** Load a previously cached window, or null if absent/corrupt. */
    fun load(days: Int): Pair<AnalyticsResponse, ModelsAnalyticsResponse?>? =
        runBlocking(Dispatchers.IO) {
            try {
                val win =
                    context.analyticsCacheDataStore.data
                        .first()
                        .windows[days.toString()] ?: return@runBlocking null
                val usage = Json.decodeFromString<AnalyticsResponse>(win.usageJson)
                val models =
                    win.modelsJson
                        .takeIf { it.isNotBlank() }
                        ?.let { Json.decodeFromString<ModelsAnalyticsResponse>(it) }
                usage to models
            } catch (e: Exception) {
                null
            }
        }

    /** Persist a successful window response. Best-effort; never throws. */
    suspend fun save(
        days: Int,
        usage: AnalyticsResponse,
        models: ModelsAnalyticsResponse?,
    ) = withContext(Dispatchers.IO) {
        try {
            context.analyticsCacheDataStore.updateData { state ->
                val win =
                    CachedWindow(
                        usageJson = Json.encodeToString(usage),
                        modelsJson = models?.let { Json.encodeToString(it) } ?: "",
                    )
                state.copy(windows = state.windows + (days.toString() to win))
            }
        } catch (e: Exception) {
            // Fail-safe: a cache miss is preferable to a crash on launch.
        }
    }
}
