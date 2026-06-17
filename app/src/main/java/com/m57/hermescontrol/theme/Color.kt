package com.m57.hermescontrol.theme

import androidx.compose.ui.graphics.Color

/**
 * Hermes Control brand palette — v2 (modern redesign).
 *
 * Design intent:
 *  - Primary (voltage purple) is the single accent; surfaces stay calm.
 *  - Secondary (plasma amber) is reserved for opt-in highlights only.
 *  - Surfaces use a 3-step elevation ladder with a slight blue tint so dark
 *    mode no longer reads as flat black.
 *  - Semantic colors (success/warning/error/info) share the same saturation
 *    band so they don't fight the brand.
 */

val HermesPurple = Color(0xFF7C5CFF)
val HermesPurpleLight = Color(0xFFAC93FF)
val HermesPurpleDark = Color(0xFF5A3FE0)
val HermesPurpleContainer = Color(0xFF2B2159)
val HermesPurpleOnContainer = Color(0xFFD9CCFF)

val HermesAmber = Color(0xFFFFB627)
val HermesAmberLight = Color(0xFFFFE082)
val HermesAmberDark = Color(0xFFC68400)

val DarkBackground = Color(0xFF0B0B11)
val DarkSurface = Color(0xFF121218)
val DarkSurfaceVariant = Color(0xFF1C1C26)
val DarkSurfaceHigh = Color(0xFF262633)
val DarkOnSurface = Color(0xFFE8E6EE)
val DarkOnSurfaceVariant = Color(0xFFB6B2C4)
val DarkOutline = Color(0xFF4A4A5A)
val DarkOutlineVariant = Color(0xFF2F2F3D)

val LightBackground = Color(0xFFF7F6FB)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEFEDF4)
val LightSurfaceHigh = Color(0xFFE6E3EE)
val LightOnSurface = Color(0xFF1A1A24)
val LightOnSurfaceVariant = Color(0xFF56536A)
val LightOutline = Color(0xFF79748E)
val LightOutlineVariant = Color(0xFFC9C5D6)

val StatusGreen = Color(0xFF3DDC84)
val StatusGreenContainer = Color(0xFF143A23)
val StatusRed = Color(0xFFFF5C5C)
val StatusRedContainer = Color(0xFF3D1414)
val StatusYellow = Color(0xFFFFB627)
val StatusYellowContainer = Color(0xFF3D2F0F)
val StatusBlue = Color(0xFF4DA8FF)
val StatusBlueContainer = Color(0xFF0F2A3D)

val UserBubble = HermesPurple
val UserBubbleDark = HermesPurpleDark
val AssistantBubble = Color(0xFF1C1C26)
val AssistantBubbleLight = Color(0xFFEDEAF4)
val SystemMessageColor = Color(0xFF8B879A)
val ToolChipColor = Color(0xFF262633)
val ToolChipColorLight = Color(0xFFE6E3EE)
