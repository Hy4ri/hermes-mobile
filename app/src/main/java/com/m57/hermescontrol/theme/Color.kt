package com.m57.hermescontrol.theme

import androidx.compose.ui.graphics.Color

// Shared color tokens — only what code OUTSIDE the theme presets consumes.
// The preset palettes themselves are self-contained template fills in
// presets/ (see PaletteTemplate.kt); they don't import from here.

// Semantic status colors (always brand-defined).
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

// Chat-specific fallback tokens (default theme surface text colors).
val DarkOnSurface = Color(0xFFE8E6EE)
val LightOnSurface = Color(0xFF1A1A24)

// Syntax highlighting tokens (code blocks) — VS Code Dark+ palette — good
// general readability across all presets. Consumed by CodeBlockCard.
val CodeKeyword = Color(0xFF569CD6) // blue — for val, fun, class, etc.
val CodeString = Color(0xFFCE9178) // orange — for "string literals"
val CodeComment = Color(0xFF6A9955) // green — for // comments
val CodeNumber = Color(0xFFB5CEA8) // light green — for 42, 0xFF
val CodePunctuation = Color(0xFFD4D4D4) // gray — for {, (, ;, etc.

// Code block / terminal surface colors (issue #659).
val CodeTerminalBg = Color(0xFF1E1E1E)
val CodeTerminalBorder = Color(0xFF333333)
val CodeTerminalText = Color(0xFFD4D4D4)
val CodeTerminalMuted = Color(0xFF808080)

// Diff viewer tokens (issue #738).
val CodeDiffAddBg = Color(0x263DDC84)
val CodeDiffAddText = Color(0xFF3DDC84)
val CodeDiffDeleteBg = Color(0x26FF5C5C)
val CodeDiffDeleteText = Color(0xFFFF5C5C)
val CodeDiffHunkBg = Color(0x264DA8FF)
val CodeDiffHunkText = Color(0xFF4DA8FF)

// Custom vector icons — default fill for hand-declared ImageVector icons
// (e.g. NeurologyIcon). Mirrors Material's own icon set: vectors are drawn
// opaque black and Icon()'s tint (LocalContentColor) replaces the RGB at
// draw time — only the alpha channel survives, so this must stay opaque.
val IconDefaultFill = Color.Black
