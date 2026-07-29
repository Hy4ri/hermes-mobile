package com.m57.hermescontrol.data.remote

import android.net.ConnectivityManager
import android.net.Network
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetworkMonitorTest {
    @Before
    fun setUp() {
        val defaultNetworkField = NetworkMonitor::class.java.getDeclaredField("defaultNetwork")
        defaultNetworkField.isAccessible = true
        defaultNetworkField.set(NetworkMonitor, null)
        val connectedField = NetworkMonitor::class.java.getDeclaredField("_isConnected")
        connectedField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (connectedField.get(NetworkMonitor) as MutableStateFlow<Boolean>).value = true
    }

    @Test
    fun `losing the old default network keeps connectivity when replacement is active`() {
        val connectivityManager = mockk<ConnectivityManager>()
        val oldNetwork = mockk<Network>()
        val replacement = mockk<Network>()
        every { connectivityManager.activeNetwork } returns replacement
        val callback = NetworkMonitor.createCallback(connectivityManager)

        callback.onAvailable(oldNetwork)
        callback.onLost(oldNetwork)

        assertTrue(NetworkMonitor.isConnected.value)
    }

    @Test
    fun `old network loss after replacement availability is ignored`() =
        runTest {
            val connectivityManager = mockk<ConnectivityManager>()
            val oldNetwork = mockk<Network>()
            val replacement = mockk<Network>()
            val callback = NetworkMonitor.createCallback(connectivityManager)

            NetworkMonitor.networkChanges.test {
                callback.onAvailable(oldNetwork)
                assertTrue(awaitItem())
                callback.onAvailable(replacement)
                assertTrue(awaitItem())
                callback.onLost(oldNetwork)

                assertTrue(NetworkMonitor.isConnected.value)
                expectNoEvents()
            }
        }

    @Test
    fun `callback from previous registration is ignored after restart`() {
        val connectivityManager = mockk<ConnectivityManager>()
        val oldNetwork = mockk<Network>()
        val replacement = mockk<Network>()
        every { connectivityManager.activeNetwork } returns null
        val staleCallback = NetworkMonitor.createCallback(connectivityManager)
        staleCallback.onAvailable(oldNetwork)
        val currentCallback = NetworkMonitor.createCallback(connectivityManager)
        currentCallback.onAvailable(replacement)

        staleCallback.onLost(oldNetwork)

        assertTrue(NetworkMonitor.isConnected.value)
    }

    @Test
    fun `break before make emits loss and replacement identity transitions in order`() =
        runTest {
            val connectivityManager = mockk<ConnectivityManager>()
            val oldNetwork = mockk<Network>()
            val replacement = mockk<Network>()
            every { connectivityManager.activeNetwork } returns null
            val callback = NetworkMonitor.createCallback(connectivityManager)

            NetworkMonitor.networkChanges.test {
                callback.onAvailable(oldNetwork)
                callback.onLost(oldNetwork)
                callback.onAvailable(replacement)

                assertEquals(true, awaitItem())
                assertEquals(false, awaitItem())
                assertEquals(true, awaitItem())
                expectNoEvents()
            }
        }
}
