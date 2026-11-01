package com.m57.hermescontrol.data.tunnel

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Which SSH credential the tunnel uses. */
@Serializable
enum class SshAuthMode {
    PASSWORD,
    PRIVATE_KEY,
}

/** Field that keeps a stored tunnel from being used. */
enum class SshTunnelProblem {
    SSH_HOST,
    SSH_PORT,
    USERNAME,
    PASSWORD,
    PRIVATE_KEY,
    REMOTE_HOST,
    REMOTE_PORT,
}

/**
 * One SSH local forward, keyed by the dashboard origin it reaches.
 *
 * The dashboard URL keeps the host and port it would have on the SSH server
 * itself (typically `http://127.0.0.1:9119/`), so cookies, TLS bindings,
 * custom headers and the WebSocket URL never change when a tunnel is enabled.
 * Only the TCP socket is re-routed: [host] is the SSH server that is dialed,
 * and [remoteHost]:[remotePort] is the dashboard address as seen from it.
 *
 * Credentials live in the same encrypted preferences file as the dashboard
 * token, never in `server_store.json`.
 */
@Serializable
data class SshTunnelConfig(
    val enabled: Boolean = true,
    val host: String = "",
    val port: Int = DEFAULT_SSH_PORT,
    val username: String = "",
    val authMode: SshAuthMode = SshAuthMode.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val remoteHost: String = DEFAULT_REMOTE_HOST,
    val remotePort: Int = DEFAULT_REMOTE_PORT,
) {
    /** Null when every required field is filled; the first bad field otherwise. */
    fun problem(): SshTunnelProblem? =
        when {
            host.isBlank() || host.contains(' ') -> SshTunnelProblem.SSH_HOST
            port !in 1..65535 -> SshTunnelProblem.SSH_PORT
            username.isBlank() || username.contains(' ') -> SshTunnelProblem.USERNAME
            authMode == SshAuthMode.PASSWORD && password.isEmpty() -> SshTunnelProblem.PASSWORD
            authMode == SshAuthMode.PRIVATE_KEY && privateKey.isBlank() -> SshTunnelProblem.PRIVATE_KEY
            remoteHost.isBlank() || remoteHost.contains(' ') -> SshTunnelProblem.REMOTE_HOST
            remotePort !in 1..65535 -> SshTunnelProblem.REMOTE_PORT
            else -> null
        }

    /**
     * Identity of a usable tunnel. A running tunnel whose [changeToken] still
     * matches does not need to be restarted when the app re-syncs.
     */
    fun changeToken(): String =
        listOf(
            enabled,
            host,
            port,
            username,
            authMode,
            password.hashCode(),
            privateKey.hashCode(),
            passphrase.hashCode(),
            remoteHost,
            remotePort,
        ).joinToString("|")

    /** Copy without the credentials, for logs and diagnostics. */
    override fun toString(): String =
        "SshTunnelConfig(host=$host, port=$port, remoteHost=$remoteHost, remotePort=$remotePort)"

    companion object {
        const val DEFAULT_SSH_PORT = 22
        const val DEFAULT_REMOTE_HOST = "127.0.0.1"
        const val DEFAULT_REMOTE_PORT = 9119
    }
}

/**
 * Stable key for a dashboard origin (`scheme://host:port`). Path prefixes,
 * queries and case differences never create a second tunnel.
 */
fun tunnelOrigin(url: HttpUrl): String {
    val host = if (':' in url.host) "[${url.host}]" else url.host
    return "${url.scheme}://$host:${url.port}"
}

/** [tunnelOrigin] for a raw URL string; null when it is not an http(s) URL. */
fun tunnelOriginOf(rawUrl: String?): String? {
    val parsed = rawUrl?.trim()?.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "http" && parsed.scheme != "https") return null
    return tunnelOrigin(parsed)
}
