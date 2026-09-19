package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.MediaKind
import com.m57.hermescontrol.ui.chat.classifyMedia
import com.m57.hermescontrol.ui.chat.mediaNameFromPath

/**
 * Shared inline media preview card for audio and video in chat bubbles and markdown blocks.
 * Renders a compact file-style card for audio and a 16:9 preview for video.
 */
@Composable
fun InlineMediaPlayer(
    uri: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    mimeType: String? = null,
    onFullScreenClick: () -> Unit = {},
) {
    val mediaKind =
        remember(uri, mimeType, title) {
            classifyMedia(mimeType = mimeType, name = title, uri = uri)
        }

    val displayName =
        remember(uri, title) {
            title?.takeIf { it.isNotBlank() } ?: mediaNameFromPath(uri)
        }

    if (mediaKind == MediaKind.AUDIO) {
        InlineAudioPreview(
            name = displayName,
            modifier = modifier,
            onClick = onFullScreenClick,
        )
    } else {
        InlineVideoPreview(
            modifier = modifier,
            onClick = onFullScreenClick,
        )
    }
}

@Composable
private fun InlineAudioPreview(
    name: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .testTag("inline_audio_player"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier =
                    Modifier
                        .size(42.dp)
                        .testTag("audio_play_overlay"),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Audiotrack,
                        contentDescription = stringResource(R.string.media_player_audio),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.media_player_audio).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.media_player_play_audio),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineVideoPreview(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { onClick() }
                .testTag("inline_video_player"),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f),
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
                    .testTag("video_play_overlay"),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.media_player_play_video),
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp),
                    ).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.media_player_video),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Text(
                    text = " " + stringResource(R.string.media_player_video).uppercase(),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    fontSize = 10.sp,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
