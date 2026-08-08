package com.m57.hermescontrol.theme.presets

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.PaletteColors
import com.m57.hermescontrol.theme.buildThemeDarkOnly

// ---------------------------------------------------------------------
// AMOLED — true-black variant of MonochromeTheme. Dark only:
// background is pure #000000 so OLED panels switch those pixels off,
// which MonochromeTheme's #121212 baseline doesn't give you.
// ---------------------------------------------------------------------

private val AmoledFloor = Color(0xFF000000) // background/surface/lowest — nothing darker exists
private val AmoledContainer = Color(0xFF0D0D0D) // surfaceContainer
private val AmoledContainerHigh = Color(0xFF1A1A1A) // surfaceContainerHigh, surfaceVariant, status containers
private val AmoledContainerHighest = Color(0xFF262626) // surfaceContainerHighest, primaryContainer
private val AmoledOutline = Color(0xFF808080)
private val AmoledOutlineVariant = Color(0xFF404040)
private val AmoledTextDim = Color(0xFFB3B3B3) // onSurfaceVariant
private val AmoledTextBright = Color(0xFFFFFFFF) // pure white — max contrast on black; primary, error, text
private val AmoledSecondary = Color(0xFFCCCCCC) // secondary accent + warning
private val AmoledTertiary = Color(0xFF999999) // tertiary accent + success
private val AmoledInfo = Color(0xFF808080) // info — same value as outline, distinct role
private val AmoledInversePrimary = Color(0xFF121212) // dark tone on the (light, fallback) inverseSurface

/**
 * AMOLED theme — dark-only variant of MonochromeTheme for
 * true-black displays. Background is pure #000000 rather than the
 * #121212 baseline the standard Monochrome preset uses, so surfaceContainerLowest
 * and surfaceContainerLow collapse to the same value — there's nothing
 * darker than black to separate them into. Light mode isn't shipped
 * (a light AMOLED theme is a contradiction); the dispatcher falls back
 * to the default theme.
 *
 * Same accessibility note as MonochromeTheme: status colors are pure
 * grayscale, separated only by lightness, so don't rely on them alone
 * to convey success/warning/error/info — pair with an icon or label.
 */
val AmoledTheme =
    buildThemeDarkOnly(
        dark =
            PaletteColors(
                primary = AmoledTextBright,
                onPrimary = AmoledFloor,
                primaryContainer = AmoledContainerHighest,
                onPrimaryContainer = AmoledTextBright,
                secondary = AmoledSecondary,
                onSecondary = AmoledFloor,
                secondaryContainer = AmoledContainerHigh,
                onSecondaryContainer = AmoledSecondary,
                tertiary = AmoledTertiary,
                onTertiary = AmoledFloor,
                tertiaryContainer = AmoledContainerHigh,
                onTertiaryContainer = AmoledTertiary,
                background = AmoledFloor,
                onBackground = AmoledTextBright,
                surface = AmoledFloor,
                onSurface = AmoledTextBright,
                surfaceVariant = AmoledContainerHigh,
                onSurfaceVariant = AmoledTextDim,
                surfaceContainerLowest = AmoledFloor,
                surfaceContainerLow = AmoledFloor,
                surfaceContainer = AmoledContainer,
                surfaceContainerHigh = AmoledContainerHigh,
                surfaceContainerHighest = AmoledContainerHighest,
                inverseSurface = AmoledTextBright,
                inverseOnSurface = AmoledFloor,
                inversePrimary = AmoledInversePrimary,
                outline = AmoledOutline,
                outlineVariant = AmoledOutlineVariant,
                scrim = Color.Black,
                status =
                    HermesStatusColors(
                        success = AmoledTertiary,
                        successContainer = AmoledContainerHigh,
                        onSuccess = AmoledFloor,
                        warning = AmoledSecondary,
                        warningContainer = AmoledContainerHigh,
                        onWarning = AmoledFloor,
                        error = AmoledTextBright,
                        errorContainer = AmoledContainerHigh,
                        onError = AmoledFloor,
                        onErrorContainer = AmoledTextBright,
                        info = AmoledInfo,
                        infoContainer = AmoledContainerHigh,
                        onInfo = AmoledFloor,
                    ),
            ),
    )
