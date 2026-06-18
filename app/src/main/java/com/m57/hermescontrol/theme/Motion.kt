package com.m57.hermescontrol.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion tokens — duration, easing, and spring specs.
 *
 * Design intent (ui-ux-pro-max §7):
 *  - Micro-interactions: 150–300 ms
 *  - Complex transitions: ≤ 400 ms
 *  - Never exceed 500 ms
 *  - Enter: ease-out (decelerated), Exit: ease-in (accelerated)
 *  - Exit shorter than enter (~60–70 %)
 *  - Spring physics for interactive elements
 *  - All animations must be interruptible
 *
 * Access via `LocalMotion.current` in composables.
 */
object Motion {
    // ── Duration tokens (ms) ─────────────────────────────────────────

    /** Instant — state changes with no perceptible delay. */
    const val INSTANT = 0

    /** Fast — micro-interactions, ripples, opacity toggles. */
    const val FAST = 150

    /** Normal — standard UI transitions, sheet slides, expansion. */
    const val NORMAL = 250

    /** Slow — complex multi-element transitions, screen navigation. */
    const val SLOW = 400

    // ── Easing curves ────────────────────────────────────────────────

    /** Emphasized decelerate — for ENTERING elements (ease-out). */
    val emphasizedDecelerate: Easing =
        CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** Emphasized accelerate — for EXITING elements (ease-in). */
    val emphasizedAccelerate: Easing =
        CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /** Standard — default M3 easing for neutral transitions. */
    val standard: Easing = FastOutSlowInEasing

    // ── Spring specs ─────────────────────────────────────────────────

    /** Bouncy spring for interactive elements (press, release, drag). */
    fun <T> bouncySpring() =
        spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        )

    /** Snappy spring for state toggles (switch, selection). */
    fun <T> snappySpring() =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh,
        )

    /** Gentle spring for layout animations (expand, collapse). */
    fun <T> gentleSpring() =
        spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        )
}

val MotionDefaults = Motion

val LocalMotion = staticCompositionLocalOf { Motion }
