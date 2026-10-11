package com.m57.hermescontrol.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateException

class AndroidCaStoreTest {
    private val fixtures =
        KeyStore.getInstance("PKCS12").apply {
            AndroidCaStoreTest::class.java.getResourceAsStream("/tls/server-trust.p12").use {
                load(requireNotNull(it), TlsTestIdentity.PASSWORD)
            }
        }
    private val store =
        KeyStore.getInstance("PKCS12").apply {
            load(null)
            setCertificateEntry("system:root", fixtures.getCertificate("system-ca"))
            setCertificateEntry("user:root", fixtures.getCertificate("user-ca"))
            setCertificateEntry("user:leaf", fixtures.getCertificate("user"))
            setCertificateEntry("unknown:root", fixtures.getCertificate("unknown-ca"))
        }
    private val catalog = AndroidCaStore { store }

    @Test
    fun `picker lists only CA certificates with explicit system and user sources and details`() {
        val entries = catalog.list()
        assertEquals(listOf(CaSource.SYSTEM, CaSource.USER), entries.map { it.reference.source })
        entries.forEach {
            assertEquals(64, it.reference.sha256.length)
            assertEquals(it.certificate.subjectX500Principal.name, it.reference.subject)
            assertEquals(it.reference, CaReference.decode(it.reference.encode()))
            assertEquals(it.certificate, catalog.resolve(it.reference))
            assertEquals(listOf(it.certificate), catalog.trust(it.reference, true).acceptedIssuers.toList())
        }
    }

    @Test
    fun `removed replaced non-CA and forged-source references fail closed`() {
        val ref = catalog.list().first().reference
        assertThrows(CertificateException::class.java) { catalog.resolve(ref.copy(source = CaSource.USER)) }
        store.setCertificateEntry(ref.alias, fixtures.getCertificate("user-ca"))
        assertThrows(CertificateException::class.java) { catalog.resolve(ref) }
        store.setCertificateEntry(ref.alias, fixtures.getCertificate("system"))
        assertThrows(CertificateException::class.java) { catalog.resolve(ref) }
        store.deleteEntry(ref.alias)
        assertThrows(CertificateException::class.java) { catalog.resolve(ref) }
    }

    @Test
    fun `store errors are propagated without inventing roots or falling back to global trust`() {
        val broken = AndroidCaStore { throw IOException("store unavailable") }
        assertThrows(IOException::class.java) { broken.list() }
        assertThrows(IOException::class.java) { broken.trust(catalog.list().first().reference, true) }
    }

    @Test
    fun `handshake resolution reads only the selected alias and rejects invalid CA validity`() {
        val selected = catalog.list().first()
        val certificate = io.mockk.mockk<java.security.cert.X509Certificate>()
        io.mockk.every { certificate.encoded } returns selected.certificate.encoded
        io.mockk.every { certificate.basicConstraints } returns selected.certificate.basicConstraints
        io.mockk.every { certificate.checkValidity() } returns Unit
        val indexedStore = io.mockk.mockk<KeyStore>()
        io.mockk.every { indexedStore.getCertificate(selected.reference.alias) } returns certificate
        io.mockk.every { indexedStore.aliases() } throws AssertionError("Handshake must not enumerate CA aliases")
        val indexed = AndroidCaStore { indexedStore }
        org.junit.Assert.assertSame(certificate, indexed.resolveAllowed(selected.reference, false))
        io.mockk.every { certificate.checkValidity() } throws java.security.cert.CertificateExpiredException()
        assertThrows(CertificateException::class.java) { indexed.resolveAllowed(selected.reference, false) }
    }
}
