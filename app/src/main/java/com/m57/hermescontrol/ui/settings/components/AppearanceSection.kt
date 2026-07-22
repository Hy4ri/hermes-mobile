package com.m57.hermescontrol.ui.settings.components

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import com.m57.hermescontrol.ui.settings.SectionCard
import com.m57.hermescontrol.util.LocaleContextWrapper

@Composable
internal fun AppearanceSection(
    themePreference: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
    useDynamicColors: Boolean,
    onUseDynamicColorsChange: (Boolean) -> Unit,
    themePreset: ThemePreset,
    onThemePresetChange: (ThemePreset) -> Unit,
    appLanguage: String,
    onAppLanguageChange: (String) -> Unit,
) {
    SectionCard {
        Text(
            text = stringResource(R.string.settings_item_theme),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemePreference.entries.forEachIndexed { index, pref ->
                SegmentedButton(
                    selected = themePreference == pref,
                    onClick = { onThemeChange(pref) },
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemePreference.entries.size,
                        ),
                ) {
                    Text(
                        when (pref) {
                            ThemePreference.SYSTEM -> stringResource(R.string.theme_system)
                            ThemePreference.LIGHT -> stringResource(R.string.theme_light)
                            ThemePreference.DARK -> stringResource(R.string.theme_dark)
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_item_use_dynamic_colors),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_desc_use_dynamic_colors),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
            Switch(
                checked = useDynamicColors,
                onCheckedChange = onUseDynamicColorsChange,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_item_theme_preset),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))

        var presetsExpanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { presetsExpanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when (themePreset) {
                        ThemePreset.DEFAULT -> stringResource(R.string.theme_preset_default)
                        ThemePreset.MONOCHROME -> stringResource(R.string.theme_preset_monochrome)
                        ThemePreset.GRUVBOX -> stringResource(R.string.theme_preset_gruvbox)
                        ThemePreset.CATPPUCCIN -> stringResource(R.string.theme_preset_catppuccin)
                        ThemePreset.AMOLED -> stringResource(R.string.theme_preset_amoled)
                        ThemePreset.NEON_NOIR -> stringResource(R.string.theme_preset_neon_noir)
                    },
                )
            }
            DropdownMenu(
                expanded = presetsExpanded,
                onDismissRequest = { presetsExpanded = false },
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                ThemePreset.entries.forEach { preset ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                when (preset) {
                                    ThemePreset.DEFAULT -> {
                                        stringResource(
                                            R.string.theme_preset_default,
                                        )
                                    }

                                    ThemePreset.MONOCHROME -> {
                                        stringResource(
                                            R.string.theme_preset_monochrome,
                                        )
                                    }

                                    ThemePreset.GRUVBOX -> {
                                        stringResource(
                                            R.string.theme_preset_gruvbox,
                                        )
                                    }

                                    ThemePreset.CATPPUCCIN -> {
                                        stringResource(
                                            R.string.theme_preset_catppuccin,
                                        )
                                    }

                                    ThemePreset.AMOLED -> {
                                        stringResource(
                                            R.string.theme_preset_amoled,
                                        )
                                    }

                                    ThemePreset.NEON_NOIR -> {
                                        stringResource(
                                            R.string.theme_preset_neon_noir,
                                        )
                                    }
                                },
                            )
                        },
                        onClick = {
                            onThemePresetChange(preset)
                            presetsExpanded = false
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_item_language),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        val languageOptions =
            listOf(
                LocaleContextWrapper.SYSTEM_LANGUAGE to stringResource(R.string.language_system),
                "en" to stringResource(R.string.language_english),
                "ko" to stringResource(R.string.language_korean),
            )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val activity = LocalActivity.current
            languageOptions.forEachIndexed { index, (code, label) ->
                SegmentedButton(
                    selected = appLanguage == code,
                    onClick = {
                        onAppLanguageChange(code)
                        // MainActivity is a plain ComponentActivity (not
                        // AppCompatActivity), so the locale only takes effect
                        // after the activity is recreated.
                        activity?.recreate()
                    },
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = languageOptions.size,
                        ),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
internal fun ChatSection(
    typingEffectEnabled: Boolean,
    onTypingEffectEnabledChange: (Boolean) -> Unit,
    typingEffectDelayMs: Int,
    onTypingEffectDelayMsChange: (Int) -> Unit,
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.settings_item_typing_effect),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_desc_typing_effect),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
            Switch(
                checked = typingEffectEnabled,
                onCheckedChange = onTypingEffectEnabledChange,
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
            )
        }

        // Delay slider — only visible when effect is enabled
        AnimatedVisibility(visible = typingEffectEnabled) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    text =
                        stringResource(
                            R.string.settings_item_typing_delay,
                            typingEffectDelayMs,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = typingEffectDelayMs.toFloat(),
                    onValueChange = { onTypingEffectDelayMsChange(it.toInt()) },
                    valueRange = 10f..100f,
                    steps = 8, // 10, 20, 30, 40, 50, 60, 70, 80, 90, 100
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_delay_10ms),
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    )
                    Text(
                        text = stringResource(R.string.settings_delay_100ms),
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    )
                }
            }
        }
    }
}
