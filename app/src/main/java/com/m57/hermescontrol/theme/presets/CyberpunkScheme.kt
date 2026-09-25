package com.m57.hermescontrol.theme.presets

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.PaletteColors
import com.m57.hermescontrol.theme.buildThemeDarkOnly

// ---------------------------------------------------------------------
// Cyberpunk — neon-green-on-black theme ported from the Hermes
// dashboard (theme-presets.ts "cyberpunk"). Dark only: the source
// palette is entirely near-black surfaces with neon green accents —
// a light variant makes no sense, so light mode falls back to the
// default theme (same mode as AMOLED).
//
// Web token → Material slot mapping.
//
// The web palette follows the shadcn convention, where `secondary` and
// `accent` are *surface* tokens paired with a `…Foreground` ink token.
// Material 3's `secondary`/`tertiary` are the opposite: they are accent
// INKS, painted onto the background as icon tints, progress indicators,
// badge text and filled-button containers (see ToolBubble.kt:541,
// CronJobsScreen.kt:263, AchievementsScreen.kt:326/672, SourceBadge.kt,
// ContextUsageChip.kt:110, GatewayScreen.kt:230, ProfileBuilderView.kt:398).
// So the web surface token goes to the `…Container` slot and the web
// foreground token goes to the bare accent slot — matching how every
// other preset here assigns a bright accent to `secondary`/`tertiary`
// and a dark tone to `secondaryContainer`/`tertiaryContainer`.
//
//   background              → background
//   foreground              → onBackground / onSurface / primary
//   card                    → surface
//   cardForeground          → onSurface
//   muted                   → surfaceVariant
//   mutedForeground         → onSurfaceVariant / outline / info
//   popover                 → surfaceContainer
//   popoverForeground       → onSurface (shared with cardForeground)
//   primary                 → primary (= foreground)
//   primaryForeground       → onPrimary (= background)
//   secondary               → secondaryContainer
//   secondaryForeground     → secondary / onSecondaryContainer
//   accent                  → tertiaryContainer
//   accentForeground        → tertiary / onTertiaryContainer
//   border / input          → outlineVariant + errorContainer
//   ring / midground        → primary (same neon green)
//   destructive             → error / onErrorContainer
//   destructiveForeground   → onError
//   sidebarBackground       → surfaceContainerLowest
//   sidebarBorder           → surfaceContainerHighest / inversePrimary
//   userBubble              → primaryContainer / surfaceContainerHigh
//   userBubbleBorder        → (unused — see note below)
//
//   Notes on the tokens with no direct Material analogue:
//   - `border` (#003000) is a hairline colour: it only reaches 1.36:1
//     against the background, below DESIGN.md's 3:1 bar for meaningful
//     control boundaries, so it is used as `outlineVariant` (decorative
//     dividers) and `outline` takes `mutedForeground` (#1a8a30, 4.53:1)
//     instead. `outline` is painted as badge text in SourceBadge.kt.
//   - `userBubbleBorder` (#004800) is dropped: Compose draws the user
//     bubble as a solid `primary` fill with no stroke (ChatBubble.kt),
//     so there is no border slot to carry it, and as an `errorContainer`
//     it would put `onErrorContainer` at 2.76:1 — under the 3:1 gate.
//   - `ring`, `midground` collapse onto `primary` — all three are the
//     same neon green in the source palette.
//   - Status colours (success/warning/info) are drawn from the
//     green-family tones since the palette has no yellow/orange —
//     consumers MUST pair status indicators with icons or labels.
// ---------------------------------------------------------------------

private val CyberpunkBg = Color(0xFF000A00) // background, primaryForeground, destructiveForeground
private val CyberpunkFg = Color(0xFF00FF41) // foreground, primary, ring, midground
private val CyberpunkCard = Color(0xFF001200) // card
private val CyberpunkMuted = Color(0xFF001A00) // muted
private val CyberpunkMutedFg = Color(0xFF1A8A30) // mutedForeground — the only readable mid-tone
private val CyberpunkPopover = Color(0xFF001000) // popover
private val CyberpunkSecondary = Color(0xFF002800) // secondary (a surface, not an ink)
private val CyberpunkSecondaryFg = Color(0xFF00CC34) // secondaryForeground
private val CyberpunkAccent = Color(0xFF002000) // accent (a surface, not an ink)
private val CyberpunkAccentFg = Color(0xFF00E038) // accentForeground
private val CyberpunkBorder = Color(0xFF003000) // border, input — hairline only
private val CyberpunkDestructive = Color(0xFFFF003C) // destructive
private val CyberpunkSidebarBg = Color(0xFF000600) // sidebarBackground
private val CyberpunkSidebarBorder = Color(0xFF001800) // sidebarBorder
private val CyberpunkUserBubble = Color(0xFF001400) // userBubble

/**
 * Cyberpunk theme — dark-only neon green on near-black.
 *
 * All surfaces are deep green-blacks; primary text and accents
 * are a bright neon green (#00FF41). Light mode is not shipped
 * (a light cyberpunk palette is an oxymoron) — the dispatcher
 * falls back to the default theme in light mode.
 *
 * Accessibility note: success (#00CC34), warning (#00E038), and
 * info (#1A8A30) are all variants of green — do NOT rely on
 * colour alone to differentiate them; pair with an icon or label.
 * Error (#FF003C) is the only non-green status, which aids
 * identification but still should not be conveyed by colour alone.
 */
val CyberpunkTheme =
    buildThemeDarkOnly(
        dark =
            PaletteColors(
                primary = CyberpunkFg,
                onPrimary = CyberpunkBg,
                primaryContainer = CyberpunkUserBubble,
                onPrimaryContainer = CyberpunkFg,
                secondary = CyberpunkSecondaryFg,
                onSecondary = CyberpunkBg,
                secondaryContainer = CyberpunkSecondary,
                onSecondaryContainer = CyberpunkSecondaryFg,
                tertiary = CyberpunkAccentFg,
                onTertiary = CyberpunkBg,
                tertiaryContainer = CyberpunkAccent,
                onTertiaryContainer = CyberpunkAccentFg,
                background = CyberpunkBg,
                onBackground = CyberpunkFg,
                surface = CyberpunkCard,
                onSurface = CyberpunkFg,
                surfaceVariant = CyberpunkMuted,
                onSurfaceVariant = CyberpunkMutedFg,
                surfaceContainerLowest = CyberpunkSidebarBg,
                surfaceContainerLow = CyberpunkBg,
                surfaceContainer = CyberpunkPopover,
                surfaceContainerHigh = CyberpunkUserBubble,
                surfaceContainerHighest = CyberpunkSidebarBorder,
                inverseSurface = CyberpunkFg,
                inverseOnSurface = CyberpunkBg,
                inversePrimary = CyberpunkSidebarBorder,
                outline = CyberpunkMutedFg,
                outlineVariant = CyberpunkBorder,
                scrim = CyberpunkBg,
                status =
                    HermesStatusColors(
                        success = CyberpunkSecondaryFg,
                        successContainer = CyberpunkSecondary,
                        onSuccess = CyberpunkBg,
                        warning = CyberpunkAccentFg,
                        warningContainer = CyberpunkAccent,
                        onWarning = CyberpunkBg,
                        error = CyberpunkDestructive,
                        errorContainer = CyberpunkBorder,
                        onError = CyberpunkBg,
                        onErrorContainer = CyberpunkDestructive,
                        info = CyberpunkMutedFg,
                        infoContainer = CyberpunkMuted,
                        onInfo = CyberpunkBg,
                    ),
            ),
    )
