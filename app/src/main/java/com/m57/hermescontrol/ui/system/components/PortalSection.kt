package com.m57.hermescontrol.ui.system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.Spacing
import com.m57.hermescontrol.ui.common.InfoRow
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.system.SystemUiState

fun LazyListScope.portalSection(
    state: SystemUiState,
    spacing: Spacing,
    uriHandler: UriHandler,
) {
    state.portal?.let { portal ->
        item {
            SectionHeader(title = stringResource(R.string.system_sec_portal))
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            ) {
                Column(modifier = Modifier.padding(spacing.md)) {
                    // Login status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        val loggedIn = portal.logged_in == true
                        StatusBadge(
                            text =
                                if (loggedIn) {
                                    stringResource(R.string.system_status_logged_in)
                                } else {
                                    stringResource(R.string.system_status_not_logged_in)
                                },
                            status = if (loggedIn) StatusBadgeType.SUCCESS else StatusBadgeType.WARNING,
                        )
                        if (!loggedIn) {
                            Text(
                                text = stringResource(R.string.system_portal_login_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Provider
                    portal.provider?.let { provider ->
                        InfoRow(
                            label = stringResource(R.string.system_portal_provider, provider),
                            value = "",
                        )
                    }

                    // Manage subscription link
                    portal.subscription_url?.let { url ->
                        TextButton(
                            onClick = { uriHandler.openUri(url) },
                            modifier = Modifier.padding(top = spacing.xs),
                        ) {
                            Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(spacing.xs))
                            Text(
                                stringResource(R.string.system_portal_manage_subscription),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }

                    // Feature routing
                    portal.features?.let { features ->
                        if (features.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
                            features.forEach { feature ->
                                InfoRow(
                                    label = feature.label ?: stringResource(R.string.system_portal_feature_routing),
                                    value = feature.state ?: "",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
