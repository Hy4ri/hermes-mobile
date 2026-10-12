package com.m57.hermescontrol.data.remote

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class OriginCaTrustManagerTest {
    private val global = mockk<X509TrustManager>()
    private val origin = CertificateOrigin("example.test", 443)
    private val chain = arrayOf(mockk<X509Certificate>())

    private fun manager(
        bindings: Map<String, String?> = emptyMap(),
        selected: (String) -> X509TrustManager = { error("No selected trust expected") },
    ) = OriginCaTrustManager(
        global,
        CertificateBindings(bindings, persist = {
            _,
            _,
            ->
        }, invalidate = {}),
        origin,
        selected,
    )

    @Test
    fun `client checks and issuers delegate to global even with a selected binding`() {
        val failure = CertificateException("client rejected")
        every { global.acceptedIssuers } returns chain
        every { global.checkClientTrusted(chain, "RSA") } throws failure
        val manager = manager(mapOf(origin.storageKey to "selected"))
        assertSame(chain, manager.acceptedIssuers)
        assertSame(failure, assertThrows(CertificateException::class.java) { manager.checkClientTrusted(chain, "RSA") })
        verify(exactly = 1) { global.checkClientTrusted(chain, "RSA") }
    }

    @Test
    fun `unbound server rejection is propagated without wrapping`() {
        val failure = CertificateException("global rejected")
        every { global.checkServerTrusted(chain, "RSA") } throws failure
        assertSame(
            failure,
            assertThrows(CertificateException::class.java) { manager().checkServerTrusted(chain, "RSA") },
        )
    }

    @Test
    fun `selected failure keeps the handshake certificate boundary and direct cause without global fallback`() {
        val failure = IllegalArgumentException("invalid selected reference")
        val manager = manager(mapOf(origin.storageKey to "broken")) { throw failure }
        val rejected = assertThrows(CertificateException::class.java) { manager.checkServerTrusted(chain, "RSA") }
        assertEquals("Server chain does not satisfy selected CA", rejected.message)
        assertSame(failure, rejected.cause)
        verify(exactly = 0) { global.checkServerTrusted(any(), any()) }
    }
}
