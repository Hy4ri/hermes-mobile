package com.m57.hermescontrol.ui.chat.components

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.theme.HermesControlTheme

/** Static fixtures; production input, dropdowns, and actions, no gateway connection. */
@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Narrow Arabic large text", widthDp = 320, fontScale = 1.5f, locale = "ar")
@Preview(name = "Wide", widthDp = 600)
@Composable
private fun ComposerExpressivePreview() {
    // Layoutlib localizes resources but does not propagate RTL to the root.
    val direction =
        if (LocalConfiguration.current.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        HermesControlTheme {
            Surface {
                Column {
                    ComposerFixture(draft = "", connected = true)
                    ComposerFixture(draft = "Review the changes", connected = true)
                    ComposerFixture(draft = "", connected = true, streaming = true)
                    ComposerFixture(draft = "Waiting for connection", connected = false)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerFixture(
    draft: String,
    connected: Boolean,
    streaming: Boolean = false,
) {
    ChatInputBar(
        inputState = rememberTextFieldState(draft),
        onSend = {},
        onMicTap = {},
        isListening = false,
        isAgentTyping = streaming,
        canInterrupt = streaming,
        isConnected = connected,
        commandCatalog = CommandCatalog(),
        isSessionReady = connected,
        currentSessionModel = "openai/gpt-5.5",
        reasoningLevel = "medium",
    )
}
