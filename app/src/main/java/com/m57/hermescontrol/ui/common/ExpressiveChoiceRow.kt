package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Native onSurfaceVariant misses normal-text contrast on Gruvbox surfaceContainer.
// Use the existing strong foreground token; keep every other native state color.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun expressiveChoiceColors() = ToggleButtonDefaults.colors(contentColor = MaterialTheme.colorScheme.onSurface)

/**
 * Shared native Expressive connected single-choice row adapter built with
 * [ButtonGroup] and [ToggleButton].
 *
 * The composable group overload is intentionally used in a horizontal scroll
 * container: its deprecation warns about cutoff, which scrolling prevents without
 * hiding single-choice options in an overflow menu.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveChoiceRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scrollState = rememberScrollState()
    val interactionSources =
        remember(options) {
            List(options.size) { MutableInteractionSource() }
        }

    ButtonGroup(
        overflowIndicator = {},
        modifier = modifier.horizontalScroll(scrollState).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selectedIndex
            val interactionSource = interactionSources[index]

            customItem(
                buttonGroupContent = {
                    val shapes: ToggleButtonShapes =
                        when {
                            options.size == 1 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            index == options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        }
                    ToggleButton(
                        checked = isSelected,
                        onCheckedChange = { checked ->
                            if (checked && !isSelected) {
                                onSelect(index)
                            }
                        },
                        modifier =
                            Modifier
                                .animateWidth(interactionSource)
                                .heightIn(min = 48.dp)
                                .semantics {
                                    role = Role.RadioButton
                                    selected = isSelected
                                },
                        enabled = enabled,
                        colors = expressiveChoiceColors(),
                        shapes = shapes,
                        interactionSource = interactionSource,
                    ) {
                        Text(
                            text = option,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                },
                menuContent = {},
            )
        }
    }
}
