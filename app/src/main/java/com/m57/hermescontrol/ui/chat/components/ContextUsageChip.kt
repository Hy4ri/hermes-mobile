package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import kotlin.math.min

/**
 * Compact "used / full context" meter for the chat screen.
 *
 * Renders `<used> / <full>` (e.g. `12.3k / 262k`) plus a color-coded progress
 * bar. The bar turns amber past 70% and red past 90% of the context window so
 * the user sees compression pressure building before a turn silently compacts.
 *
 * Both values are best-effort: when either is missing (not yet fetched, or the
 * gate is unreachable) the chip is simply hidden rather than showing `? / ?`.
 *
 * @param usedTokens tokens currently in the session prompt (numerator)
 * @param fullTokens the active model's full context window (denominator)
 */
@Composable
fun ContextUsageChip(
    usedTokens: Long?,
    fullTokens: Long?,
    modifier: Modifier = Modifier,
) {
    val used = usedTokens
    val full = fullTokens
    if (used == null || full == null || full <= 0L) return

    val fraction = min(1f, used.toFloat() / full.toFloat())
    val pct = (fraction * 100).toInt()
    val statusColors = LocalHermesStatusColors.current
    val barColor =
        when {
            pct >= 90 -> statusColors.error
            pct >= 70 -> statusColors.warning
            else -> MaterialTheme.colorScheme.primary
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${formatTokens(used)} / ${formatTokens(full)} context",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$pct%",
                style = MaterialTheme.typography.labelSmall,
                color = barColor,
                maxLines = 1,
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(2.dp),
                    ),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(
                            color = barColor,
                            shape = RoundedCornerShape(2.dp),
                        ),
            )
        }
    }
}

/**
 * Compact token formatting: 1_500 → "1.5k", 262_144 → "262k", 950 → "950".
 * Keeps the chip narrow so it fits above the composer on small screens.
 */
internal fun formatTokens(tokens: Long): String =
    when {
        tokens >= 1_000_000 -> {
            "${tokens / 1_000_000}M"
        }

        tokens >= 100_000 -> {
            "${tokens / 1000}k"
        }

        tokens >= 1_000 -> {
            val k = tokens / 1000.0
            if (k % 1.0 == 0.0) "${k.toInt()}k" else String.format(java.util.Locale.US, "%.1fk", k)
        }

        else -> {
            tokens.toString()
        }
    }
