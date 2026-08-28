---
version: "alpha"
name: "Hermes Mobile Design System"
description: "High-density, minimal Material 3 interface for the Hermes Agent Android client"
omitted: []
colors:
  # Slate Dark Baseline (DefaultScheme.kt)
  bg-root: "#0F1416"
  bg-surface: "#0F1416"
  bg-surface-container-lowest: "#0F1416"
  bg-surface-container-low: "#171C1E"
  bg-surface-container: "#1B2022"
  bg-surface-container-high: "#252B2D"
  bg-surface-container-highest: "#303638"
  bg-surface-variant: "#171C1E"

  # Foreground / Text Tokens
  text-primary: "#DEE3E5"
  text-secondary: "#CEE7EC"
  text-muted: "#BEC8CA"
  text-on-primary: "#0F1416"
  text-on-secondary: "#0F1416"
  text-on-tertiary: "#0F1416"
  text-outline: "#889294"

  # Accents & Containers (Slate Bright Dark Mode)
  primary: "#80D5D2"
  primary-container: "#303638"
  on-primary-container: "#DEE3E5"
  secondary: "#B2CBD0"
  secondary-container: "#252B2D"
  on-secondary-container: "#CEE7EC"
  tertiary: "#A3C9EC"
  tertiary-container: "#303638"
  on-tertiary-container: "#DEE3E5"

  # Semantic Status Tokens (HermesStatusColors.kt & DefaultScheme.kt)
  status-success: "#81D692"
  status-success-container: "#252B2D"
  status-on-success: "#0F1416"
  status-warning: "#ECC248"
  status-warning-container: "#252B2D"
  status-on-warning: "#0F1416"
  status-error: "#FFB4AB"
  status-error-container: "#252B2D"
  status-on-error: "#0F1416"
  status-on-error-container: "#FFB4AB"
  status-info: "#8ECEFF"
  status-info-container: "#252B2D"
  status-on-info: "#0F1416"

  # Code Block & Terminal Tokens (Color.kt)
  terminal-bg: "#1E1E1E"
  terminal-border: "#333333"
  terminal-text: "#D4D4D4"
  terminal-muted: "#808080"
  code-keyword: "#569CD6"
  code-string: "#CE9178"
  code-comment: "#6A9955"
  code-number: "#B5CEA8"

  # Diff Viewer Tokens
  diff-add-bg: "rgba(61, 220, 132, 0.15)"
  diff-add-text: "#3DDC84"
  diff-delete-bg: "rgba(255, 92, 92, 0.15)"
  diff-delete-text: "#FF5C5C"

typography:
  font-sans: "Roboto, 'Segoe UI', system-ui, sans-serif"
  font-mono: "'JetBrains Mono', 'Roboto Mono', monospace"

  display-large:
    fontFamily: "{typography.font-sans}"
    fontSize: "44px"
    fontWeight: 700
    lineHeight: 52px
    letterSpacing: "-0.5px"
  display-medium:
    fontFamily: "{typography.font-sans}"
    fontSize: "36px"
    fontWeight: 700
    lineHeight: 44px
    letterSpacing: "-0.25px"
  display-small:
    fontFamily: "{typography.font-sans}"
    fontSize: "30px"
    fontWeight: 600
    lineHeight: 38px
    letterSpacing: "0px"
  headline-large:
    fontFamily: "{typography.font-sans}"
    fontSize: "28px"
    fontWeight: 600
    lineHeight: 34px
    letterSpacing: "0px"
  headline-medium:
    fontFamily: "{typography.font-sans}"
    fontSize: "24px"
    fontWeight: 600
    lineHeight: 30px
    letterSpacing: "0px"
  headline-small:
    fontFamily: "{typography.font-sans}"
    fontSize: "20px"
    fontWeight: 600
    lineHeight: 26px
    letterSpacing: "0.1px"
  title-large:
    fontFamily: "{typography.font-sans}"
    fontSize: "20px"
    fontWeight: 600
    lineHeight: 26px
    letterSpacing: "0.1px"
  title-medium:
    fontFamily: "{typography.font-sans}"
    fontSize: "16px"
    fontWeight: 500
    lineHeight: 22px
    letterSpacing: "0.15px"
  title-small:
    fontFamily: "{typography.font-sans}"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: 20px
    letterSpacing: "0.1px"
  body-large:
    fontFamily: "{typography.font-sans}"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 24px
    letterSpacing: "0.25px"
  body-medium:
    fontFamily: "{typography.font-sans}"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 20px
    letterSpacing: "0.25px"
  body-small:
    fontFamily: "{typography.font-sans}"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 16px
    letterSpacing: "0.4px"
  label-large:
    fontFamily: "{typography.font-sans}"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: 20px
    letterSpacing: "0.1px"
  label-medium:
    fontFamily: "{typography.font-sans}"
    fontSize: "12px"
    fontWeight: 500
    lineHeight: 16px
    letterSpacing: "0.5px"
  label-small:
    fontFamily: "{typography.font-sans}"
    fontSize: "11px"
    fontWeight: 500
    lineHeight: 16px
    letterSpacing: "0.5px"

rounded:
  none: "0dp"
  extra-small: "4dp"
  small: "8dp"
  medium: "12dp"
  large: "16dp"
  extra-large: "28dp"
  full: "9999dp"

spacing:
  xs: "2dp"
  sm: "6dp"
  md: "12dp"
  lg: "18dp"
  xl: "24dp"
  xxl: "36dp"

components:
  top-app-bar:
    backgroundColor: "{colors.bg-surface}"
    textColor: "{colors.text-primary}"
    height: "56dp"
  chat-user-bubble:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.text-on-primary}"
    rounded: "{rounded.medium}"
    padding: "10dp 14dp"
  chat-assistant-bubble:
    backgroundColor: "{colors.bg-surface-container}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.medium}"
    padding: "10dp 14dp"
  tool-chip:
    backgroundColor: "{colors.bg-surface-container-high}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.full}"
    padding: "4dp 10dp"
  code-card:
    backgroundColor: "{colors.terminal-bg}"
    textColor: "{colors.terminal-text}"
    rounded: "{rounded.small}"
    padding: "8dp 12dp"
---

## Overview
Hermes Mobile (`com.m57.hermescontrol`) is a high-density, keyboard-first, distraction-free Android client for the Hermes Agent platform. The UI philosophy prioritizes information density, low battery drain (OLED-friendly), and instantaneous feedback over decorative visual noise.

The visual language follows Material 3 guidelines strictly adapted for developer utilities:
- **Tone**: Technical, focused, utilitarian, crisp.
- **Form Factors**: Native Android phones, foldables, and tablets via adaptive Navigation 3 layouts.
- **Core Principle**: Zero fluff. Fast render cycles, explicit states, flat surfaces, and zero visual ambiguity.

## Colors
The app supports 6 dynamic presets managed by `PaletteTemplate.kt` (`Default/Slate`, `Monochrome`, `Gruvbox`, `Catppuccin`, `AMOLED`, `Nord`), plus dynamic Android 12+ wallpaper theming:

1. **Surfaces**:
   - `bg-root`: Deep canvas background (`#0F1416` in Default Dark, `#000000` in AMOLED).
   - `bg-surface`: Top app bars, bottom sheets, navigation drawer background.
   - `bg-surface-container`: Assistant message bubbles, session list cards, settings group panels (`#1B2022`).
   - `bg-surface-container-high`: Dialogs, interactive chips, elevated tool surfaces (`#252B2D`).
   - `bg-surface-container-highest`: Selected containers and active pills (`#303638`).
2. **Accents & Tints**:
   - Primary accent (`#80D5D2` Slate Bright Teal) is reserved for interactive controls, user bubbles, selected drawer icons, and active tabs.
   - Selected navigation drawer items MUST tint icons directly to `primary` to prevent low-contrast grey-on-grey visual loss.
3. **Semantic Status Colors (`HermesStatusColors`)**:
   - Status indicators (`success`, `warning`, `error`, `info`) must always satisfy a minimum **3:1 contrast ratio** against their respective container backgrounds (`onErrorContainer` on `errorContainer`).
   - Every theme preset must explicitly define `onErrorContainer` (`#FFB4AB` in Default Dark) to prevent invisible text bugs.

## Typography
- **UI Fonts**: System `Roboto` / sans-serif with strict semantic scaling to prevent layout shifts.
- **Code & Logs**: Tabular `JetBrains Mono` for terminal traces, tool payloads, JSON viewers, and memory tokens.
- **Scale Discipline (`Type.kt`)**:
  - Screen titles: `20sp` (`title-large`) with 0.1sp tracking.
  - Card headings: `16sp` (`title-medium`) with 0.15sp tracking.
  - Chat content: `16sp` (`body-large`) with 24sp line-height for readability.
  - Metadata & Subtitles: `14sp` (`body-medium`) or `12sp` (`body-small` / `label-medium`).
  - Hero stats & Display numbers: `44sp` (`display-large`) down to `30sp` (`display-small`).

## Layout
- **Scaffolding (`HermesScaffold`)**:
  - Global `TopAppBar` containing screen title, connection indicator, profile switch dropdown, and quick action icon buttons.
  - **No Floating Action Buttons (FABs)**: Primary triggers live in the top-bar actions or fixed bottom composer docks.
  - **Scaffold Padding Foot-Gun Rule**: Content lambdas must **NEVER** apply `Modifier.padding(paddingValues)` on root children — the scaffold's internal `Box` already handles top-bar offsets.
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
- **Elevation Language**: Surface luminance layering (`surfaceContainer` tiers) and 1px borders (`outline` / `outlineVariant`) replace heavy drop shadows.
- **Flat Surface Rule**:
  - Chat background is flat `colorScheme.background`.
  - User message bubbles use solid `colorScheme.primary` (no multi-color gradients).
  - Shimmer effects (`StateViews.kt`) are used strictly as animated loading states, never as static background decorations.

## Shapes
- **Corner Radii Hierarchy (`Shapes.kt`)**:
  - `0dp` (`rounded.none`): Terminal panels, full-bleed code blocks.
  - `4dp` (`rounded.extra-small`): Chips, badges, inline toggles.
  - `8dp` (`rounded.small`): Text fields, small cards, code cards.
  - `12dp` (`rounded.medium`): Standard cards, list rows, message bubbles, dialogs.
  - `16dp` (`rounded.large`): Large cards, bottom sheets.
  - `28dp` (`rounded.extra-large`): Feature cards, full-bleed surfaces.
  - `9999dp` (`rounded.full`): Interactive filter chips, timeline markers, circular icon buttons.

## Components
- **Top App Bar**: Flat surface, 56dp height, contains title marquee, WS connection status pill, and action buttons.
- **Chat Timeline**:
  - **User Bubbles**: Right-aligned, solid primary fill, high-contrast text (`onPrimary`).
  - **Assistant Bubbles**: Left-aligned, `surfaceContainer` fill (`#1B2022`), full Markdown renderer support.
  - **Tool Bubbles**: Expandable rows with tool vector icon, execution state indicator, parameter summary, and full payload bottom sheet.
- **Action & Filter Chips**:
  - Must represent real system state (e.g., active model, selected profile, filter criteria).
  - Active chips: `primaryContainer` fill with `onPrimaryContainer` text/icon.
  - Inactive chips: `surfaceContainerHigh` fill with `text-secondary` text.
  - Disabled controls: Visually greyed out with `alpha = 0.38f`.
- **Dialogs & Bottom Sheets**:
  - Standard CRUD flows must provide all 4 operations (Create, Read, Update, Delete) with confirmation gates for destructive actions.
  - Every managed resource must have a discoverable navigation surface in the UI.

## Do's and Don'ts

### Do's
- **DO** use official `androidx.compose.material.icons` vectors for every UI element and status indicator.
- **DO** use `Icons.AutoMirrored.Filled.*` for directional icons (`ArrowBack`, `Send`, `VolumeUp`, `MenuBook`).
- **DO** maintain $\ge 4.5:1$ text contrast and $\ge 3:1$ container contrast across all 6 presets.
- **DO** use Android string `<plurals>` for count labels (`1 agent` vs `2 agents`).
- **DO** grey out disabled controls (`enabled = false`) visibly.
- **DO** ensure every created resource has a discoverable UI list or navigation tab.

### Don'ts
- **DON'T** render emoji glyphs (`✅`, `❌`, `⭕`, `🔄`, `⚡`, `🛠`, `📄`) in the app UI — replace them with real vector icons.
- **DON'T** use Floating Action Buttons (FABs); anchor actions in top bars or fixed docks.
- **DON'T** apply `Modifier.padding(paddingValues)` inside `HermesScaffold` content lambdas.
- **DON'T** use gradient backgrounds on chat surfaces or message bubbles.
- **DON'T** create multi-layer nested cards or unnecessary decorative borders.
- **DON'T** use cycle buttons where a dropdown menu provides immediate option visibility.
