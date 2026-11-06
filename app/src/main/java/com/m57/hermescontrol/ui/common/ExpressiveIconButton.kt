package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/**
 * Standard icon button with Material 3 Expressive motion and shape morphing.
 *
 * If a custom [shape] is provided, it is applied consistently to both rest and pressed states.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    shape: Shape? = null,
    content: @Composable () -> Unit,
) {
    val buttonShapes =
        if (shape != null) {
            IconButtonShapes(shape = shape, pressedShape = shape)
        } else {
            IconButtonDefaults.shapes()
        }
    IconButton(
        onClick = onClick,
        shapes = buttonShapes,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        content = content,
    )
}
