package com.m57.hermescontrol.data.remote

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class ServerTrustSelectionTest {
    @Test
    fun `disabled uses platform defaults without opening Android store`() {
        val factory = mockk<TrustManagerFactory>()
        val manager = mockk<X509TrustManager>()
        every { factory.init(null as KeyStore?) } returns Unit
        every { factory.trustManagers } returns arrayOf(manager)
        assertSame(manager, ServerTrust.loadTrustManager(false, { error("Must not open user store") }, { factory }))
        verify(exactly = 1) { factory.init(null as KeyStore?) }
    }

    @Test
    fun `enabled passes Android store directly to platform factory`() {
        val store = mockk<KeyStore>()
        val factory = mockk<TrustManagerFactory>()
        val manager = mockk<X509TrustManager>()
        every { factory.init(store) } returns Unit
        every { factory.trustManagers } returns arrayOf(manager)
        assertSame(manager, ServerTrust.loadTrustManager(true, { store }, { factory }))
        verify(exactly = 1) { factory.init(store) }
        verify(exactly = 0) { store.aliases() }
    }

    @Test
    fun `store and factory failures propagate instead of accepting certificates`() {
        val factory = mockk<TrustManagerFactory>()
        assertThrows(IOException::class.java) {
            ServerTrust.loadTrustManager(true, { throw IOException("Store unavailable") }, { factory })
        }
        every { factory.init(null as KeyStore?) } throws java.security.KeyStoreException("Factory failure")
        assertThrows(java.security.KeyStoreException::class.java) {
            ServerTrust.loadTrustManager(false, factory = { factory })
        }
    }
}
