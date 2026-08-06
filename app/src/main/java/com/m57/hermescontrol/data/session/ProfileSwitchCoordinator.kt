package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SetActiveProfileRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.HermesWsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * The single flow that performs a profile switch — the mobile equivalent of
 * desktop's re-home (``requestFreshSession`` + socket swap). Every surface
 * that switches profiles goes through here, so the switch is atomic instead
 * of a pile of scattered patches.
 *
 * Order matters:
 *  1. Flip the server's sticky active profile (REST).
 *  2. Persist the LOCAL selection — the REST interceptor (``?profile=``) and
 *     the WS params injector (``params.profile``) now scope everything to the
 *     new profile. The per-server token fallback (phase 1) keeps auth intact:
 *     no re-login, restart-safe.
 *  3. Emit [switched] BEFORE the socket re-dial, so chat wipes its stale
 *     conversation first — when the reconnected socket delivers
 *     ``gateway.ready``, ``handleGatewayReady`` sees no open session and
 *     auto-creates a FRESH session in the new profile (desktop parity).
 *  4. Re-dial the WebSocket so the gateway re-homes chat to the new profile.
 */
object ProfileSwitchCoordinator {
    private val _switched = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val switched: SharedFlow<String> = _switched.asSharedFlow()

    suspend fun switchProfile(name: String): NetworkResult<Unit> {
        val result =
            withContext(Dispatchers.IO) {
                safeApiCall { ApiClient.hermesApi.setActiveProfile(SetActiveProfileRequest(name)) }
            }
        if (result !is NetworkResult.Success) return result

        AuthManager.setActiveProfileId(name)
        _switched.emit(name)
        // The ticket mint inside connect() does blocking network I/O — it must
        // run off the main thread or the dial crashes with
        // NetworkOnMainThreadException and falls back to the 1s reconnect
        // retry (visible in the 2026-08-06 live logcat).
        withContext(Dispatchers.IO) {
            HermesWsClient.disconnect()
            HermesWsClient.connect()
        }
        return result
    }
}
