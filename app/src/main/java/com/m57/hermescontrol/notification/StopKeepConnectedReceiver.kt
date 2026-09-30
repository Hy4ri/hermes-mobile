package com.m57.hermescontrol.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.m57.hermescontrol.data.local.AuthManager

/**
 * Handles the "Turn off" action on the ongoing background notification:
 * disables "Stay connected in background" and lets the controller retire the
 * lease/service.
 */
class StopKeepConnectedReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP_KEEP_CONNECTED = "com.m57.hermescontrol.ACTION_STOP_KEEP_CONNECTED"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_STOP_KEEP_CONNECTED) return
        AuthManager.setKeepConnectedInBackground(false)
        BackgroundConnectionController.default.onKeepConnectedDisabled()
    }
}
