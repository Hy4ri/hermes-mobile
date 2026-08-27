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
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.update.UpdateNoticeManager
import com.m57.hermescontrol.ui.analytics.AnalyticsPreloader

class HermesControlApp :
    Application(),
    SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        AuthManager.init(this)
        // Issue #478: guarantee a "Default" profile is always selected so there is no
        // separate standalone/default code path anywhere in the app.
        AuthManager.ensureDefaultProfile()
        NetworkMonitor.init(this)
        // Issue #537 follow-up (A): preload analytics in the background after launch
        // so the tab renders instantly when opened (the usage endpoint is slow on a
        // cold backend). Fire-and-forget; never blocks UI startup.
        AnalyticsPreloader.preload(this)
        // Issue #890: silent once-per-version update check right after launch,
        // so the chat screen can show an update banner without user interaction.
        UpdateNoticeManager.checkOnLaunch()
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
