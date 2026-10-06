package com.m57.hermescontrol.data.remote

import okhttp3.HttpUrl
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException

// BoringSSL's received-alert reasons, rather than local server-certificate verification failures.
// See ssl_process_alert in https://boringssl.googlesource.com/boringssl/+/HEAD/ssl/tls_record.cc.
private val clientCertificateAlert =
    Regex(
        "(?<![A-Za-z0-9_])(?:" +
            listOf(
                "TLSV1_ALERT_CERTIFICATE_REQUIRED",
                "SSLV3_ALERT_BAD_CERTIFICATE",
                "SSLV3_ALERT_UNSUPPORTED_CERTIFICATE",
                "SSLV3_ALERT_CERTIFICATE_REVOKED",
                "SSLV3_ALERT_CERTIFICATE_EXPIRED",
                "SSLV3_ALERT_CERTIFICATE_UNKNOWN",
                "TLSV1_ALERT_UNKNOWN_CA",
            ).joinToString("|") +
            ")(?![A-Za-z0-9_])",
    )

/** Deliberately narrow Conscrypt alert allowlist; other TLS failures retain the manual configuration path. */
internal fun isClientCertificateAuthenticationFailure(
    url: HttpUrl,
    failure: Throwable,
): Boolean {
    if (!url.isHttps) return false
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var cause: Throwable? = failure
    while (cause != null && seen.add(cause)) {
        if (cause is SSLException && clientCertificateAlert.containsMatchIn(cause.message.orEmpty())) return true
        cause = cause.cause
    }
    return false
}
