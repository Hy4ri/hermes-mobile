package com.m57.hermescontrol.ui.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/**
 * Remembers a copy feedback state and automatically resets it to `false`
 * after 1500ms when set to `true`.
 *
 * If `copied.value` is already `true`, setting it to `true` again does not
 * restart the active timer.
 */
@Composable
internal fun rememberCopyFeedback(): MutableState<Boolean> {
    val copied = remember { mutableStateOf(false) }

    LaunchedEffect(copied.value) {
        if (copied.value) {
            delay(1500)
            copied.value = false
        }
    }

    return copied
}
