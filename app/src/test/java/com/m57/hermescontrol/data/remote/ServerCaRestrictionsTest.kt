package com.m57.hermescontrol.data.remote

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/** In-memory preferences only; verifies upgrade separation and rollback without touching device storage. */
class ServerCaRestrictionsTest {
    private val url = CertificateOrigin("example.test", 443).url
    private val reference = CaReference("user:test", CaSource.USER, "test-fingerprint", "Test CA").encode()
    private val memory = mutableMapOf<String, String>(url.toString() to reference)
    private var disk = memory.toMap()
    private var fail = false
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val app = mockk<Context>()

    @Before
    fun setup() {
        every { app.applicationContext } returns app
        every { app.getSharedPreferences("server_ca_restrictions", Context.MODE_PRIVATE) } returns preferences
        every { preferences.all } answers { memory.toMap() }
        every { preferences.edit() } returns editor
        every { editor.clear() } answers {
            memory.clear()
            editor
        }
        every { editor.putString(any(), any()) } answers {
            memory[firstArg()] = secondArg<String>()
            editor
        }
        every { editor.commit() } answers {
            if (fail) {
                false
            } else {
                disk = memory.toMap()
                true
            }
        }
        mockkObject(ServerTrust)
        mockkObject(ClientCertificates)
        every { ClientCertificates.invalidateServerCa(any()) } returns Unit
        ServerCaRestrictions.initialize(app)
    }

    @After
    fun cleanup() {
        unmockkObject(ServerTrust, ClientCertificates)
    }

    @Test
    fun `failed removal restores preference memory runtime binding and restart state`() {
        val before = ServerCaRestrictions.state.value
        fail = true
        assertThrows(IOException::class.java) { ServerCaRestrictions.save(url, null, null, before) }
        assertEquals(before, ServerCaRestrictions.state.value)
        assertEquals(reference, memory[url.toString()])
        memory.clear()
        memory.putAll(disk)
        ServerCaRestrictions.initialize(app)
        assertEquals(before, ServerCaRestrictions.state.value)
        fail = false
        ServerCaRestrictions.save(url, null, null, before)
        memory.clear()
        memory.putAll(disk)
        ServerCaRestrictions.initialize(app)
        assertEquals(emptyMap<String, String?>(), ServerCaRestrictions.state.value)
    }

    @Test
    fun `upgrade reads no client alias preferences and removal does not enable global user trust`() {
        assertEquals(mapOf(url.toString() to reference), ServerCaRestrictions.state.value)
        ServerCaRestrictions.save(url, null, null, ServerCaRestrictions.state.value)
        verify(exactly = 0) { app.getSharedPreferences("client_certificate_aliases", any()) }
        verify(exactly = 0) { ServerTrust.setEnabled(any()) }
    }

    @Test
    fun `availability preserves the original store failure and socket wraps it exactly once`() {
        val failure = CertificateException("selected CA removed")
        val store = ServerCaRestrictions.store
        mockkObject(store)
        try {
            every { ServerTrust.active } returns true
            every { store.resolveAllowed(any(), true) } throws failure
            val origin = CertificateOrigin.from(url)!!
            assertSame(
                failure,
                assertThrows(CertificateException::class.java) { ServerCaRestrictions.checkAvailable(origin) },
            )
            val sockets =
                CertificateSocketFactory(
                    mockk<X509TrustManager>(relaxed = true),
                    checkOrigin = ServerCaRestrictions::checkAvailable,
                    keys = { error("Rejected selection must not create a socket") },
                )
            val rejected = assertThrows(SSLException::class.java) { sockets.createSocket(origin.host, origin.port) }
            assertEquals("Selected Android CA is unavailable", rejected.message)
            assertSame(failure, rejected.cause)
        } finally {
            unmockkObject(store)
        }
    }

    @Test
    fun `malformed selections fail at availability while unbound origins need no trust store`() {
        memory[url.toString()] = "invalid-json"
        ServerCaRestrictions.initialize(app)
        assertThrows(IllegalArgumentException::class.java) {
            ServerCaRestrictions.checkAvailable(CertificateOrigin.from(url)!!)
        }
        ServerCaRestrictions.checkAvailable(CertificateOrigin("unbound.test", 443))
        verify(exactly = 0) { ServerTrust.active }
    }

    @Test
    fun `HTTP fixture teardown restores malformed selection rejection`() {
        mockSystemServerTrust()
        unmockSystemServerTrust()
        mockkObject(ServerTrust)
        `malformed selections fail at availability while unbound origins need no trust store`()
    }

    @Test
    fun `HTTP fixture teardown restores original store failure at socket boundary`() {
        mockSystemServerTrust()
        unmockSystemServerTrust()
        mockkObject(ServerTrust)
        `availability preserves the original store failure and socket wraps it exactly once`()
    }
}
