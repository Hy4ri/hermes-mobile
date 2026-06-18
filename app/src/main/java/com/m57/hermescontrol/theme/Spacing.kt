package com.m57.hermescontrol.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Spacing tokens — the 8 dp rhythm that every screen shares.
 *
 * | Token | Value  | Use case                                   |
 * |-------|--------|-------------------------------------------|
 * | xs    | 4 dp   | Hairline gaps, chip inner padding          |
 * | sm    | 8 dp   | Default inner padding, icon-to-text gap   |
 * | md    | 16 dp  | Card content, screen edge, section gaps   |
 * | lg    | 24 dp  | Hero spacing, between major sections      |
 * | xl    | 32 dp  | Full-feature spacing, large section break  |
 * | xxl   | 48 dp  | Maximum spacing, rare                     |
 *
 * Access in composables via `LocalSpacing.current` or the [Spacing] object.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

/** Alias for backwards-compatibility — existing `Spacing.xs` calls still work. */
val SpacingDefaults = Spacing

val LocalSpacing = staticCompositionLocalOf { Spacing }
