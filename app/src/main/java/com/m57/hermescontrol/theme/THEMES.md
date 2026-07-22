# Themes

How theming works in Hermes Control, and how to add or edit a theme.

## Where themes live

All theme code is under:

```
app/src/main/java/com/m57/hermescontrol/theme/
├── Theme.kt                       # dispatcher — resolves scheme + status colors per preset
├── Color.kt                       # brand design tokens (DarkSurface, HermesPurple, StatusGreen, …)
├── HermesStatusColors.kt          # semantic status color model (success/warning/error/info + on*)
├── Type.kt / Shapes.kt            # typography + shape tokens
├── Spacing.kt / Motion.kt         # spacing + motion tokens
└── presets/                       # one file per ThemePreset
    ├── DefaultScheme.kt
    ├── MonochromeScheme.kt
    ├── GruvboxScheme.kt
    ├── CatppuccinScheme.kt
    ├── AmoledScheme.kt
    └── NeonNoirScheme.kt
```

## The 4-token shape

Every file in `presets/` is uniform — it exports the same four tokens:

| Token | Type | Purpose |
|-------|------|---------|
| `XxxDarkColorScheme` | `ColorScheme` | dark-mode Material 3 scheme |
| `XxxLightColorScheme` | `ColorScheme` | light-mode Material 3 scheme |
| `XxxDarkStatusColors` | `HermesStatusColors` | dark semantic status colors |
| `XxxLightStatusColors` | `HermesStatusColors` | light semantic status colors |

A preset with no bespoke status set (AMOLED) aliases the defaults:

```kotlin
val AmoledDarkStatusColors = DefaultDarkStatusColors
val AmoledLightStatusColors = DefaultLightStatusColors
```

AMOLED has no bespoke light scheme, so it aliases the default light scheme too:

```kotlin
val AmoledLightColorScheme = DefaultLightColorScheme
```

## The dispatcher

`Theme.kt` is a **pure lookup** — no special-casing. Given a `ThemePreset`
and a dark flag, it returns the matching scheme/status from the preset file:

```kotlin
private fun resolveColorScheme(preset: ThemePreset, darkTheme: Boolean) = when (preset) {
    ThemePreset.DEFAULT -> if (darkTheme) DefaultDarkColorScheme else DefaultLightColorScheme
    ThemePreset.MONOCHROME -> if (darkTheme) MonochromeDarkColorScheme else MonochromeLightColorScheme
    // …one line per preset…
}

private fun resolveStatusColors(preset: ThemePreset, darkTheme: Boolean) = when (preset) {
    ThemePreset.DEFAULT -> if (darkTheme) DefaultDarkStatusColors else DefaultLightStatusColors
    // …one line per preset…
}
```

Dynamic (Material You) color on API 31+ overrides the preset scheme when
`useDynamicColors = true`.

## DEFAULT uses design tokens — that's intentional

`DefaultScheme.kt` imports ~47 named tokens from `Color.kt`
(`DarkBackground`, `HermesPurple`, `StatusGreenContainer`, …) and builds its
schemes from them. This is **by design**: DEFAULT is the brand reference theme,
so a palette change in `Color.kt` flows straight through.

The other presets are hand-authored community palettes and use **raw hex**
(`Color(0xFFFE8019)`), so they only import `Color` (+ `HermesStatusColors`).
Do **not** "fix" DEFAULT to raw hex to match them — that would break the token
linkage. The asymmetry is semantic, not sloppy.

## Adding a new theme

1. **Create** `presets/<Name>Scheme.kt` exporting the 4 tokens:
   ```kotlin
   package com.m57.hermescontrol.theme.presets

   import androidx.compose.material3.darkColorScheme
   import androidx.compose.material3.lightColorScheme
   import androidx.compose.ui.graphics.Color
   import com.m57.hermescontrol.theme.HermesStatusColors

   val MyThemeDarkColorScheme = darkColorScheme(/* … */)
   val MyThemeLightColorScheme = lightColorScheme(/* … */)
   val MyThemeDarkStatusColors = HermesStatusColors(/* … */)
   val MyThemeLightStatusColors = HermesStatusColors(/* … */)
   ```
2. **Add 2 lines** to each `when` in `Theme.kt` (`resolveColorScheme` +
   `resolveStatusColors`), importing the new tokens at the top of the file.
3. **Add the enum entry** `MY_THEME` to `ThemePreset` in `Theme.kt`.
4. **Wire the UI**: add the label + selection in
   `ui/settings/components/AppearanceSection.kt` (and any string resource).
5. **Verify**:
   ```bash
   ./ktlint --format app/src/main/java/com/m57/hermescontrol/theme/presets/<Name>Scheme.kt
   ./gradlew compileDebugKotlin
   ```

## Conventions

- ktlint 1.2.1 is enforced in CI. Run `./ktlint --format` before committing.
- Import order is ASCII-lexicographic (uppercase before lowercase).
- Every change goes through a PR — never push directly to `main`.
