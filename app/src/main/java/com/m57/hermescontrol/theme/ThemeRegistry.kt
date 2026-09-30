package com.m57.hermescontrol.theme

import com.m57.hermescontrol.theme.presets.AmoledTheme
import com.m57.hermescontrol.theme.presets.CatppuccinTheme
import com.m57.hermescontrol.theme.presets.DefaultTheme
import com.m57.hermescontrol.theme.presets.GruvboxTheme
import com.m57.hermescontrol.theme.presets.MonochromeTheme
import com.m57.hermescontrol.theme.presets.NordTheme

internal data class ThemeDefinition(
    val preset: ThemePreset,
    val palette: ThemePalette,
)

// Regression theme-contract: selection, resolution, and tests share this registry.
internal val ThemeRegistry =
    listOf(
        ThemeDefinition(ThemePreset.DEFAULT, DefaultTheme),
        ThemeDefinition(ThemePreset.MONOCHROME, MonochromeTheme),
        ThemeDefinition(ThemePreset.GRUVBOX, GruvboxTheme),
        ThemeDefinition(ThemePreset.CATPPUCCIN, CatppuccinTheme),
        ThemeDefinition(ThemePreset.AMOLED, AmoledTheme),
        ThemeDefinition(ThemePreset.NORD, NordTheme),
    )

internal fun ThemePreset.palette(): ThemePalette = ThemeRegistry.single { it.preset == this }.palette
