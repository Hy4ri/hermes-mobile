package com.m57.hermescontrol.ui.chat.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.common.ExpressiveCircularProgressIndicator
import com.m57.hermescontrol.ui.common.ExpressiveIconButton

@Composable
internal fun SpeechPlaybackButton(
    isActive: Boolean,
    isPreparing: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(if (isActive) R.string.content_desc_stop_speaking else R.string.content_desc_speak)
    val preparing = stringResource(R.string.tts_preparing)
    ExpressiveIconButton(
        onClick = onClick,
        modifier =
            Modifier.size(48.dp).testTag("fullbleed_speak").semantics {
                contentDescription = label
                if (isPreparing) stateDescription = preparing
            },
    ) {
        if (isPreparing) {
            ExpressiveCircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                imageVector = if (isActive) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "Light", widthDp = 320, heightDp = 64)
@Preview(name = "Dark", widthDp = 320, heightDp = 64, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Arabic large text", widthDp = 320, heightDp = 64, fontScale = 1.5f, locale = "ar")
@Preview(name = "Wide", widthDp = 600, heightDp = 64)
@Composable
private fun SpeechPlaybackButtonPreview() {
    val rtl = LocalConfiguration.current.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        HermesControlTheme {
            Surface {
                Row {
                    SpeechPlaybackButton(false, false) {}
                    SpeechPlaybackButton(true, true) {}
                    SpeechPlaybackButton(true, false) {}
                }
            }
        }
    }
}
