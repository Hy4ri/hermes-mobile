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
    val resolvedBaseUrl: String
        get() =
            baseUrl
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
