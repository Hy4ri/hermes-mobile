package com.m57.hermescontrol.ui.analytics

import android.content.Context
import com.m57.hermescontrol.data.local.AnalyticsCacheStore
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App-launch analytics preloader (issue #537 follow-up, part A).
 *
 * The analytics tab's slowness is server-side: `/api/analytics/usage` runs the
 * insights engine over the full message history and can take tens of seconds on a
 * cold backend. The tab only fetches when the user *taps* it, so they eat that
 * whole wait. This fires the fetches in the background right after login, so by
 * the time the user opens Analytics the data is already in the cache and renders
 * instantly. Writes through to [AnalyticsCacheStore] so a cold app launch also
 * benefits (see part C).
 *
 * Fire-and-forget: never blocks the UI, swallows all errors (a preload miss is
 * invisible — the tab just falls back to its normal on-demand load).
 */
object AnalyticsPreloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun preload(context: Context) {
        if (AuthManager.getToken().isNullOrBlank()) return
        val store = AnalyticsCacheStore(context)
        val daysOptions = listOf(7, 30, 90)
        scope.launch {
            for (days in daysOptions) {
                // Skip windows already cached on disk — don't refetch what we have.
                if (store.load(days) != null) continue
                val usage =
                    safeApiCall<com.m57.hermescontrol.data.model.AnalyticsResponse> {
                        ApiClient.hermesApi.getAnalytics(days, null)
                    }
                val models =
                    safeApiCall<com.m57.hermescontrol.data.model.ModelsAnalyticsResponse> {
                        ApiClient.hermesApi.getModelsAnalytics(days, null)
                    }
                val usageData = (usage as? NetworkResult.Success)?.data ?: continue
                val modelsData = (models as? NetworkResult.Success)?.data
                store.save(days, usageData, modelsData)
            }
        }
    }
}
