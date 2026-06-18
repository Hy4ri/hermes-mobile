package com.m57.hermescontrol.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Unified shape system — aligned to 8 dp rhythm (4/8/12/16/28).
 *
 * | Token        | Radius | Use case                              |
 * |--------------|--------|---------------------------------------|
 * | extraSmall   | 4 dp   | Chips, badges, inline toggles         |
 * | small        | 8 dp   | Text fields, small cards, small FAB   |
 * | medium       | 12 dp  | Standard cards, list rows, dialogs    |
 * | large        | 16 dp  | Large cards, bottom sheets, FAB       |
 * | extraLarge   | 28 dp  | Feature cards, full-bleed surfaces   |
 */
val HermesShapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
