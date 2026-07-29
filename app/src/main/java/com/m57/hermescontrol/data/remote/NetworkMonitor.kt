package com.m57.hermescontrol.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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

    private val callbackLock = Any()
    private val callbackGeneration = AtomicInteger(0)
    private var callbackRegistration: Pair<ConnectivityManager, ConnectivityManager.NetworkCallback>? = null

    @Volatile
    private var defaultNetwork: Network? = null

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
                            _isConnected.value = networkAvailable
                        }
                    }
                if (changed) networkChangeEvents.tryEmit(networkAvailable)
            }
        }
    }
}
