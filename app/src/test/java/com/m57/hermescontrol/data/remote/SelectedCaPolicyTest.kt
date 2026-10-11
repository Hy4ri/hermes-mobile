package com.m57.hermescontrol.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class SelectedCaPolicyTest {
    private fun certificates(resource: String): List<X509Certificate> =
        javaClass.getResourceAsStream("/tls/$resource").use {
            CertificateFactory
                .getInstance("X.509")
                .generateCertificates(requireNotNull(it))
                .map { certificate -> certificate as X509Certificate }
        }

    private val chain = certificates("cross-signed-chain.pem").toTypedArray()
    private val userRoot = certificates("cross-signed-user-root.pem").single()
    private val store =
        KeyStore.getInstance("PKCS12").apply {
            load(null)
            setCertificateEntry("system:root", chain.last())
            setCertificateEntry("user:root", userRoot)
        }
    private val catalog = AndroidCaStore { store }
    private val user = catalog.list().single { it.reference.source == CaSource.USER }.reference
    private val system = catalog.list().single { it.reference.source == CaSource.SYSTEM }.reference

    @Test
    fun `cross signed chain cannot bypass source intersection through two successful paths`() {
        val global = TlsTestContext(trusted = listOf(chain.last())).trustManager
        // Each path validates independently, even though the chosen USER anchor is excluded globally.
        global.checkServerTrusted(chain, "RSA")
        catalog.trust(user, true).checkServerTrusted(chain, "RSA")
        val origin = CertificateOrigin("example.test", 443)
        val bindings =
            CertificateBindings(
                mapOf(origin.storageKey to user.encode()),
                persist = { _, _ -> },
                invalidate = {},
            )
        val restricted = OriginCaTrustManager(global, bindings, origin) { catalog.trust(CaReference.decode(it), false) }
        assertThrows(CertificateException::class.java) { restricted.checkServerTrusted(chain, "RSA") }
        catalog.trust(system, false).checkServerTrusted(chain, "RSA")
    }

    @Test
    fun `identical certificates installed in both sources remain bound to selected source`() {
        store.setCertificateEntry("system:duplicate", userRoot)
        assertEquals(userRoot, catalog.resolve(user)) // Saving a draft is permitted with global user trust OFF.
        assertThrows(CertificateException::class.java) { catalog.resolveAllowed(user, false) }
        val systemCopy = catalog.list().single { it.reference.alias == "system:duplicate" }.reference
        catalog.trust(systemCopy, false).checkServerTrusted(chain, "RSA")
        assertThrows(
            CertificateException::class.java,
        ) { catalog.resolveAllowed(user.copy(source = CaSource.SYSTEM), false) }
    }

    @Test
    fun `saved global opt in cannot enable a user selection in the running process`() {
        val policy = StartupServerTrust(false, {}) { TlsTestContext(trusted = listOf(chain.last())).trustManager }
        policy.save(true)
        assertThrows(CertificateException::class.java) { catalog.trust(user, policy.active) }
        val restarted =
            StartupServerTrust(policy.state.value, {}) {
                TlsTestContext(trusted = listOf(chain.last(), userRoot)).trustManager
            }
        catalog.trust(user, restarted.active).checkServerTrusted(chain, "RSA")
    }
}
