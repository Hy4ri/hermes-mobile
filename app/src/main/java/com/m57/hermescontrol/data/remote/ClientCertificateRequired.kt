package com.m57.hermescontrol.data.remote

import okhttp3.HttpUrl
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException

private val certificateRequiredAlert = Regex("(?<![A-Za-z0-9_])TLSV1_ALERT_CERTIFICATE_REQUIRED(?![A-Za-z0-9_])")

/** Deliberately narrow Conscrypt alert allowlist; other TLS failures retain the manual configuration path. */
internal fun isClientCertificateRequired(
    url: HttpUrl,
    failure: Throwable,
): Boolean {
    if (!url.isHttps) return false
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var cause: Throwable? = failure
    while (cause != null && seen.add(cause)) {
        if (cause is SSLException && certificateRequiredAlert.containsMatchIn(cause.message.orEmpty())) return true
        cause = cause.cause
    }
    return false
}
