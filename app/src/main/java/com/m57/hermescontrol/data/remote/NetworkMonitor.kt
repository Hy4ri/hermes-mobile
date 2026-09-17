package com.m57.hermescontrol.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

object NetworkMonitor {
    private val _isConnected = MutableStateFlow(true) // optimistic default
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Unlike StateFlow, this queues every break-before-make old -> null -> new transition.
    private val networkChangeEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = Int.MAX_VALUE)
    internal val networkChanges: SharedFlow<Boolean> = networkChangeEvents.asSharedFlow()

    // Emitted when default network transports change without onLost/onAvailable (issue #1165).
    private val transportChangeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = Int.MAX_VALUE)
    internal val transportChanges: SharedFlow<Unit> = transportChangeEvents.asSharedFlow()

    private val callbackLock = Any()
    private val callbackGeneration = AtomicInteger(0)
    private var callbackRegistration: Pair<ConnectivityManager, ConnectivityManager.NetworkCallback>? = null

    @Volatile
    private var defaultNetwork: Network? = null

    @Volatile
    private var currentTransports: Set<Int>? = null

    fun init(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = createCallback(cm)
        var networkChanged = false
        var networkAvailable = false
        val previous =
            synchronized(callbackLock) {
                val activeNetwork = cm.activeNetwork
                networkChanged = defaultNetwork != activeNetwork
                defaultNetwork = activeNetwork
                networkAvailable = activeNetwork != null
                _isConnected.value = networkAvailable
                currentTransports =
                    runCatching {
                        activeNetwork?.let { cm.getNetworkCapabilities(it) }?.let { extractTransports(it) }
                    }.getOrNull()
                callbackRegistration.also { callbackRegistration = cm to callback }
            }
        if (networkChanged) networkChangeEvents.tryEmit(networkAvailable)
        previous?.let { (previousCm, previousCallback) ->
            runCatching { previousCm.unregisterNetworkCallback(previousCallback) }
        }
        cm.registerDefaultNetworkCallback(callback)
    }

    internal fun createCallback(cm: ConnectivityManager): ConnectivityManager.NetworkCallback {
        val generation = callbackGeneration.incrementAndGet()
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val changed =
                    synchronized(callbackLock) {
                        if (callbackGeneration.get() != generation) return
                        (defaultNetwork != network).also {
                            defaultNetwork = network
                            currentTransports = null
                            _isConnected.value = true
                        }
                    }
                if (changed) networkChangeEvents.tryEmit(true)
            }

            override fun onLost(network: Network) {
                var networkAvailable = false
                val changed =
                    synchronized(callbackLock) {
                        if (callbackGeneration.get() != generation || defaultNetwork != network) return
                        val activeNetwork = cm.activeNetwork?.takeUnless { it == network }
                        (defaultNetwork != activeNetwork).also {
                            defaultNetwork = activeNetwork
                            networkAvailable = activeNetwork != null
                            currentTransports = null
                            _isConnected.value = networkAvailable
                        }
                    }
                if (changed) networkChangeEvents.tryEmit(networkAvailable)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                val transportChanged: Boolean
                synchronized(callbackLock) {
                    if (callbackGeneration.get() != generation || defaultNetwork != network) return
                    val newTransports = extractTransports(networkCapabilities)
                    val oldTransports = currentTransports
                    currentTransports = newTransports
                    transportChanged = oldTransports != null && oldTransports != newTransports
                }
                if (transportChanged) {
                    transportChangeEvents.tryEmit(Unit)
                }
            }

            override fun onLosing(
                network: Network,
                maxMsToLive: Int,
            ) {
                val isDefault: Boolean
                synchronized(callbackLock) {
                    if (callbackGeneration.get() != generation || defaultNetwork != network) return
                    isDefault = true
                }
                if (isDefault) {
                    transportChangeEvents.tryEmit(Unit)
                }
            }
        }
    }

    internal fun extractTransports(capabilities: NetworkCapabilities?): Set<Int> {
        if (capabilities == null) return emptySet()
        val transports = mutableSetOf<Int>()
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            transports.add(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            transports.add(NetworkCapabilities.TRANSPORT_WIFI)
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            transports.add(NetworkCapabilities.TRANSPORT_BLUETOOTH)
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            transports.add(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            transports.add(NetworkCapabilities.TRANSPORT_VPN)
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) {
            transports.add(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)
        ) {
            transports.add(NetworkCapabilities.TRANSPORT_LOWPAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB)
        ) {
            transports.add(NetworkCapabilities.TRANSPORT_USB)
        }
        return transports
    }

    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(callbackLock) {
            defaultNetwork = null
            currentTransports = null
            _isConnected.value = true
        }
    }
}
