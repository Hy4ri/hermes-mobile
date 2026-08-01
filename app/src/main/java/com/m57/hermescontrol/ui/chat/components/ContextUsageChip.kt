package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
 * Renders `<used> / <full> context` (e.g. `12.3k / 262k`) plus a color-coded
 * progress bar. The bar turns amber past 70% and red past 90% of the context
 * window so the user sees compression pressure building before a turn silently
 * compacts.
 *
 * The denominator (`fullTokens`) comes from the `session.context_breakdown`
 * WS RPC's `context_max` (fallback `GET /api/model/info`), and the numerator
 * (`usedTokens`) from the same RPC's `context_used` — the live agent's actual
 * prompt occupancy, which drops after context compression (issue #756). The
 * RPC may be absent on first render (WS still connecting); the chip shows as
 * soon as `fullTokens` is known, and when `usedTokens` is still null it
 * renders `— / <full>` (unknown used) rather than hiding entirely, so a
 * missing fetch never blanks the meter.
 *
 * @param usedTokens tokens currently in the session prompt (numerator), or null
 * @param fullTokens the active model's full context window (denominator)
 * @param compressionCount how many times the session has been context-compressed;
 * when > 0 a "compressed ×N" badge renders next to the percentage — the badge
 * explains a meter drop after auto-compaction or `/compress` (issue #756)
 */
@Composable
fun ContextUsageChip(
    usedTokens: Long?,
    fullTokens: Long?,
    compressionCount: Int? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val full = fullTokens
    if (full == null || full <= 0L) return

    val used = usedTokens ?: 0L
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
                .then(
                    if (onClick != null) {
                        Modifier
                            .clickable(onClick = onClick)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    } else {
                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    },
                ),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    if (usedTokens != null) {
                        "${formatTokens(used)} / ${formatTokens(full)} context"
                    } else {
                        "— / ${formatTokens(full)} context"
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (compressionCount != null && compressionCount > 0) {
                    Text(
                        text = "compressed ×$compressionCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                    )
                }
                Text(
                    text = "$pct%",
                    style = MaterialTheme.typography.labelSmall,
                    color = barColor,
                    maxLines = 1,
                )
            }
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
