package com.m57.hermescontrol.data.config

import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.ServerEndpoint
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Legacy fields retained only so pre-base-URL profiles can migrate. */
    val host: String = "127.0.0.1",
    val port: Int = 9119,
    val baseUrl: String? = null,
) {
    /**
     * Resolves this profile's own base URL, falling back to the legacy loopback
     * default when [baseUrl] was never stamped. Prefer [resolveBaseUrl] at the
     * store/UI level so a real gateway URL used to log in takes precedence over
     * the hardcoded loopback (issue #647).
     */
    val resolvedBaseUrl: String
        get() = resolveBaseUrl(null)
}

/**
 * Resolves the base URL actually used to talk to this profile's server.
 *
 * Prefers the profile's own [ConnectionProfile.baseUrl]; if that was never
 * stamped (legacy or a freshly-seeded Default profile) it falls back to
 * [topLevelBaseUrl] — the URL the user last authenticated against — and only
 * then to the legacy loopback default. This prevents the connection-profile
 * list from showing the hardcoded `127.0.0.1:9119` when a real gateway URL was
 * used to log in (issue #647).
 */
fun ConnectionProfile.resolveBaseUrl(topLevelBaseUrl: String?): String {
    baseUrl
        ?.let {
            return ServerEndpoint
                .parse(
                    it,
                    CleartextPolicy.ALLOW_WITH_WARNING,
                ).baseUrl
                .toString()
        }
    return topLevelBaseUrl
        ?.let {
            ServerEndpoint
                .parse(
                    it,
                    CleartextPolicy.ALLOW_WITH_WARNING,
                ).baseUrl
                .toString()
        }
        ?: ServerEndpoint.fromLegacy(host, port).baseUrl.toString()
}
