package com.m57.hermescontrol.ui.chat.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import kotlinx.coroutines.delay

/**
 * Telegram-style recording strip shown in place of the input field while a
 * voice note is being recorded: a slide-to-cancel hint, an elapsed timer, and
 * a live mic level meter so the press and the captured audio are both visible
 * even when the mic button itself is under the thumb.
 */
@Composable
internal fun VoiceNoteRecordingPanel(
    amplitude: State<Float>,
    modifier: Modifier = Modifier,
) {
    val palette = composerPalette()
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
            delay(200)
        }
    }
    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        snapshotFlow { amplitude.value }.collect { level ->
            levels.add(level)
            while (levels.size > VOICE_NOTE_BARS) {
                levels.removeAt(0)
            }
        }
    }
    Row(
        modifier =
            modifier
                .heightIn(min = 42.dp)
                .testTag("voice_note_recording_panel"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            // Decorative: the adjacent "Slide to cancel" text carries the
            // instruction, so the arrow must not repeat it (review, PR #1250).
            contentDescription = null,
            tint = palette.placeholder,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.chat_voice_slide_to_cancel),
            style = MaterialTheme.typography.labelMedium,
            color = palette.placeholder,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
        )
        Text(
            text = formatElapsed(elapsedSeconds),
            style = MaterialTheme.typography.labelLarge,
            color = palette.text,
        )
        Row(
            modifier = Modifier.height(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val filler = VOICE_NOTE_BARS - levels.size
            repeat(VOICE_NOTE_BARS) { index ->
                val level = if (index < filler) 0f else levels[index - filler]
                Box(
                    modifier =
                        Modifier
                            .width(3.dp)
                            .height((4f + 18f * level).dp)
                            .background(palette.placeholder, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

/** Number of level-meter bars in the recording panel. */
private const val VOICE_NOTE_BARS = 14

/** Formats elapsed recording time as m:ss. */
private fun formatElapsed(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "$minutes:${secs.toString().padStart(2, '0')}"
}
