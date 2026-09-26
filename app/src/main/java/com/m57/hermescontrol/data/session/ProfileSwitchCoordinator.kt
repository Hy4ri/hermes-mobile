package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SetActiveProfileRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.HermesWsClient
import kotlinx.coroutines.CoroutineDispatcher
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

/**
 * Canonical-session intent that survives the socket re-dial.
 *
 * Scoped to target profile and switch generation so that [handleGatewayReady]
 * in [ChatViewModel] can verify it belongs to the current connection before
 * consuming it. Prevents cross-profile consumption and stale-intent races
 * from overlapping switches.
 */
data class CanonicalSessionIntent(
    val sessionId: String,
    val profileName: String,
    val generation: Long,
)

object ProfileSwitchCoordinator {
    /**
     * Dispatcher for the blocking network hops below.
     *
     * Injectable so tests can drive the whole switch on a TestDispatcher. With the
     * real Dispatchers.IO these paths hop to a live thread pool and the ORDER of
     * the mocked calls becomes load-dependent -- ProfileSwitchCoordinatorTest's
     * Ordering.SEQUENCE checks passed on an idle machine but lost 2 tests while the
     * emulator saturated the CPU, and its setMain-less sibling tests failed
     * outright whenever another class had left Dispatchers.Main broken.
     */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _switched = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val switched: SharedFlow<String> = _switched.asSharedFlow()

    private val _connectionSwitched = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val connectionSwitched: SharedFlow<String> = _connectionSwitched.asSharedFlow()

    // ── Canonical-session intent (profile- and generation-scoped) ─────

    @Volatile
    private var pendingCanonicalIntent: CanonicalSessionIntent? = null
    private var nextSwitchGeneration = 0L

    /**
     * Set a canonical-session intent scoped to [profileName]. Returns the
     * generation token that must match on consumption — older intents are
     * stale and ignored.
     *
     * Call BEFORE [switchProfile] so the intent is armed in time for a fast
     * gateway.ready. Clear the intent with [clearCanonicalIntent] if the
     * switch fails or is cancelled.
     */
    fun setCanonicalIntent(sessionId: String, profileName: String): Long {
        val generation = ++nextSwitchGeneration
        pendingCanonicalIntent = CanonicalSessionIntent(sessionId, profileName, generation)
        return generation
    }

    /**
     * Consume and return the intent's sessionId only when [profileName] and
     * [generation] match the pending intent. Returns null on mismatch or when
     * no intent is set.
     */
    fun consumeCanonicalIntent(profileName: String, generation: Long): String? {
        val intent = pendingCanonicalIntent
        if (intent != null && intent.profileName == profileName && intent.generation == generation) {
            pendingCanonicalIntent = null
            return intent.sessionId
        }
        return null
    }

    /** Atomically clear any pending canonical intent. */
    fun clearCanonicalIntent() {
        pendingCanonicalIntent = null
    }

    /**
     * The generation of the most recent [setCanonicalIntent] call. Zero when
     * no intent has ever been set. Consumers pass this to [consumeCanonicalIntent]
     * so that only the latest intent can be consumed.
     */
    val canonicalIntentGeneration: Long get() = nextSwitchGeneration

    // ── Profile switch operations ────────────────────────────────────

    /**
     * Restore the server-side Hermes profile scope on a cold/fresh app start.
     *
     * A fresh install has no encrypted active_profile_id yet. If chat opens
     * before the user manually visits Profiles, WS session.create/resume omit
     * params.profile and the multiplex gateway falls back to its launch profile
     * (normally default). The server already persists the operator's active
     * Hermes profile, so use that as bootstrap authority only when local scope
     * is missing.
     *
     * A profile selected while the request is in flight wins: re-check local
     * state before writing so startup never clobbers an explicit user switch.
     */
    suspend fun restoreActiveProfileScopeIfMissing(): String? {
        AuthManager.activeProfileId.value?.takeIf { it.isNotBlank() }?.let { return it }

        val result =
            withContext(ioDispatcher) {
                safeApiCall { ApiClient.hermesApi.getActiveProfile() }
            }
        val serverProfile =
            (result as? NetworkResult.Success)
                ?.data
                ?.active
                ?.takeIf { it.isNotBlank() }

        if (serverProfile != null && AuthManager.activeProfileId.value.isNullOrBlank()) {
            AuthManager.setActiveProfileId(serverProfile)
        }
        return AuthManager.activeProfileId.value
    }

    suspend fun switchProfile(name: String): NetworkResult<Unit> {
        val result =
            withContext(ioDispatcher) {
                safeApiCall { ApiClient.hermesApi.setActiveProfile(SetActiveProfileRequest(name)) }
            }
        if (result !is NetworkResult.Success) {
            // Clear pending canonical intent on REST failure so a stale
            // gateway.ready cannot consume a session for the wrong profile.
            clearCanonicalIntent()
            return result
        }

        AuthManager.setActiveProfileId(name)
        _switched.emit(name)
        // The ticket mint inside connect() does blocking network I/O — it must
        // run off the main thread or the dial crashes with
        // NetworkOnMainThreadException and falls back to the 1s reconnect
        // retry (visible in the 2026-08-06 live logcat).
        withContext(ioDispatcher) {
            HermesWsClient.disconnect()
            HermesWsClient.connect()
        }
        return result
    }

    /**
     * Switch the active server connection profile (not just the bot profile).
     *
     * The server connection profile determines which Hermes server the mobile
     * app talks to (e.g. a Tailscale endpoint vs the default LAN address).
     * Unlike [switchProfile] (which changes the active bot/hermes profile on
     * the same server), this repoints both Retrofit and the WebSocket to a
     * different server.
     *
     * Order matters:
     *  1. Persist the LOCAL selection — the token cache, cookie scope and
     *     [AuthManager.contextFlow] re-home to the new profile.
     *  2. Rebuild Retrofit so REST targets the new server.
     *  3. Emit [connectionSwitched] BEFORE the socket re-dial, so chat wipes
     *     its stale conversation first; the re-dialed socket then delivers
     *     gateway.ready → handleGatewayReady auto-creates a FRESH session on
     *     the new server (desktop requestFreshSession parity).
     *  4. Re-dial the WebSocket off the main thread (the ticket mint does
     *     blocking I/O — NetworkOnMainThreadException otherwise).
     */
    suspend fun switchConnectionProfile(profileId: String?) {
        AuthManager.setSelectedProfileId(profileId)
        ApiClient.rebuild()
        _connectionSwitched.emit(profileId.orEmpty())
        withContext(ioDispatcher) {
            // The WS ticket mint reads the cookie jar's ACTIVE store; the
            // selection change swaps that store asynchronously, so a dial
            // that races it mints with the PREVIOUS server's cookie → 401 →
            // aborted socket with no retry. Await the swap before dialing
            // (idempotent no-op when it already landed).
            AuthManager.syncCookieStoreForProfile(profileId)
            HermesWsClient.disconnect()
            HermesWsClient.connect()
        }
    }
}