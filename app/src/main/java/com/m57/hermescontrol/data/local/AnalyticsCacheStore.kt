package com.m57.hermescontrol.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.m57.hermescontrol.data.model.AnalyticsResponse
import com.m57.hermescontrol.data.model.ModelsAnalyticsResponse
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
 * Keyed by trailing window (`days`) AND profile id, so switching profiles
 * never leaks one profile's data into another (issue #537 follow-up).
 */
@Serializable
private data class CachedWindow(
    val usageJson: String = "",
    val modelsJson: String = "",
)

@Serializable
private data class AnalyticsCacheState(
    // Map key = "$days:$profile" so multi-profile installs stay isolated.
    val windows: Map<String, CachedWindow> = emptyMap(),
)

private object AnalyticsCacheSerializer : Serializer<AnalyticsCacheState> {
    override val defaultValue: AnalyticsCacheState = AnalyticsCacheState()

    override suspend fun readFrom(input: InputStream): AnalyticsCacheState =
        try {
            // Use the app's wire Json (ignoreUnknownKeys=true) so a backend
            // adding a field can't break our on-disk cache decode.
            OkHttpProvider.json.decodeFromString(
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
        output.write(
            OkHttpProvider.json
                .encodeToString(AnalyticsCacheState.serializer(), t)
                .toByteArray(),
        )
    }
}

private val Context.analyticsCacheDataStore: DataStore<AnalyticsCacheState> by dataStore(
    fileName = "analytics_cache.json",
    serializer = AnalyticsCacheSerializer,
)

class AnalyticsCacheStore(
    private val context: Context,
) {
    /** Stable cache key covering both window and profile. */
    private fun key(
        days: Int,
        profile: String?,
    ): String = "$days:${profile ?: "default"}"

    /** Load a previously cached window, or null if absent/corrupt. */
    suspend fun load(
        days: Int,
        profile: String?,
    ): Pair<AnalyticsResponse, ModelsAnalyticsResponse?>? =
        withContext(Dispatchers.IO) {
            try {
                val win =
                    context.analyticsCacheDataStore.data
                        .first()
                        .windows[key(days, profile)] ?: return@withContext null
                val usage = OkHttpProvider.json.decodeFromString<AnalyticsResponse>(win.usageJson)
                val models =
                    win.modelsJson
                        .takeIf { it.isNotBlank() }
                        ?.let { OkHttpProvider.json.decodeFromString<ModelsAnalyticsResponse>(it) }
                usage to models
            } catch (e: Exception) {
                null
            }
        }

    /** Persist a successful window response. Best-effort; never throws. */
    suspend fun save(
        days: Int,
        profile: String?,
        usage: AnalyticsResponse,
        models: ModelsAnalyticsResponse?,
    ) = withContext(Dispatchers.IO) {
        try {
            context.analyticsCacheDataStore.updateData { state ->
                val win =
                    CachedWindow(
                        usageJson = OkHttpProvider.json.encodeToString(usage),
                        modelsJson = models?.let { OkHttpProvider.json.encodeToString(it) } ?: "",
                    )
                state.copy(windows = state.windows + (key(days, profile) to win))
            }
        } catch (e: Exception) {
            // Fail-safe: a cache miss is preferable to a crash on launch.
        }
    }
}
