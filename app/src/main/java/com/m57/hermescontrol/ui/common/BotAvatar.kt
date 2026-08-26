package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.theme.StatusGreen
import com.m57.hermescontrol.theme.parseHexColor

/**
 * Reusable Material3 avatar component for Bot Mode agents.
 *
 * Renders geometric shapes (circle, square, rounded, hexagon), custom hex colors,
 * Material symbol icons, external image URLs via Coil, or initials fallback, with
 * an optional active presence dot.
 */
@Composable
fun BotAvatar(
    name: String,
    avatar: BotAvatarMeta?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    isActive: Boolean = false,
    showPresence: Boolean = true,
) {
    val shape = remember(avatar?.shape, size) { resolveAvatarShape(avatar?.shape, size) }
    val fallbackColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val bgColor =
        remember(avatar?.color, fallbackColor) {
            parseHexColor(avatar?.color, fallbackColor)
        }

    val iconVector = remember(avatar?.icon) { resolveAvatarIcon(avatar?.icon) }
    val imageUrl = avatar?.image_url?.takeIf { it.isNotBlank() }

    Box(
        modifier =
            modifier
                .size(size)
                .testTag("bot_avatar_$name"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(bgColor),
            contentAlignment = Alignment.Center,
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model =
                        ImageRequest.Builder(LocalContext.current)
                            .data(imageUrl)
                            .crossfade(true)
                            .build(),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (iconVector != null) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = name,
                    tint = contentColor,
                    modifier = Modifier.size(size * 0.55f),
                )
            } else {
                val initials = remember(name) { extractInitials(name) }
                Text(
                    text = initials,
                    color = contentColor,
                    fontSize = (size.value * 0.4f).sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (isActive && showPresence) {
            val badgeSize = (size * 0.3f).coerceIn(8.dp, 14.dp)
            val borderSize = (size * 0.05f).coerceIn(1.5.dp, 2.5.dp)
            Box(
                modifier =
                    Modifier
                        .size(badgeSize)
                        .align(Alignment.BottomEnd)
                        .offset(x = 1.dp, y = 1.dp)
                        .border(borderSize, MaterialTheme.colorScheme.surface, CircleShape)
                        .clip(CircleShape)
                        .background(StatusGreen)
                        .testTag("bot_avatar_presence_$name"),
            )
        }
    }
}

/**
 * Maps shape name to Compose [Shape].
 */
fun resolveAvatarShape(
    shapeKey: String?,
    size: Dp,
): Shape {
    return when (shapeKey?.lowercase()?.trim()) {
        "square", "box", "boxy" -> RoundedCornerShape(size * 0.15f)
        "rounded", "nub", "organic" -> RoundedCornerShape(size * 0.32f)
        "hexagon", "cut", "diamond", "cloud", "sun" -> CutCornerShape(size * 0.25f)
        "circle", "round" -> CircleShape
        else -> CircleShape
    }
}

/**
 * Maps known icon keys to Material [ImageVector] icons.
 */
fun resolveAvatarIcon(iconKey: String?): ImageVector? {
    return when (iconKey?.lowercase()?.trim()) {
        "code", "dev", "terminal" -> Icons.Filled.Code
        "build", "tool", "tools" -> Icons.Filled.Build
        "psychology", "brain", "ai", "research", "intel" -> Icons.Filled.Psychology
        "science", "lab" -> Icons.Filled.Science
        "bolt", "zap", "action" -> Icons.Filled.Bolt
        "sensors", "sensor", "monitor", "watchdog" -> Icons.Filled.Sensors
        "extension", "plugin" -> Icons.Filled.Extension
        "robot", "bot", "smart_toy" -> Icons.Filled.SmartToy
        else -> null
    }
}

/**
 * Extract 1-2 uppercase letters from name for initials fallback.
 */
fun extractInitials(name: String): String {
    val clean = name.trim().removePrefix("@")
    if (clean.isBlank()) return "?"
    val parts = clean.split(Regex("[_\\-\\s]+")).filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
        clean.length >= 2 -> clean.take(2).uppercase()
        else -> clean.take(1).uppercase()
    }
}
