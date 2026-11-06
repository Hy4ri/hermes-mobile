package com.m57.hermescontrol.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.settings.components.SupportSection

/** Static fixture of the production settings index, without a live ViewModel or profile lock. */
@Preview(name = "Light", widthDp = 360, heightDp = 800)
@Preview(name = "Dark", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Narrow Arabic large text", widthDp = 320, heightDp = 800, fontScale = 1.5f, locale = "ar")
@Preview(name = "Wide", widthDp = 600, heightDp = 800)
@Composable
private fun SettingsExpressivePreview() {
    // Layoutlib localizes resources but does not propagate RTL to the root.
    val direction =
        if (LocalConfiguration.current.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        HermesControlTheme {
            HermesScaffold(
                title = { Text(stringResource(R.string.screen_settings)) },
                navigationIcon = NavIcon.Menu {},
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    SettingsCategoryCard(settingsRows(SettingsUiState(), profileLocked = false))
                    SupportSection()
                }
            }
        }
    }
}
