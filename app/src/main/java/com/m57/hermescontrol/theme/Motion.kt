package com.m57.hermescontrol.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion tokens — duration, easing, and spring specs.
 *
 * Access via `LocalMotion.current` in composables.
 */
object Motion

val MotionDefaults = Motion

val LocalMotion = staticCompositionLocalOf { Motion }
