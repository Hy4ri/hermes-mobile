package com.m57.hermescontrol.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalSpacing

// Unified loading / error / empty / skeleton placeholders so every screen
// shares the same visual language.

// ── Loading ───────────────────────────────────────────────────────────

@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .testTag("loading_state"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier =
                    Modifier
                        .size(40.dp)
                        .testTag("loading_spinner"),
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Error ──────────────────────────────────────────────────────────────

@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(spacing.md))
            Button(
                onClick = onRetry,
                modifier = Modifier.testTag("error_retry_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(spacing.sm))
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

// ── Empty ────────────────────────────────────────────────────────────

@Composable
fun EmptyState(
    title: String,
    subtitle: String? = null,
    icon: ImageVector = Icons.Filled.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(spacing.md))
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.testTag("empty_state_action"),
            ) {
                Text(actionLabel)
            }
        }
    }
}

// ── Skeleton (shimmer) — see issue #617 ────────────────────────────────
//
// Skeleton loaders for list screens. Replaces bare `CircularProgressIndicator`
// in list screens to (1) reduce perceived latency (~2× per NN/g research)
// and (2) eliminate layout shift when real content lands. Pair with
// `LoadingState()` for centered/one-shot contexts (Pairing flow, dialog
// interiors) where a spinner is still appropriate.

/**
 * An animated shimmer [Brush] derived from the current theme's
 * `MaterialTheme.colorScheme.surfaceVariant`. Sweeps horizontally across the
 * canvas; offset driven by an infinite `1.2s` tween so the shimmer never
 * freezes. Theme-derived — works on light, dark, AMOLED, Material You.
 */
@Composable
private fun shimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val offset by transition.animateFloat(
        initialValue = -widthPx,
        targetValue = widthPx,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "shimmer_offset",
    )
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(offset, 0f),
        end = Offset(offset + widthPx, 0f),
    )
}

@Composable
private fun ShimmerBox(
    widthFraction: Float,
    height: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(height)
                .fillMaxWidth(widthFraction)
                .clip(shape)
                .background(shimmerBrush()),
    )
}

@Composable
private fun SkeletonRow(height: Dp) {
    val spacing = LocalSpacing.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(height)
                .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar circle — 40dp to match default LoadingState spinner size.
        ShimmerBox(
            widthFraction = 0f,
            height = 40.dp,
            shape = CircleShape,
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.width(spacing.md))
        // Title + subtitle lines
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            ShimmerBox(widthFraction = 0.6f, height = 14.dp, shape = shape)
            ShimmerBox(widthFraction = 0.4f, height = 12.dp, shape = shape)
        }
    }
}

/**
 * Skeleton placeholder list for loading states on list/management screens.
 *
 * Renders [itemCount] shimmering rows whose geometry matches the typical list
 * row (avatar circle + title line + subtitle line). The shimmer colors derive
 * from theme tokens — works on light, dark, AMOLED, and Material You. No
 * layout shift on transition to real content (acceptance criterion, issue #617).
 *
 * @param itemCount number of placeholder rows to render (default 6 — long
 *   enough to fill a phone viewport under typical content density).
 * @param rowHeight height of each placeholder row (default 72dp — same as a
 *   one-line `NavigationDrawerItem`). Override per-screen to match real row
 *   geometry when known.
 * @param modifier outer modifier; pass `Modifier.padding(paddingValues)` from
 *   the scaffold so the skeleton offsets for the top bar like real content does.
 */
@Composable
fun SkeletonListState(
    itemCount: Int = 6,
    rowHeight: Dp = 72.dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .testTag("skeleton_list_state"),
        verticalArrangement = listItemSpacing,
    ) {
        repeat(itemCount) { SkeletonRow(height = rowHeight) }
    }
}

// ── Padding conventions ──────────────────────────────────────────────

val listContentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

val listItemSpacing = Arrangement.spacedBy(6.dp)
