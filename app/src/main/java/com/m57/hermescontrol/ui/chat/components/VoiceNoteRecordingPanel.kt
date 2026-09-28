package com.m57.hermescontrol.ui.chat.components

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Telegram-style recording strip shown in place of the input field while a
 * voice note is being recorded. Layout mirrors Telegram's recorder: a blinking
 * red dot and the elapsed timer sit on the left; "◀ Slide to cancel" (with the
 * word "cancel" emphasized) sits centered to their right, gently nudging back
 * and forth, and slides left and fades as the gesture drags toward cancel
 * ([slideProgress] runs 1 → 0 across that drag). Once the gesture slides up to
 * lock, the hint is replaced by a bold tappable "CANCEL" and the action button
 * submits the note.
 */
@Composable
internal fun VoiceNoteRecordingPanel(
    slideProgress: State<Float>,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    onCancel: () -> Unit = {},
) {
    val palette = composerPalette()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
            delay(200)
        }
    }
    // Telegram blinks the record dot on a 1200 ms cycle.
    val blink = rememberInfiniteTransition(label = "voice_dot")
    val dotAlpha by
        blink.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
            label = "voice_dot_alpha",
        )
    // ...and nudges the cancel hint ±6 dp while the gesture is idle (Telegram
    // moves it 3 dp / 250 ms), so the slide affordance announces itself.
    val nudgeDp by
        blink.animateFloat(
            initialValue = -6f,
            targetValue = 6f,
            animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
            label = "voice_hint_nudge",
        )
    val progress = slideProgress.value.coerceIn(0f, 1f)
    Row(
        modifier =
            modifier
                .heightIn(min = 42.dp)
                .testTag("voice_note_recording_panel"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(12.dp)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = dotAlpha), CircleShape),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = formatElapsed(elapsedSeconds),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = palette.placeholder,
        )
        BoxWithConstraints(
            modifier =
                Modifier
                    .weight(1f)
                    .clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            val containerWidthPx = with(density) { maxWidth.toPx() }
            if (locked) {
                Text(
                    text = stringResource(R.string.chat_voice_cancel).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = palette.text,
                    modifier =
                        Modifier
                            .minimumInteractiveComponentSize()
                            .clickable(role = Role.Button) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onCancel()
                            }.testTag("voice_note_cancel_button"),
                )
            } else {
                Row(
                    modifier =
                        Modifier
                            .offset {
                                val shift = -containerWidthPx * 0.25f * (1f - progress)
                                val nudge = nudgeDp * density.density * progress
                                IntOffset((shift + nudge).roundToInt(), 0)
                            }.alpha(progress),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CancelChevron(tint = palette.placeholder)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = slideToCancelText(palette),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** "Slide to cancel" with the cancel word carrying the visual emphasis. */
@Composable
private fun slideToCancelText(palette: ComposerPalette) =
    buildAnnotatedString {
        // Two resources so translators control both parts independently; the
        // space between them is added here (review, PR #1280).
        withStyle(SpanStyle(color = palette.placeholder)) {
            append(stringResource(R.string.chat_voice_slide_to_cancel_prefix))
            append(" ")
        }
        withStyle(SpanStyle(color = palette.text, fontWeight = FontWeight.Bold)) {
            append(stringResource(R.string.chat_voice_slide_to_cancel_action))
        }
    }

/** The small drawn left chevron that precedes the cancel hint. */
@Composable
private fun CancelChevron(tint: Color) {
    Canvas(modifier = Modifier.size(width = 4.dp, height = 10.dp)) {
        val stroke = 1.6.dp.toPx()
        val midY = size.height / 2f
        val path =
            Path().apply {
                moveTo(size.width - stroke / 2f, midY - 5.dp.toPx())
                lineTo(stroke / 2f, midY)
                lineTo(size.width - stroke / 2f, midY + 5.dp.toPx())
            }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Formats elapsed recording time as m:ss. */
private fun formatElapsed(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "$minutes:${secs.toString().padStart(2, '0')}"
}
