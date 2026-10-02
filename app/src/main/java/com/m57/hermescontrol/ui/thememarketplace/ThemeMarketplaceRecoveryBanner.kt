package com.m57.hermescontrol.ui.thememarketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R

/**
 * Recovery banner for spec M6: the persisted preset is `CUSTOM` but the
 * imported palette could not be restored, so `Theme.kt` is silently falling
 * back to Default.
 *
 * The fallback is stated plainly and the two honest actions are offered —
 * re-apply the theme from the gallery, or clear the broken import. It is never
 * labelled as an installed Marketplace theme, because none is active.
 *
 * Shared by the marketplace browser and the appearance settings page; both
 * display preset state, and duplicating this banner is how the two surfaces
 * drift apart.
 */
@Composable
fun ThemeMarketplaceRecoveryBanner(
    themeName: String?,
    onReapply: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.theme_marketplace_custom_unavailable),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text =
                    themeName
                        ?.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.theme_marketplace_custom_unavailable_named, it) }
                        ?: stringResource(R.string.theme_marketplace_custom_unavailable_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onReapply,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.theme_marketplace_reapply),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.theme_marketplace_clear_custom),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
