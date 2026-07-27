package com.m57.hermescontrol.ui.chat.components

import android.graphics.drawable.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun GifImageThumbnail(
    model: Any,
    contentDescription: String?,
    isGif: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPlaying by remember { mutableStateOf(true) }
    var animatableDrawable by remember { mutableStateOf<Animatable?>(null) }

    val togglePlayPause: () -> Unit = {
        val nextState = !isPlaying
        isPlaying = nextState
        animatableDrawable?.let { anim ->
            if (nextState) {
                anim.start()
            } else {
                anim.stop()
            }
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onClick() },
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            onSuccess = { result ->
                val drawable = result.result.drawable
                if (drawable is Animatable) {
                    animatableDrawable = drawable
                    if (isPlaying) {
                        drawable.start()
                    } else {
                        drawable.stop()
                    }
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.FillWidth,
        )

        if (isGif) {
            // Play / Pause toggle badge
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(16.dp),
                        ).clickable { togglePlayPause() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause GIF" else "Play GIF",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = " GIF",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            // Center overlay button when paused
            if (!isPlaying) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .background(
                                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                                shape = CircleShape,
                            ).clickable { togglePlayPause() }
                            .padding(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play GIF",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
