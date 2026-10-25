package com.m57.hermescontrol.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import kotlinx.coroutines.delay

/** How long a connection must stay unhealthy before the banner shows. Hides cold-start and quick reconnect flicker. */
internal const val OFFLINE_BANNER_DELAY_MS = 1_500L

/** True when [status] means remote operations are expected to fail. */
internal fun ConnectionStatus.isOffline(): Boolean = this != ConnectionStatus.CONNECTED && this != ConnectionStatus.CONNECTING

/**
 * Slim, app-wide notice that the gateway connection is lost (issue #1686).
 * Shown by [HermesScaffold] on every screen except chat, which has its own banner.
 */
@Composable
fun OfflineBanner(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        if (status.isOffline()) {
            delay(OFFLINE_BANNER_DELAY_MS)
            shown = true
        } else {
            shown = false
        }
    }
    AnimatedVisibility(
        visible = shown && status.isOffline(),
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = LocalHermesStatusColors.current.errorContainer,
            contentColor = LocalHermesStatusColors.current.error,
        ) {
            Text(
                text =
                    stringResource(
                        when (status) {
                            ConnectionStatus.RECONNECTING -> R.string.chat_status_reconnecting
                            ConnectionStatus.NO_NETWORK -> R.string.chat_status_no_network
                            ConnectionStatus.AUTH_EXPIRED -> R.string.chat_status_auth_expired
                            else -> R.string.chat_status_disconnected
                        },
                    ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}
