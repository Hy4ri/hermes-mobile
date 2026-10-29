package com.m57.hermescontrol.data.session

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SessionRenameRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Writes the shared read watermark (`PATCH /api/sessions/{id}` with `{"unread": false}`, the same call
 * desktop uses) and keeps an optimistic in-memory copy so the History list clears a Done badge at once.
 *
 * A failed PATCH is logged only; the next list refresh shows the server's truth.
 */
class SessionReadMarker(
    private val api: () -> HermesApiService,
    private val profileId: () -> String,
    private val nowSeconds: () -> Double,
    private val scope: CoroutineScope,
    private val logFailure: (String, Throwable?) -> Unit,
) {
    private val _localReads = MutableStateFlow<Map<String, Double>>(emptyMap())

    /** Session id → epoch seconds of the last read made on this phone. */
    val localReads: StateFlow<Map<String, Double>> = _localReads.asStateFlow()

    /** Marks [sessionId] read now. Ignores a null or blank id. */
    fun markRead(sessionId: String?) {
        val id = sessionId?.takeIf { it.isNotBlank() } ?: return
        _localReads.update { it + (id to nowSeconds()) }
        scope.launch {
            try {
                val client = api()
                val body = fenceSessionPatch(client, id, SessionRenameRequest(unread = false, profile = profileId()))
                val result = safeApiCall { client.setSessionRead(id, body) }
                if (result is NetworkResult.Failure) {
                    logFailure(
                        "Mark read failed for $id: ${result.error.message}",
                        null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("Mark read failed for $id", e)
            }
        }
    }

    /** Chat opened [sessionId]: marks it read now and returns the action to run when the user leaves it. */
    fun chatOpened(sessionId: String?): () -> Unit {
        markRead(sessionId)
        return { markRead(sessionId) }
    }

    companion object {
        private const val TAG = "SessionReadMarker"

        /** App-wide instance used by Chat and History. */
        val shared: SessionReadMarker by lazy {
            SessionReadMarker(
                api = { ApiClient.hermesApi },
                profileId = { AuthManager.destructiveProfileId() },
                nowSeconds = { System.currentTimeMillis() / 1000.0 },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                logFailure = { message, error -> Log.w(TAG, message, error) },
            )
        }
    }
}
