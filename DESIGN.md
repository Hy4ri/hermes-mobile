# Hermes Mobile Design System

## Scope and sources of truth

This document defines visual and interaction requirements for new or changed UI.
It is not an architecture manual or a claim that every existing screen already
meets every requirement. Flag existing deviations when touching the relevant UI;
do not silently redesign unrelated screens.

- [README.md](README.md): installation, connection, and product overview.
- [CONTRIBUTING.md](CONTRIBUTING.md): contribution workflow and verification.
- [AGENTS.md](AGENTS.md): operational rules and architecture conventions.
- [THEMES.md](app/src/main/java/com/m57/hermescontrol/theme/THEMES.md): theme implementation and extension guide.

Kotlin sources are authoritative for implemented token values and component APIs.
Use this document for intent; investigate and report conflicts rather than assuming
that either stale prose or an existing deviation grants permission to change behavior.

## Token source map

Use semantic Kotlin tokens, not copied hex values or a parallel web/CSS token set.
Android layout dimensions use `dp`; text sizes, line heights, and tracking use `sp`.

| Concern | Authoritative source |
| --- | --- |
| Presets and mode selection | [Theme.kt](app/src/main/java/com/m57/hermescontrol/theme/Theme.kt) |
| Material slot mapping | [PaletteTemplate.kt](app/src/main/java/com/m57/hermescontrol/theme/PaletteTemplate.kt) |
| Palette values | [presets/](app/src/main/java/com/m57/hermescontrol/theme/presets/) |
| Semantic status model | [HermesStatusColors.kt](app/src/main/java/com/m57/hermescontrol/theme/HermesStatusColors.kt) |
| Terminal, code, and diff colors | [Color.kt](app/src/main/java/com/m57/hermescontrol/theme/Color.kt) |
| Typography | [Type.kt](app/src/main/java/com/m57/hermescontrol/theme/Type.kt) |
| Spacing and shapes | [Spacing.kt](app/src/main/java/com/m57/hermescontrol/theme/Spacing.kt), [Shapes.kt](app/src/main/java/com/m57/hermescontrol/theme/Shapes.kt) |
| Motion | [Motion.kt](app/src/main/java/com/m57/hermescontrol/theme/Motion.kt) |

## Overview
Hermes Mobile (`com.m57.hermescontrol`) is a native Android client for the Hermes Agent platform. Prioritize useful information density, clear touch interactions, and timely, honest feedback over decorative visual noise. Density must not compromise accessibility.

The visual language follows Material 3 guidelines strictly adapted for developer utilities:
- **Tone**: Technical, focused, utilitarian, crisp.
- **Form Factors**: Verify changed layouts at narrow phone widths and larger window sizes; Navigation 3 alone does not establish adaptive-layout support.
- **Core Principle**: Zero fluff. Fast render cycles, explicit states, flat surfaces, and zero visual ambiguity.

## Colors
The app supports 6 built-in presets (`Default/Slate`, `Monochrome`, `Gruvbox`, `Catppuccin`, `AMOLED`, `Nord`), plus optional Android 12+ wallpaper theming. `Theme.kt` selects the preset; `PaletteTemplate.kt` maps its colors to Material slots. AMOLED is dark-only; light mode falls back to Default.

1. **Surfaces**:
   - `MaterialTheme.colorScheme.background`: Main canvas and full-bleed chat background.
   - `surface` and `surfaceContainer*`: Use the existing component's semantic surface tier for bars, panels, cards, and sheets.
   - Pair surfaces with their corresponding foreground tokens; do not assume Default Dark values apply to light or dynamic themes.
2. **Accents & Tints**:
   - Primary accent is reserved for interactive controls, user bubbles, selected drawer icons, and active tabs.
   - Selected navigation drawer items MUST tint icons directly to `primary` to prevent low-contrast grey-on-grey visual loss.
3. **Semantic Status Colors (`HermesStatusColors`)**:
   - Access semantic status colors through `LocalHermesStatusColors.current`, including when dynamic Material colors are active. Do not substitute Material accent colors for success/warning/error/info.
   - Pair status colors with icons or labels; color alone is insufficient, especially in Monochrome and AMOLED.
   - Every preset must explicitly define `onErrorContainer`. Apply the accessibility contrast requirements below to the actual foreground/background pair.

## Typography
- **UI Fonts**: `FontFamily.Default`, using Material typography roles. Do not assume a particular system font on every device.
- **Code & Logs**: `FontFamily.Monospace`; do not promise JetBrains Mono or another named face without a bundled font and explicit wiring.
- **Scale Discipline (`Type.kt`)**:
  - Screen titles: `20sp` (`title-large`) with 0.1sp tracking.
  - Card headings: `16sp` (`title-medium`) with 0.15sp tracking.
  - Chat content: `16sp` (`body-large`) with 24sp line-height for readability.
  - Metadata & Subtitles: `14sp` (`body-medium`) or `12sp` (`body-small` / `label-medium`).
  - Hero stats & Display numbers: `44sp` (`display-large`) down to `30sp` (`display-small`).

## Layout
- **Scaffolding (`HermesScaffold`)**:
  - Use the shared scaffold's top bar and supported action slots; do not duplicate its insets or hardcode a parallel top-bar height.
  - **No Floating Action Buttons (FABs)**: Primary triggers live in the top-bar actions or fixed bottom composer docks.
  - **Scaffold Padding Rule**: Do not reapply the supplied `paddingValues` inside the content lambda — the scaffold already handles insets. This includes loading, error, and empty branches. Content-specific spacing is separate from scaffold insets.
- **Spacing Scale (`Spacing.kt`)**:
  - `xs: 2dp`: Hairline gaps, micro dividers.
  - `sm: 6dp`: Default inner padding, icon-to-text gap.
  - `md: 12dp`: Card content, screen edge, section gaps.
  - `lg: 18dp`: Hero spacing, between major sections.
  - `xl: 24dp`: Full-feature spacing, large section break.
  - `xxl: 36dp`: Maximum outer margin.
- **Controls & Navigation**:
  - Horizontal chip bars must be single-row with `horizontalScroll()`. Never stack chips vertically into multi-row wraps.
  - Dropdown pickers take precedence over cycle buttons to ensure all options are discoverable in one tap.

## Elevation & Depth
- **Elevation Language**: Surface luminance layering (`surfaceContainer` tiers) and restrained `dp` borders (`outline` / `outlineVariant`) replace heavy drop shadows.
- **Flat Surface Rule**:
  - Chat background is flat `colorScheme.background`.
  - User message bubbles use solid `colorScheme.primary` (no multi-color gradients).
  - Shimmer effects (`StateViews.kt`) are used strictly as animated loading states, never as static background decorations.

## Shapes
- **Corner Radii Hierarchy (`Shapes.kt`)**:
  - `MaterialTheme.shapes.extraSmall`: `4dp`.
  - `MaterialTheme.shapes.small`: `8dp`.
  - `MaterialTheme.shapes.medium`: `12dp`.
  - `MaterialTheme.shapes.large`: `16dp`.
  - `MaterialTheme.shapes.extraLarge`: `28dp`.
  - Use the existing component's shape contract. Square and circular shapes are component choices, not additional `HermesShapes` tokens.

## Components
- **Top App Bar**: Flat surface; reuse `HermesScaffold` and its supported slots instead of duplicating dimensions or controls.
- **Chat Timeline**:
  - **User Bubbles**: Right-aligned, solid primary fill, high-contrast text (`onPrimary`).
  - **Assistant Content**: Preserve the active renderer's layout. Full-bleed prose renders on the chat background; do not force every assistant message into a filled card. Reuse the existing Markdown renderer.
  - **Tool Bubbles**: Expandable rows with tool vector icon, execution state indicator, parameter summary, and full payload bottom sheet.
- **Action & Filter Chips**:
  - Must represent real system state (e.g., active model, selected profile, filter criteria).
  - Active chips: `primaryContainer` fill with `onPrimaryContainer` text/icon.
  - Inactive chips: Use the component's Material surface and foreground tokens.
  - Disabled controls: Use the component's disabled styling and semantics; do not treat a single alpha as a universal contract.
- **Dialogs & Bottom Sheets**:
  - Standard CRUD flows must provide all 4 operations (Create, Read, Update, Delete) with confirmation gates for destructive actions.
  - Every managed resource must have a discoverable navigation surface in the UI.

## Do's and Don'ts

### Do's
- **DO** use vector icons for app-owned controls and status indicators, reusing existing Material icons or project vector assets.
- **DO** use `Icons.AutoMirrored.Filled.*` for directional icons (`ArrowBack`, `Send`, `VolumeUp`, `MenuBook`).
- **DO** verify contrast for the actual foreground/background pairs under the accessibility requirements below.
- **DO** use Android string `<plurals>` for count labels (`1 agent` vs `2 agents`).
- **DO** grey out disabled controls (`enabled = false`) visibly.
- **DO** ensure every created resource has a discoverable UI list or navigation tab.

### Don'ts
- **DON'T** use emoji glyphs as app-owned status icons or controls; use vectors. This does not prohibit emoji in user/assistant messages or other user-authored content.
- **DON'T** use Floating Action Buttons (FABs); anchor actions in top bars or fixed docks.
- **DON'T** apply `Modifier.padding(paddingValues)` inside `HermesScaffold` content lambdas.
- **DON'T** use gradient backgrounds on chat surfaces or message bubbles.
- **DON'T** create multi-layer nested cards or unnecessary decorative borders.
- **DON'T** use cycle buttons where a dropdown menu provides immediate option visibility.

## Accessibility and verification requirements

These are requirements for UI work, not a declaration that the entire app has
passed an accessibility audit.

- **Touch targets**: Interactive targets must be at least `48dp` in each dimension. A smaller visible icon is fine when its actual hit target meets this requirement.
- **Text scaling**: Use `sp` and semantic typography. Exercise larger system font/display sizes; essential labels, values, and actions must remain readable and reachable rather than clipped into fixed-height containers.
- **TalkBack**: Give meaningful images and icon-only actions localized labels. Decorative icons use `contentDescription = null`; avoid announcing the same label twice. Verify focus order and state announcements in the changed flow.
- **RTL**: Use start/end spacing and auto-mirrored directional icons where appropriate. Check Arabic layout, mixed-direction text, and action order; do not mirror non-directional icons arbitrarily.
- **Contrast**: Target at least `4.5:1` for normal text, `3:1` for large text, and `3:1` for meaningful non-text indicators/control boundaries against adjacent colors. This is not a blanket requirement that every decorative container contrast with every other container by `3:1`.
- **Themes**: Check relevant light/dark presets and dynamic colors. Do not rely on color alone to convey selection, failure, or success.
- **Narrow layouts**: Check long localized labels, keyboard-open states, and scrolling at small widths; density must not hide essential actions.

[ThemePaletteTest.kt](app/src/test/java/com/m57/hermescontrol/theme/ThemePaletteTest.kt)
currently guards error slot pairs, full-bleed prose/header contrast, and theme-mode
invariants. Those tests are useful but do not establish contrast or accessibility
compliance for every rendered component, alpha blend, or dynamic palette.
Record device/emulator steps and observed results for changed UI; explicitly name
any gate that could not be verified.
