package com.m57.hermescontrol.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.BusySendMode

@Composable
fun BusySendMode.label(): String =
    when (this) {
        BusySendMode.CORRECT -> stringResource(R.string.chat_busy_correct)
        BusySendMode.QUEUE -> stringResource(R.string.chat_busy_queue)
        BusySendMode.GUIDE -> stringResource(R.string.chat_busy_guide)
        BusySendMode.INTERRUPT -> stringResource(R.string.chat_busy_interrupt)
    }
