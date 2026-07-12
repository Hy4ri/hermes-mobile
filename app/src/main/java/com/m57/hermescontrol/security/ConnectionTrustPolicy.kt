package com.m57.hermescontrol.security

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

object ConnectionTrustPolicy {
    fun allowsCleartextTo(rawHost: String): Boolean {
        val host =
            rawHost
                .trim()
                .removePrefix("[")
                .removeSuffix("]")
                .substringBefore('%')
                .lowercase()
        if (host == "localhost" || host == "::1") return true
        if (host.endsWith(".ts.net")) return true
        if (
            host.contains(':') &&
            (host.startsWith("fd7a:115c:a1e0:") || host.startsWith("fc") || host.startsWith("fd"))
        ) {
            return true
        }

        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val first = octets[0]
        val second = octets[1]
        return first == 10 ||
            first == 127 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 100 && second in 64..127)
    }
}

class PrivateNetworkOnlyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (!url.isHttps && !ConnectionTrustPolicy.allowsCleartextTo(url.host)) {
            throw IOException(
                "Cassy blocks cleartext traffic to public destination ${url.host}. Use HTTPS or Tailscale/private LAN.",
            )
        }
        return chain.proceed(request)
    }
}
