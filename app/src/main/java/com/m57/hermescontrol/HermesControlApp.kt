package com.m57.hermescontrol

import android.app.Application
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.ui.analytics.AnalyticsPreloader

class HermesControlApp : Application() {
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
    }
}
