package com.m57.hermescontrol.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.settings.components.SavedProfileRow
import com.m57.hermescontrol.ui.settings.components.ServerTrustContent
import com.m57.hermescontrol.ui.common.ExpressiveButton as Button

/** Saved profile rows, the Trust-CA row and Logout, with the page's bottom padding. */
@Preview(name = "Light", widthDp = 360, heightDp = 520)
@Preview(name = "Dark", widthDp = 360, heightDp = 520, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Narrow large text", widthDp = 320, heightDp = 760, fontScale = 1.5f)
@Preview(name = "Narrow Arabic large text", widthDp = 320, heightDp = 760, fontScale = 1.5f, locale = "ar")
@Composable
private fun ConnectionPagePreview() {
    HermesControlTheme {
        Surface {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SectionCard {
                    SavedProfileRow(
                        name = "Home",
                        url = "http://192.168.1.57:9119/",
                        isActive = true,
                        onSelect = {},
                        onEdit = {},
                        onDelete = {},
                    )
                    SavedProfileRow(
                        name = "Office",
                        url = "https://hermes.office.example.internal:9119/dashboard",
                        isActive = false,
                        onSelect = {},
                        onEdit = {},
                        onDelete = {},
                    )
                }
                ServerTrustContent(
                    enabled = false,
                    saving = false,
                    restartRequired = false,
                    saveFailed = false,
                    onToggle = {},
                )
                Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.settings_logout)) }
            }
        }
    }
}
