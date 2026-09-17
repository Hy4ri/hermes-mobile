package com.m57.hermescontrol.data.remote

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetworkMonitorTest {
    @Before
    fun setUp() {
        NetworkMonitor.resetForTest()
    }

    private fun createCapabilities(vararg transports: Int): NetworkCapabilities {
        val capabilities = mockk<NetworkCapabilities>()
        every { capabilities.hasTransport(any()) } answers {
            val transport = firstArg<Int>()
            transports.contains(transport)
        }
        return capabilities
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

    @Test
    fun `initial capabilities callback after onAvailable sets baseline and does not emit transport change`() =
        runTest {
            val connectivityManager = mockk<ConnectivityManager>()
            val network = mockk<Network>()
            val wifiCaps = createCapabilities(NetworkCapabilities.TRANSPORT_WIFI)
            val callback = NetworkMonitor.createCallback(connectivityManager)

            NetworkMonitor.transportChanges.test {
                callback.onAvailable(network)
                callback.onCapabilitiesChanged(network, wifiCaps)

                expectNoEvents()
            }
        }

    @Test
    fun `capabilities change with different transports emits transport change`() =
        runTest {
            val connectivityManager = mockk<ConnectivityManager>()
            val network = mockk<Network>()
            val wifiCaps = createCapabilities(NetworkCapabilities.TRANSPORT_WIFI)
            val vpnWifiCaps = createCapabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN)
            val callback = NetworkMonitor.createCallback(connectivityManager)

            NetworkMonitor.transportChanges.test {
                callback.onAvailable(network)
                callback.onCapabilitiesChanged(network, wifiCaps)
                expectNoEvents()

                callback.onCapabilitiesChanged(network, vpnWifiCaps)
                assertEquals(Unit, awaitItem())
                expectNoEvents()
            }
        }

    @Test
    fun `capabilities change with identical transports does not emit transport change`() =
        runTest {
            val connectivityManager = mockk<ConnectivityManager>()
            val network = mockk<Network>()
            val wifiCaps1 = createCapabilities(NetworkCapabilities.TRANSPORT_WIFI)
            val wifiCaps2 = createCapabilities(NetworkCapabilities.TRANSPORT_WIFI)
            val callback = NetworkMonitor.createCallback(connectivityManager)

            NetworkMonitor.transportChanges.test {
                callback.onAvailable(network)
                callback.onCapabilitiesChanged(network, wifiCaps1)
                expectNoEvents()

                callback.onCapabilitiesChanged(network, wifiCaps2)
                expectNoEvents()
            }
        }

    @Test
    fun `capabilities change on non-default network is ignored`() =
        runTest {
            val connectivityManager = mockk<ConnectivityManager>()
            val defaultNet = mockk<Network>()
            val otherNet = mockk<Network>()
            val wifiCaps = createCapabilities(NetworkCapabilities.TRANSPORT_WIFI)
            val cellularCaps = createCapabilities(NetworkCapabilities.TRANSPORT_CELLULAR)
            val callback = NetworkMonitor.createCallback(connectivityManager)

            NetworkMonitor.transportChanges.test {
                callback.onAvailable(defaultNet)
                callback.onCapabilitiesChanged(defaultNet, wifiCaps)
                expectNoEvents()

                callback.onCapabilitiesChanged(otherNet, cellularCaps)
                expectNoEvents()
            }
        }

    @Test
    fun `onLosing on default network emits transport change`() =
        runTest {
            val connectivityManager = mockk<ConnectivityManager>()
            val defaultNet = mockk<Network>()
            val callback = NetworkMonitor.createCallback(connectivityManager)

            NetworkMonitor.transportChanges.test {
                callback.onAvailable(defaultNet)
                callback.onLosing(defaultNet, 3000)

                assertEquals(Unit, awaitItem())
                expectNoEvents()
            }
        }

    @Test
    fun `onLosing on non-default network is ignored`() =
        runTest {
            val connectivityManager = mockk<ConnectivityManager>()
            val defaultNet = mockk<Network>()
            val otherNet = mockk<Network>()
            val callback = NetworkMonitor.createCallback(connectivityManager)

            NetworkMonitor.transportChanges.test {
                callback.onAvailable(defaultNet)
                callback.onLosing(otherNet, 3000)

                expectNoEvents()
            }
        }

    @Test
    fun `extractTransports identifies all supported transport types`() {
        val caps =
            createCapabilities(
                NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.TRANSPORT_ETHERNET,
                NetworkCapabilities.TRANSPORT_VPN,
                NetworkCapabilities.TRANSPORT_BLUETOOTH,
                NetworkCapabilities.TRANSPORT_WIFI_AWARE,
            )
        val transports = NetworkMonitor.extractTransports(caps)
        assertEquals(
            setOf(
                NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.TRANSPORT_ETHERNET,
                NetworkCapabilities.TRANSPORT_VPN,
                NetworkCapabilities.TRANSPORT_BLUETOOTH,
                NetworkCapabilities.TRANSPORT_WIFI_AWARE,
            ),
            transports,
        )
    }
}
