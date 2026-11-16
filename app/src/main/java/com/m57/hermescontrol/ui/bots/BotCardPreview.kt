package com.m57.hermescontrol.ui.bots

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.theme.HermesControlTheme

/** Active and inactive bot rows must both read as cards; the handle stays on one line. */
@Preview(name = "Light", widthDp = 360, heightDp = 300)
@Preview(name = "Dark", widthDp = 360, heightDp = 300, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Narrow large text", widthDp = 320, heightDp = 420, fontScale = 1.5f)
@Preview(name = "Narrow Arabic large text", widthDp = 320, heightDp = 420, fontScale = 1.5f, locale = "ar")
@Composable
private fun BotCardPreview() {
    HermesControlTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BotCard(
                    profile = ProfileInfo(name = "wife", display_name = "Wife", description = "Daily helper"),
                    isActiveProfile = true,
                    isActiveNow = true,
                    onClick = {},
                    onEditClick = {},
                )
                BotCard(
                    profile = ProfileInfo(name = "y", display_name = "g", description = "Group chat bot"),
                    isActiveProfile = false,
                    isActiveNow = false,
                    onClick = {},
                    onEditClick = {},
                )
            }
        }
    }
}
