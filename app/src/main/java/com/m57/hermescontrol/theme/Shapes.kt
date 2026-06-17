package com.m57.hermescontrol.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Unified shape system for Hermes Control.
 *
 * - xs: chips, small badges
 * - sm: text fields, small cards
 * - md: standard cards, list rows
 * - lg: large cards, bottom sheets
 * - xl: feature cards, full-bleed surfaces
 */
val HermesShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )
