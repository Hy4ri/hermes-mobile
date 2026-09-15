package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.m57.hermescontrol.R

/**
 * Compact attachment tray that extends the composer without looking like a
 * separate system menu. It is hosted in a focusable popup so outside taps and
 * the system back action dismiss it naturally.
 */
@Composable
internal fun AttachmentTray(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    onCameraTap: () -> Unit,
    onImageTap: () -> Unit,
    onFileTap: () -> Unit,
) {
    val transitionState = remember { MutableTransitionState(visible) }
    LaunchedEffect(visible) {
        transitionState.targetState = visible
    }

    if (!visible && !transitionState.currentState) {
        return
    }

    val marginPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val trayPositionProvider =
        remember(marginPx) {
            AttachmentTrayPositionProvider(marginPx = marginPx)
        }

    Popup(
        popupPositionProvider = trayPositionProvider,
        onDismissRequest = onDismissRequest,
        properties =
            PopupProperties(
                focusable = visible,
                dismissOnBackPress = visible,
                dismissOnClickOutside = visible,
            ),
    ) {
        AnimatedVisibility(
            visibleState = transitionState,
            enter =
                fadeIn(animationSpec = tween(durationMillis = 160)) +
                    slideInVertically(
                        animationSpec = tween(durationMillis = 160),
                        initialOffsetY = { it / 4 },
                    ) +
                    scaleIn(
                        animationSpec = tween(durationMillis = 160),
                        initialScale = 0.96f,
                    ),
            exit =
                fadeOut(animationSpec = tween(durationMillis = 120)) +
                    slideOutVertically(
                        animationSpec = tween(durationMillis = 120),
                        targetOffsetY = { it / 4 },
                    ) +
                    scaleOut(
                        animationSpec = tween(durationMillis = 120),
                        targetScale = 0.96f,
                    ),
        ) {
            val palette = composerPalette()
            Surface(
                modifier =
                    Modifier
                        .widthIn(max = 280.dp)
                        .testTag("attachment_tray"),
                shape = MaterialTheme.shapes.large,
                color = palette.card,
                border = BorderStroke(width = 1.dp, color = palette.cardBorder),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AttachmentTrayAction(
                        icon = Icons.Filled.CameraAlt,
                        label = stringResource(R.string.chat_attachment_camera),
                        onClick = {
                            onDismissRequest()
                            onCameraTap()
                        },
                        modifier = Modifier.weight(1f).testTag("attachment_action_camera"),
                    )
                    AttachmentTrayAction(
                        icon = Icons.Filled.PhotoLibrary,
                        label = stringResource(R.string.chat_attachment_photos),
                        onClick = {
                            onDismissRequest()
                            onImageTap()
                        },
                        modifier = Modifier.weight(1f).testTag("attachment_action_photos"),
                    )
                    AttachmentTrayAction(
                        icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                        label = stringResource(R.string.chat_attachment_file),
                        onClick = {
                            onDismissRequest()
                            onFileTap()
                        },
                        modifier = Modifier.weight(1f).testTag("attachment_action_file"),
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentTrayAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .heightIn(min = 72.dp)
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    role = Role.Button
                }.padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            softWrap = true,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

internal class AttachmentTrayPositionProvider(
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val minX = marginPx
        val maxX =
            (windowSize.width - popupContentSize.width - marginPx)
                .coerceAtLeast(minX)
        val x =
            if (layoutDirection == LayoutDirection.Ltr) {
                anchorBounds.left + marginPx
            } else {
                anchorBounds.right - popupContentSize.width - marginPx
            }
        val yAbove = anchorBounds.top - popupContentSize.height - marginPx
        val yBelow = anchorBounds.bottom + marginPx
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val y = if (yAbove >= 0) yAbove else yBelow
        return IntOffset(
            x = x.coerceIn(minX, maxX),
            y = y.coerceIn(0, maxY),
        )
    }
}
