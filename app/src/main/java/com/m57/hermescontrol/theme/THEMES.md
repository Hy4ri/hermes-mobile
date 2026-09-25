# Themes

How theming works in Hermes Mobile, and how to add or edit a theme.

This is the implementation guide. [DESIGN.md](../../../../../../../../DESIGN.md)
defines visual and interaction requirements; the Kotlin sources define the actual
tokens. When changing tokens or behavior, update the relevant documentation too.

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

Dynamic (Material You) color on API 31+ can optionally override the preset scheme when
`useDynamicColors = true` (defaults to `false`). Semantic status colors are always resolved from the
active preset via `LocalHermesStatusColors`.

## Adding a new theme (preset)

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

## Adding a marketplace theme (dynamic)

Instead of adding a hardcoded preset, marketplace themes are applied at
runtime via the theme apply pipeline:

1. The `ThemeMarketplaceViewModel` fetches the catalog from `GET /api/dashboard/themes`.
2. Each `ThemeMarketplaceEntry` may contain a `definition` map with parsed color tokens
   and a `downloadUrl` for the `.vsix` package.
3. `ThemeApplier.applyCustomTheme(name, palette)` stores the `ThemePalette`.
4. `ThemePreset.CUSTOM` is selected in the UI (via `AppearanceSection`).
5. `Theme.kt.themeFor(CUSTOM)` reads `customPalette` and applies it.

The pipeline lives under `data/theme/import/`:
- `VsixThemeParser` — downloads and parses `.vsix` ZIP archives
- `ThemeDefinitionConverter` — maps VS Code token names to `PaletteColors`
- `ThemeApplier` — runtime management of the custom theme

## Conventions

- ktlint 1.8.0 is enforced in CI. Run `./gradlew ktlintCheck` before committing
  (the gradle task is the gate, not the standalone `ktlint` binary).
- Import order is ASCII-lexicographic (uppercase before lowercase).
- Every change goes through a PR targeting `dev` — never push directly to `main` or `dev`.
