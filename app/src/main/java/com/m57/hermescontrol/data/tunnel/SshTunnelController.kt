package com.m57.hermescontrol.data.tunnel

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Connects the stored per-origin SSH tunnel settings to the running tunnel.
 *
 * Every place that changes the dashboard the app is talking to (app start,
 * connection-profile switch, sign-in URL, logout) calls [applyForBaseUrl]; a
 * matching, healthy tunnel is left alone, so this is cheap to call often.
 */
object SshTunnelController {
    private const val SETTLE_TIMEOUT_MS = 20_000L

    val state: StateFlow<SshTunnelState> get() = SshTunnelManager.state

    fun initialize(context: Context) {
        SshTunnelManager.initialize(context)
    }

    /** The tunnel settings that apply to [baseUrl], when they are usable. */
    fun configFor(baseUrl: String?): SshTunnelConfig? {
        val origin = originOf(baseUrl) ?: return null
        val config = SshTunnelStore.get(origin) ?: return null
        return config.takeIf { it.enabled && it.problem() == null }
    }

    /** Starts, restarts or stops the tunnel so it matches [baseUrl]. */
    fun applyForBaseUrl(baseUrl: String?) {
        val parsed = baseUrl?.trim()?.toHttpUrlOrNull()
        if (parsed == null) {
            SshTunnelManager.apply(null)
            return
        }
        val origin = tunnelOrigin(parsed)
        val config = configFor(baseUrl)
        if (config == null) {
            SshTunnelManager.apply(null)
            return
        }
        SshTunnelManager.apply(SshTunnelManager.Target(origin, parsed.host, parsed.port, config))
    }

    /**
     * [applyForBaseUrl], then waits for the first settled state so a caller can
     * probe the dashboard in the same breath (a tunnel that is still dialing
     * would just refuse the connection).
     */
    suspend fun prepareForBaseUrl(
        baseUrl: String?,
        timeoutMs: Long = SETTLE_TIMEOUT_MS,
    ): SshTunnelState {
        applyForBaseUrl(baseUrl)
        val current = state.value
        if (current is SshTunnelState.Connected || current is SshTunnelState.Disabled) return current
        return withTimeoutOrNull(timeoutMs) {
            state.first { it !is SshTunnelState.Connecting }
        } ?: state.value
    }

    fun stop() {
        SshTunnelManager.apply(null)
    }

    fun acceptPendingHostKey() {
        SshTunnelManager.acceptPendingHostKey()
    }

    fun forgetHostKey(host: String) {
        SshTunnelManager.forgetHostKey(host)
    }

    private fun originOf(baseUrl: String?): String? = tunnelOriginOf(baseUrl)
}
