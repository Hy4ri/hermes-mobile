package com.m57.hermescontrol

import android.app.Application
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.NetworkMonitor

class HermesControlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthManager.init(this)
        NetworkMonitor.init(this)
    }
}
