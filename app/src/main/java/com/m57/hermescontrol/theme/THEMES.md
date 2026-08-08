# Themes

How theming works in Hermes Control, and how to add or edit a theme.

## Where themes live

All theme code is under:

```
app/src/main/java/com/m57/hermescontrol/theme/
├── Theme.kt                       # dispatcher — preset lookup + mode fallback
├── PaletteTemplate.kt             # the template — the ONE shape every theme follows
├── Color.kt                       # shared tokens for non-theme code (status fallbacks, code blocks, …)
├── HermesStatusColors.kt          # semantic status color model (success/warning/error/info + on*)
├── Type.kt / Shapes.kt            # typography + shape tokens
├── Spacing.kt / Motion.kt         # spacing + motion tokens
└── presets/                       # one file per ThemePreset — same skeleton everywhere
    ├── DefaultScheme.kt
    ├── MonochromeScheme.kt
    ├── GruvboxScheme.kt
    ├── CatppuccinScheme.kt
    ├── AmoledScheme.kt            # dark-only (ThemeMode.DARK_ONLY)
    └── NordScheme.kt
```

## The template — one shape for every theme

Every preset file is a **single `ThemePalette`** built from raw color specs via
the template in `PaletteTemplate.kt`. A theme is a pure color spec: fill in
`PaletteColors` (the Material slots) + the semantic status set
(`HermesStatusColors`) and the app does the rest. No per-preset behavior, no
aliases, no shared-token imports.

```kotlin
val MyTheme =
    buildTheme(
        dark =
            PaletteColors(
                primary = Color(0xFF…),
                // …every slot…
                status =
                    HermesStatusColors(
                        success = Color(0xFF…),
                        // …
                    ),
            ),
        light = PaletteColors(/* … */),
    )
```

The template maps `PaletteColors` into the Material 3 `ColorScheme` slots. The
Material `error` slots (`error`, `onError`, `errorContainer`, `onErrorContainer`)
are **derived** from the theme's status set — the theme author defines semantic
colors once.

## Preset conventions

- **Named swatches** for any hex reused across slots (Gruvbox bg ladder,
  Catppuccin's official names, Nord's nord0–nord15, Monochrome's mono ramp).
  A theme with repeated hexes should define them once at the top of the file.
- **Bright/faded accent split** (Gruvbox, Slate): bright variants against dark
  surfaces, muted "faded" variants against light surfaces so contrast holds
  without neon saturation. Single-accent-set themes (Nord) instead keep dark
  "on" text in both modes — pastel accents stay light, so Snow-Storm-style
  light text would fail contrast.
- **Grayscale status colors** (Monochrome, AMOLED) separate success/warning/
  error/info by lightness only — consumers must pair with icons or labels.
- Every shipped mode's error slot pairs are enforced at **>= 3:1 contrast** by
  `ThemePaletteTest` — a preset that breaks this fails the unit-test gate.

## Theme modes

A theme declares which modes it ships via the factory you build it with:

| Factory | Mode | Meaning |
|---------|------|---------|
| `buildTheme(...)` | `FULL` | bespoke dark + light palettes |
| `buildThemeDarkOnly(...)` | `DARK_ONLY` | dark only; light mode falls back to the default theme (AMOLED) |
| `buildThemeLightOnly(...)` | `LIGHT_ONLY` | light only; dark mode falls back to the default theme |

The dispatcher falls back to the brand **default** theme for any mode a preset
doesn't ship — never to a sibling preset.

## The dispatcher

`Theme.kt` is a **pure lookup** — no special-casing. Given a `ThemePreset` and
a dark flag, `themeFor()` returns the preset's `ThemePalette`, then the scheme /
status are read off it (with the mode fallback above):

```kotlin
private fun themeFor(preset: ThemePreset): ThemePalette = when (preset) {
    ThemePreset.DEFAULT -> DefaultTheme
    ThemePreset.MONOCHROME -> MonochromeTheme
    // …one line per preset…
}
```

Dynamic (Material You) color on API 31+ overrides the preset scheme when
`useDynamicColors = true`. Semantic status colors are always resolved from the
active preset via `LocalHermesStatusColors`.

## Adding a new theme

1. **Create** `presets/<Name>Scheme.kt` as a template fill (see above) —
   `buildTheme` for a full theme, `buildThemeDarkOnly` / `buildThemeLightOnly`
   for single-mode themes. Every status color must be bespoke — no aliasing.
2. **Add 1 line** to `themeFor()` in `Theme.kt` + import the new val.
3. **Add the enum entry** `MY_THEME` to `ThemePreset` in `Theme.kt`.
4. **Wire the UI**: add the label + selection in
   `ui/settings/components/AppearanceSection.kt` (and any string resource).
5. **Verify**:
   ```bash
   ./gradlew ktlintCheck testDebugUnitTest
   ```
   `ThemePaletteTest` asserts >= 3:1 contrast on every shipped mode's error
   slot pairs and the ThemeMode invariants — it must stay green.

## Conventions

- ktlint 1.2.1 is enforced in CI. Run `./gradlew ktlintCheck` before committing
  (the gradle task is the gate, not the standalone `ktlint` binary).
- Import order is ASCII-lexicographic (uppercase before lowercase).
- Every change goes through a PR — never push directly to `main`.
