package com.m57.hermescontrol.data.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.m57.hermescontrol.R

/**
 * Foreground service that keeps the app process responsive while the native (browser) sign-in is
 * in flight.
 *
 * RFC 8252 sign-in opens the system browser (Custom Tab) and then waits for a loopback
 * [LoopbackCallbackServer] callback. The moment the browser takes focus Android drops the app to a
 * low-priority process state and pauses its threads — including the loopback listener — so the
 * consent redirect never reaches the server and the user is left on an endless "loading" spinner
 * until they return to the app. Running a foreground service pins the process at
 * STATE_FOREGROUND_SERVICE, so the loopback thread stays runnable and the callback completes even
 * while the browser is in front.
 */
class LoginKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.login_keepalive_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.login_keepalive_title))
                .setContentText(getString(R.string.login_keepalive_text))
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The notification is removed automatically when the service stops.
    }

    companion object {
        private const val CHANNEL_ID = "login_keepalive"
        private const val NOTIFICATION_ID = 0x4C0A

        /** Start the keep-alive foreground service (idempotent; no-op if already running). */
        fun start(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, LoginKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        /** Stop the keep-alive foreground service (no-op if not running). */
        fun stop(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, LoginKeepAliveService::class.java))
        }
    }
}
