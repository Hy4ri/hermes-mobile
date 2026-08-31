package com.m57.hermescontrol.ui.settings.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import com.m57.hermescontrol.ui.settings.SectionCard
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun AppearanceSection(
    themePreference: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
    useDynamicColors: Boolean,
    onUseDynamicColorsChange: (Boolean) -> Unit,
    themePreset: ThemePreset,
    onThemePresetChange: (ThemePreset) -> Unit,
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
                enabled = !useDynamicColors,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when (themePreset) {
                        ThemePreset.DEFAULT -> stringResource(R.string.theme_preset_default)
                        ThemePreset.MONOCHROME -> stringResource(R.string.theme_preset_monochrome)
                        ThemePreset.GRUVBOX -> stringResource(R.string.theme_preset_gruvbox)
                        ThemePreset.CATPPUCCIN -> stringResource(R.string.theme_preset_catppuccin)
                        ThemePreset.AMOLED -> stringResource(R.string.theme_preset_amoled)
                        ThemePreset.NORD -> stringResource(R.string.theme_preset_nord)
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

                                    ThemePreset.NORD -> {
                                        stringResource(
                                            R.string.theme_preset_nord,
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
    }
}

@Composable
internal fun ChatSection(
    typingEffectEnabled: Boolean,
    onTypingEffectEnabledChange: (Boolean) -> Unit,
    typingEffectDelayMs: Int,
    onTypingEffectDelayMsChange: (Int) -> Unit,
    chatFontScale: Float = 1.0f,
    onChatFontScaleChange: (Float) -> Unit = {},
) {
    val fontScaleOptions = listOf(0.85f, 1.0f, 1.15f, 1.30f, 1.50f)
    val currentIndex =
        fontScaleOptions
            .indexOfFirst { abs(it - chatFontScale) < 0.05f }
            .takeIf { it >= 0 } ?: 1

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_item_chat_font_size),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_desc_chat_font_size),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
            val scaleLabel =
                when (currentIndex) {
                    0 -> stringResource(R.string.settings_font_scale_small)
                    1 -> stringResource(R.string.settings_font_scale_default)
                    2 -> stringResource(R.string.settings_font_scale_large)
                    3 -> stringResource(R.string.settings_font_scale_xlarge)
                    else -> stringResource(R.string.settings_font_scale_huge)
                }
            Text(
                text = scaleLabel,
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    ),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Slider(
            value = currentIndex.toFloat(),
            onValueChange = { onChatFontScaleChange(fontScaleOptions[it.roundToInt()]) },
            valueRange = 0f..(fontScaleOptions.size - 1).toFloat(),
            steps = fontScaleOptions.size - 2,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "0.85×",
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
            Text(
                text = "1.0×",
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
            Text(
                text = "1.5×",
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_chat_preview_title),
            style =
                MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        val currentDensity = LocalDensity.current
        val previewDensity =
            remember(currentDensity, chatFontScale) {
                Density(
                    density = currentDensity.density,
                    fontScale = currentDensity.fontScale * chatFontScale,
                )
            }

        CompositionLocalProvider(LocalDensity provides previewDensity) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // User bubble preview
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Surface(
                            shape =
                                RoundedCornerShape(
                                    topStart = 14.dp,
                                    topEnd = 14.dp,
                                    bottomStart = 14.dp,
                                    bottomEnd = 4.dp,
                                ),
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_chat_preview_user),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }

                    // Assistant bubble preview
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        Surface(
                            shape =
                                RoundedCornerShape(
                                    topStart = 14.dp,
                                    topEnd = 14.dp,
                                    bottomStart = 4.dp,
                                    bottomEnd = 14.dp,
                                ),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_chat_preview_agent),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

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
