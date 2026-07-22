package com.m57.hermescontrol.theme

import androidx.compose.ui.graphics.Color

// Hermes Mobile brand palette — v3 (Material You rewrite).
//
// On Android 12+ (API 31+) the app uses dynamic colour derived from the user's
// wallpaper — this palette is NOT used for primary/surface roles on those
// devices (see HermesControlTheme). On API 26–30 it serves as the full colour
// scheme. Semantic status colours and chat-bubble colours are ALWAYS
// brand-defined. All pairs verified for WCAG AA contrast (4.5:1 text, 3:1
// large text / icons).

// ── Brand: Voltage Purple (primary) ──────────────────────────────────────

val HermesPurple = Color(0xFF7C5CFF)
val HermesPurpleLight = Color(0xFFAC93FF)
val HermesPurpleDark = Color(0xFF5A3FE0)
val HermesPurpleContainer = Color(0xFF2B2159)
val HermesPurpleOnContainer = Color(0xFFD9CCFF)

// ── Brand: Plasma Amber (secondary) ─────────────────────────────────────

val HermesAmber = Color(0xFFFFB627)
val HermesAmberLight = Color(0xFFFFE082)
val HermesAmberDark = Color(0xFFC68400)

// ── Dark surfaces (5-step elevation ladder) ─────────────────────────────

val DarkBackground = Color(0xFF0B0B11)
val DarkSurface = Color(0xFF121218)
val DarkSurfaceVariant = Color(0xFF1C1C26)
val DarkSurfaceContainerLowest = Color(0xFF07070C)
val DarkSurfaceContainerLow = Color(0xFF0F0F14)
val DarkSurfaceContainer = Color(0xFF1C1C26)
val DarkSurfaceContainerHigh = Color(0xFF262633)
val DarkSurfaceContainerHighest = Color(0xFF30303D)
val DarkOnSurface = Color(0xFFE8E6EE)
val DarkOnSurfaceVariant = Color(0xFFB6B2C4)
val DarkOutline = Color(0xFF4A4A5A)
val DarkOutlineVariant = Color(0xFF2F2F3D)
val DarkInverseSurface = Color(0xFFE8E6EE)
val DarkInverseOnSurface = Color(0xFF1A1A24)
val DarkScrim = Color(0xFF000000)

// ── Light surfaces (5-step elevation ladder) ────────────────────────────

val LightBackground = Color(0xFFF7F6FB)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEFEDF4)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF3F1F8)
val LightSurfaceContainer = Color(0xFFEFEDF4)
val LightSurfaceContainerHigh = Color(0xFFE6E3EE)
val LightSurfaceContainerHighest = Color(0xFFDDD9EA)
val LightOnSurface = Color(0xFF1A1A24)
val LightOnSurfaceVariant = Color(0xFF56536A)
val LightOutline = Color(0xFF79748E)
val LightOutlineVariant = Color(0xFFC9C5D6)
val LightInverseSurface = Color(0xFF1A1A24)
val LightInverseOnSurface = Color(0xFFE8E6EE)
val LightScrim = Color(0xFF000000)

// ── Semantic status colours (always brand-defined) ──────────────────────

val StatusGreen = Color(0xFF3DDC84)
val StatusGreenContainer = Color(0xFF143A23)
val StatusRed = Color(0xFFFF5C5C)
val StatusRedContainer = Color(0xFF3D1414)
val StatusYellow = Color(0xFFFFB627)
val StatusYellowContainer = Color(0xFF3D2F0F)
val StatusBlue = Color(0xFF4DA8FF)
val StatusBlueContainer = Color(0xFF0F2A3D)
val StatusGrey = Color(0xFF9E9E9E)
val StatusGreyDark = Color(0xFF1A1A24)
val StatusGreyLight = Color(0xFFF5F5F5)

// ── Chat-specific colours (always brand-defined for consistency) ───────

val AssistantBubble = Color(0xFF1C1C26)
val AssistantBubbleLight = Color(0xFFEDEAF4)
val SystemMessageColor = Color(0xFF8B879A)
val ToolChipColor = Color(0xFF262633)
val ToolChipColorLight = Color(0xFFE6E3EE)

// ── Syntax highlighting tokens (code blocks) ─────────────────────────────
// VS Code Dark+ palette — good general readability across all presets.
// These are consumed exclusively by CodeBlockCard (MessageCards.kt).

val CodeKeyword = Color(0xFF569CD6) // blue — for val, fun, class, etc.
val CodeString = Color(0xFFCE9178) // orange — for "string literals"
val CodeComment = Color(0xFF6A9955) // green — for // comments
val CodeNumber = Color(0xFFB5CEA8) // light green — for 42, 0xFF
val CodePunctuation = Color(0xFFD4D4D4) // gray — for {, (, ;, etc.

// ── Code block / terminal surface colours (issue #659) ───────────────
val CodeTerminalBg = Color(0xFF1E1E1E)
val CodeTerminalBorder = Color(0xFF333333)
val CodeTerminalText = Color(0xFFD4D4D4)
val CodeTerminalMuted = Color(0xFF808080)
