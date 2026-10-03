package com.m57.hermescontrol.data.remote

import okhttp3.HttpUrl

/** TLS credentials belong to a host and port, never to a profile or URL path. */
internal data class CertificateOrigin(
    val host: String,
    val port: Int,
) {
    val url: HttpUrl =
        HttpUrl
            .Builder()
            .scheme("https")
            .host(host)
            .port(port)
            .build()
    val storageKey: String get() = url.toString()

    companion object {
        fun parse(
            host: String,
            port: String,
        ): CertificateOrigin {
            val value = port.toIntOrNull()
            require(value != null && value in 1..65535) { "Port must be between 1 and 65535" }
            val raw = host.trim().removePrefix("[").removeSuffix("]")
            require(
                raw.isNotEmpty() && raw.none { it.isWhitespace() || it in "/?#@" },
            ) { "Enter a hostname or IP address" }
            val url =
                HttpUrl
                    .Builder()
                    .scheme("https")
                    .host(raw)
                    .port(value)
                    .build()
            return CertificateOrigin(url.host, url.port)
        }

        fun from(url: HttpUrl): CertificateOrigin? = if (url.isHttps) CertificateOrigin(url.host, url.port) else null
    }
}
