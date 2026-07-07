package com.m57.hermescontrol

import android.app.Application
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.NetworkMonitor

class HermesControlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthManager.init(this)
        // Issue #478: guarantee a "Default" profile is always selected so there is no
        // separate standalone/default code path anywhere in the app.
        AuthManager.ensureDefaultProfile()
        NetworkMonitor.init(this)
    }
}
