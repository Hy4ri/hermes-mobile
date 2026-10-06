package com.m57.hermescontrol.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException

class ClientCertificateAuthenticationFailureTest {
    private val url = "https://example.test:8443/api/status".toHttpUrl()
    private val alerts =
        listOf(
            "TLSV1_ALERT_CERTIFICATE_REQUIRED",
            "SSLV3_ALERT_BAD_CERTIFICATE",
            "SSLV3_ALERT_UNSUPPORTED_CERTIFICATE",
            "SSLV3_ALERT_CERTIFICATE_REVOKED",
            "SSLV3_ALERT_CERTIFICATE_EXPIRED",
            "SSLV3_ALERT_CERTIFICATE_UNKNOWN",
            "TLSV1_ALERT_UNKNOWN_CA",
        )

    @Test
    fun `accepts the exact alert in SSL handshake or protocol exceptions and wrapped causes`() {
        alerts.forEach { reason ->
            val message = "error:10000000:SSL routines:OPENSSL_internal:$reason\nSSL alert number 42"
            listOf(SSLException(message), SSLHandshakeException(message), SSLProtocolException(message)).forEach {
                assertTrue(isClientCertificateAuthenticationFailure(url, it))
                assertTrue(isClientCertificateAuthenticationFailure(url, IOException("wrapped", IOException(it))))
            }
        }
    }

    @Test
    fun `does not infer client authentication from generic TLS server trust or transport failures`() {
        listOf(
            SSLHandshakeException("handshake_failure"),
            SSLHandshakeException("CERTIFICATE_VERIFY_FAILED").apply { initCause(CertificateException("untrusted")) },
            SSLException("Hostname example.test not verified"),
            SSLHandshakeException("PKIX path building failed: unable to find valid certification path"),
            SSLHandshakeException("Trust anchor for certification path not found"),
            SSLException("SSLV3_ALERT_HANDSHAKE_FAILURE"),
            SSLException("TLSV1_ALERT_ACCESS_DENIED"),
            SSLException("TLSV1_ALERT_DECRYPT_ERROR"),
            SSLException("SSLV3_ALERT_CERTIFICATE_VERIFY_FAILED"),
            SSLException("certificate has expired"),
            CertificateException("TLSV1_ALERT_UNKNOWN_CA"),
            SocketTimeoutException("timeout"),
            IOException("Connection reset"),
            SSLException("TLSV1_ALERT_CERTIFICATE_REQUIRED_EXTRA"),
            SSLException("NOT_TLSV1_ALERT_CERTIFICATE_REQUIRED"),
            SSLException("tlsv1 alert certificate required"),
            SSLException("TLSV13_ALERT_CERTIFICATE_REQUIRED"),
        ).forEach { assertFalse(it.toString(), isClientCertificateAuthenticationFailure(url, it)) }
    }

    @Test
    fun `every alert requires an SSL cause HTTPS and exact token boundaries`() {
        alerts.forEach { alert ->
            listOf(
                IOException(alert),
                SSLException("${alert}_EXTRA"),
                SSLException("NOT_$alert"),
                SSLException(alert.lowercase()),
            ).forEach { assertFalse(it.toString(), isClientCertificateAuthenticationFailure(url, it)) }
            assertFalse(
                isClientCertificateAuthenticationFailure("http://example.test/".toHttpUrl(), SSLException(alert)),
            )
        }
    }

    @Test
    fun `a cyclic cause chain terminates`() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        assertFalse(isClientCertificateAuthenticationFailure(url, first))
    }
}
