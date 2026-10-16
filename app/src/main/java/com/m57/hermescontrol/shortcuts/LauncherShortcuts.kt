package com.m57.hermescontrol.shortcuts

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.ws.ChangeEventHub
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

internal data class LauncherRequest(
    val action: String,
    val scopeId: String? = null,
    val sessionId: String? = null,
)

/** Process-owned publishing; no screen/ViewModel must be kept alive to retire private titles. */
internal object LauncherShortcuts {
    const val ACTION_NEW_CHAT = "com.m57.hermescontrol.shortcut.NEW_CHAT"
    const val ACTION_HISTORY = "com.m57.hermescontrol.shortcut.HISTORY"
    const val ACTION_SESSION = "com.m57.hermescontrol.shortcut.SESSION"
    const val EXTRA_SCOPE = "shortcut_scope"
    const val EXTRA_SESSION = "shortcut_session"
    private const val TAG = "LauncherShortcuts"
    private const val RECENT_WINDOW = 50

    var pendingRequest: LauncherRequest? by mutableStateOf(null)
        private set
    private var coordinator: SessionShortcutCoordinator? = null

    fun initialize(
        context: Context,
        appScope: CoroutineScope,
    ) {
        val publisher = SessionShortcutPublisher(context.applicationContext)
        appScope.launch(Dispatchers.Main.immediate) {
            protect { publisher.clear() }
            AuthManager.initializationState.first { it == AuthManager.InitializationState.Ready }
            val controller =
                SessionShortcutCoordinator(
                    currentScope = ::authenticatedScope,
                    loadSessions = { owner ->
                        val endpoint = ServerEndpoint.parseForBuild(owner.baseUrl).baseUrl
                        val token = if (AuthManager.isGatedMode()) null else AuthManager.getToken()
                        val cookies =
                            if (CookieManager.isInitialized()) {
                                CookieManager.cookieJar.loadForRequest(requireNotNull(endpoint.resolve("api/sessions")))
                            } else {
                                emptyList()
                            }
                        check(authenticatedScope() == owner) { "Shortcut owner changed" }
                        val service = ApiClient.createScopedService(owner, token, cookies)
                        // The backend backfills pins into page one; otherwise consider the newest 50 only.
                        val response =
                            service.getSessions(
                                limit = RECENT_WINDOW,
                                order = "recent",
                                excludeSources = "cron,bot_room,subagent",
                                archived = "exclude",
                            )
                        if (!response.isSuccessful) throw java.io.IOException("Shortcut metadata unavailable")
                        response.body()?.sessions ?: throw java.io.IOException("Missing shortcut metadata")
                    },
                    publish = publisher::publish,
                    clear = publisher::clear,
                )
            coordinator = controller
            combine(
                AuthManager.dataScopeFlow,
                AuthManager.tokenFlow,
                HermesWsClient.connectionStatus,
            ) { _, _, status -> authenticatedScope() to status }
                .collectLatest { (owner, status) ->
                    protect { controller.updateOwner(owner) }
                    if (owner == null) return@collectLatest
                    merge(
                        ShortcutRefreshEvents.events,
                        ChangeEventHub.events.filter { it.type == ChangeEvents.SESSIONS }.map { Unit },
                    ).onStart { emit(Unit) }
                        .collectLatest {
                            protect {
                                // Changed metadata is untrusted until refetched; do not retain hidden/deleted titles.
                                publisher.clear()
                                if (status == ConnectionStatus.CONNECTED) controller.refresh(owner)
                            }
                        }
                }
        }
    }

    fun accept(intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in setOf(ACTION_NEW_CHAT, ACTION_HISTORY, ACTION_SESSION)) return
        pendingRequest =
            LauncherRequest(
                action = action,
                scopeId = intent.getStringExtra(EXTRA_SCOPE),
                sessionId = intent.getStringExtra(EXTRA_SESSION),
            )
        // Consumed on cold and warm starts, including activity recreation while auth is loading.
        intent.action = Intent.ACTION_MAIN
        intent.removeExtra(EXTRA_SCOPE)
        intent.removeExtra(EXTRA_SESSION)
    }

    fun consume(request: LauncherRequest) {
        if (pendingRequest == request) pendingRequest = null
    }

    fun authenticatedScope(): DataScope? =
        if (AuthManager.initializationState.value == AuthManager.InitializationState.Ready &&
            !AuthManager.getToken().isNullOrBlank()
        ) {
            AuthManager.currentDataScope()
        } else {
            null
        }

    suspend fun resolve(
        request: LauncherRequest,
        scope: DataScope,
    ): Boolean {
        val scopeId = request.scopeId ?: return false
        val sessionId = request.sessionId?.takeIf { it.isNotBlank() } ?: return false
        return try {
            coordinator?.resolve(scope, scopeId, sessionId) == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun protect(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Launcher/OEM failures must not crash login or navigation; never log private titles/URLs.
            Log.w(TAG, "Could not refresh launcher shortcuts")
        }
    }
}
