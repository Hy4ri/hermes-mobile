package com.m57.hermescontrol

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.SessionListCacheStore
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.notification.TurnCorrelationTracker
import com.m57.hermescontrol.ui.analytics.AnalyticsPreloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HermesControlApp :
    Application(),
    SingletonImageLoader.Factory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AuthManager.init(this)
        NetworkMonitor.init(this)
        SessionListCacheStore.init(this)
        // Reply-notification turn boundaries survive process death (armed before
        // each mobile prompt, consumed by the matching completion).
        TurnCorrelationTracker.attach(this)
        appScope.launch {
            AuthManager.initializationState.first { it == AuthManager.InitializationState.Ready }
            // Issue #537 follow-up (A): preload analytics in the background after launch
            // so the tab renders instantly when opened (the usage endpoint is slow on a
            // cold backend). Fire-and-forget; never blocks UI startup.
            AnalyticsPreloader.preload(this@HermesControlApp)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { OkHttpProvider.base },
                    ),
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }.crossfade(true)
            .build()
}
