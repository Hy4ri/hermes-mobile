package com.m57.hermescontrol.ui.common

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreset

/** Uses the same adapters as production, not stand-in drawings of the components. */
@Preview(name = "Light", widthDp = 360, heightDp = 1100)
@Preview(name = "Dark", widthDp = 360, heightDp = 1100, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Narrow Arabic large text", widthDp = 320, heightDp = 1500, fontScale = 1.5f, locale = "ar")
@Preview(name = "Wide", widthDp = 600, heightDp = 1100)
@Composable
private fun ExpressiveComponentsPreview() {
    val configuration = LocalConfiguration.current
    val rtl = configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        HermesControlTheme(themePreset = if (rtl) ThemePreset.MONOCHROME else ThemePreset.GARNET) {
            Surface {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Expressive components", style = MaterialTheme.typography.titleLarge)
                    Text("Wavy progress: indeterminate / 60%", style = MaterialTheme.typography.titleMedium)
                    ExpressiveLinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    ExpressiveLinearProgressIndicator(progress = { 0.6f }, modifier = Modifier.fillMaxWidth())
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ExpressiveCircularProgressIndicator()
                        ExpressiveCircularProgressIndicator(progress = { 0.6f })
                        ExpressiveCircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Text("Morphing loading state", style = MaterialTheme.typography.titleMedium)
                    Box(Modifier.fillMaxWidth().height(80.dp)) { LoadingState() }
                    Text("Buttons: enabled / disabled", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExpressiveButton(onClick = {}) { Text(stringResource(R.string.action_save)) }
                        ExpressiveButton(onClick = {}, enabled = false) { Text(stringResource(R.string.action_save)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExpressiveFilledTonalButton(onClick = {}) { Text(stringResource(R.string.action_retry)) }
                        ExpressiveFilledTonalButton(onClick = {}, enabled = false) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExpressiveOutlinedButton(onClick = {}) { Text(stringResource(R.string.action_cancel)) }
                        ExpressiveTextButton(onClick = {}) { Text(stringResource(R.string.action_confirm)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HermesIconButton(Icons.Filled.Refresh, stringResource(R.string.action_retry), {})
                        HermesIconButton(
                            Icons.Filled.Refresh,
                            stringResource(R.string.action_retry),
                            {},
                            enabled = false,
                        )
                    }
                    Text("Connected single-choice buttons", style = MaterialTheme.typography.titleMedium)
                    var selected by remember { mutableIntStateOf(0) }
                    ExpressiveChoiceRow(
                        options =
                            listOf(
                                stringResource(R.string.theme_system),
                                stringResource(R.string.theme_light),
                                stringResource(R.string.theme_dark),
                            ),
                        selectedIndex = selected,
                        onSelect = { selected = it },
                    )
                    Text("Switches and native slider", style = MaterialTheme.typography.titleMedium)
                    var checked by remember { mutableStateOf(true) }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ExpressiveSwitch(checked, { checked = it })
                        ExpressiveSwitch(false, {}, enabled = false)
                        ExpressiveSwitch(true, {}, enabled = false)
                    }
                    var value by remember { mutableFloatStateOf(2f) }
                    Text(stringResource(R.string.settings_item_chat_font_size))
                    Slider(value, { value = it }, valueRange = 0f..4f, steps = 3)
                }
            }
        }
    }
}
