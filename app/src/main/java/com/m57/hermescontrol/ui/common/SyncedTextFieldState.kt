package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

/**
 * Two-way bridge between an external [String] holder (ViewModel state, a parent
 * callback, …) and a [TextFieldState]-backed text field.
 *
 * Why not `OutlinedTextField(value:, onValueChange:)`? The value-based overload
 * keeps its selection inside the legacy `BasicTextField` implementation, so when
 * a scrolled field gains focus it scrolls the cursor into view while the cursor
 * is still at index 0 — the viewport jumps back to the top instead of staying
 * where the user tapped. `TextFieldState`-backed fields only follow the cursor
 * while the user is actively editing, so the scroll position survives the tap.
 *
 * Inbound (external → field) updates are applied only when the text actually
 * differs, which keeps ViewModel round-trips from clobbering in-progress typing;
 * outbound (field → external) updates skip the identity case so the two
 * directions can never ping-pong.
 */
@Composable
fun rememberSyncedTextFieldState(
    value: String,
    onValueChange: (String) -> Unit,
): TextFieldState {
    val state = rememberTextFieldState(value)
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    LaunchedEffect(state, value) {
        if (state.text.toString() != value) {
            state.edit { replace(0, length, value) }
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .collect { newText ->
                if (newText != currentValue) currentOnValueChange(newText)
            }
    }

    return state
}
