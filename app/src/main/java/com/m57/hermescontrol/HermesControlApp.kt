package com.m57.hermescontrol

import android.app.Application
import com.m57.hermescontrol.data.local.AuthManager

class HermesControlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthManager.init(this)
        com.m57.hermescontrol.data.remote.NetworkMonitor
            .init(this)
    }
}
