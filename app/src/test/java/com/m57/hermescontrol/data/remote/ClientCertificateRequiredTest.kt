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

class ClientCertificateRequiredTest {
    private val url = "https://example.test:8443/api/status".toHttpUrl()
    private val alert = "error:1000045c:SSL routines:OPENSSL_internal:TLSV1_ALERT_CERTIFICATE_REQUIRED"

    @Test
    fun `accepts the exact alert in SSL handshake or protocol exceptions and wrapped causes`() {
        listOf(SSLException(alert), SSLHandshakeException(alert), SSLProtocolException(alert)).forEach {
            assertTrue(isClientCertificateRequired(url, it))
            assertTrue(isClientCertificateRequired(url, IOException("wrapped call failure", IOException(it))))
        }
    }

    @Test
    fun `does not infer client authentication from generic TLS server trust or transport failures`() {
        listOf(
            SSLHandshakeException("handshake_failure"),
            SSLHandshakeException("CERTIFICATE_VERIFY_FAILED").apply { initCause(CertificateException("untrusted")) },
            SSLException("Hostname example.test not verified"),
            SSLException("TLSV1_ALERT_UNKNOWN_CA"),
            SSLException("SSLV3_ALERT_BAD_CERTIFICATE"),
            SocketTimeoutException("timeout"),
            IOException("Connection reset"),
            IOException(alert),
            SSLException("TLSV1_ALERT_CERTIFICATE_REQUIRED_EXTRA"),
            SSLException("NOT_TLSV1_ALERT_CERTIFICATE_REQUIRED"),
            SSLException("tlsv1 alert certificate required"),
            SSLException("TLSV13_ALERT_CERTIFICATE_REQUIRED"),
        ).forEach { assertFalse(it.toString(), isClientCertificateRequired(url, it)) }
        assertFalse(isClientCertificateRequired("http://example.test/".toHttpUrl(), SSLException(alert)))
    }

    @Test
    fun `a cyclic cause chain terminates`() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        assertFalse(isClientCertificateRequired(url, first))
    }
}
