package com.m57.hermescontrol.ui.channels

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.data.model.MessagingCatalogPlatform
import com.m57.hermescontrol.theme.HermesControlTheme

@Preview(name = "Light", widthDp = 360, heightDp = 420)
@Preview(name = "Dark", widthDp = 360, heightDp = 420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Narrow large text", widthDp = 320, heightDp = 640, fontScale = 1.5f)
@Preview(name = "Narrow Arabic large text", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ar")
@Composable
private fun AvailablePlatformsPreview() {
    HermesControlTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AvailablePlatformRow(
                    platform =
                        MessagingCatalogPlatform(
                            name = "discord",
                            title = "Discord",
                            summary = "Chat with Hermes from a Discord server.",
                            badge = "official",
                        ),
                    installing = false,
                    installLocked = false,
                    onInstall = {},
                )
                AvailablePlatformRow(
                    platform =
                        MessagingCatalogPlatform(
                            name = "slack",
                            title = "Slack",
                            summary = "Workspace bot. Install is off until lazy installs are allowed.",
                            badge = "verified",
                            blocked = "lazy_off",
                            blockedReason = "Lazy installs are off, so setup cannot install this plugin.",
                        ),
                    installing = true,
                    installLocked = false,
                    onInstall = {},
                )
            }
        }
    }
}
