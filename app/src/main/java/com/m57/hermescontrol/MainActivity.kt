package com.m57.hermescontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.notification.NotificationReplyReceiver
import com.m57.hermescontrol.theme.HermesControlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read notification tap sessionId (if any) from the intent that launched us
        val notificationSessionId = intent?.getStringExtra(NotificationReplyReceiver.EXTRA_SESSION_ID)

        enableEdgeToEdge()
        setContent {
            // B6 (Jun 18 2026, kanban t_86e9be9b): read the stored theme
            // preference at startup so HermesControlTheme actually applies
            // the user's SYSTEM/LIGHT/DARK choice rather than defaulting to
            // SYSTEM on every cold start.
            HermesControlTheme(themePreference = AuthManager.getThemePreference()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainNavigation(sessionId = notificationSessionId)
                }
            }
        }
    }
}
