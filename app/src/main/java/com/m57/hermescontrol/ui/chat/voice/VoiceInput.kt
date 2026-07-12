package com.m57.hermescontrol.ui.chat.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberVoiceInputController(): CassyVoiceInputController {
    val context = LocalContext.current
    val controller = remember(context.applicationContext) { CassyVoiceInputController(context) }
    DisposableEffect(controller) {
        onDispose { controller.destroy() }
    }
    return controller
}
