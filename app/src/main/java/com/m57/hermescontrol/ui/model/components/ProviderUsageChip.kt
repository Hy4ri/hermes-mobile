package com.m57.hermescontrol.ui.model.components

import android.text.format.DateUtils
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.ProviderUsageStatus
import com.m57.hermescontrol.data.model.usageStatus
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import java.time.Instant

/** Rate-limit / low-quota chip for a provider header. Renders nothing when the provider is healthy. */
@Composable
fun ProviderUsageChip(
    provider: ModelProvider,
    modifier: Modifier = Modifier,
) {
    val status = remember(provider.usage, provider.limit) { provider.usageStatus() } ?: return
    val context = LocalContext.current
    val statusColors = LocalHermesStatusColors.current
    val resetText = { at: Instant? ->
        at?.let {
            DateUtils.formatDateTime(
                context,
                it.toEpochMilli(),
                DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL,
            )
        }
    }
    val (text, warn) =
        when (status) {
            is ProviderUsageStatus.Limited -> {
                (
                    resetText(status.resetsAt)?.let { stringResource(R.string.model_provider_limited_until, it) }
                        ?: stringResource(R.string.model_provider_limited)
                ) to true
            }

            is ProviderUsageStatus.LowQuota -> {
                (
                    resetText(status.resetsAt)?.let {
                        stringResource(R.string.model_provider_quota_left_resets, status.percentLeft, it)
                    } ?: stringResource(R.string.model_provider_quota_left, status.percentLeft)
                ) to status.isCritical
            }
        }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (warn) statusColors.warningContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (warn) statusColors.onWarning else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
