package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.CatalogScanStatus
import com.m57.hermescontrol.data.model.ConnectionCatalogInfo
import com.m57.hermescontrol.data.model.ConnectionTargetKind
import com.m57.hermescontrol.theme.LocalHermesStatusColors

private const val SHORT_SHA_LENGTH = 12

/** What a plugin/skill install row would bring in: source, pin, scan and requirements (#1287). */
@Composable
internal fun ConnectionCatalogDetails(
    kind: ConnectionTargetKind,
    catalog: ConnectionCatalogInfo,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier.testTag("connection_catalog_details"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val kindLabel =
            stringResource(
                if (kind == ConnectionTargetKind.SKILL) {
                    R.string.connection_catalog_kind_skill
                } else {
                    R.string.connection_catalog_kind_plugin
                },
            )
        val tierLabel =
            when (catalog.tier) {
                null -> null
                "official" -> stringResource(R.string.connection_catalog_tier_official)
                "community" -> stringResource(R.string.connection_catalog_tier_community)
                else -> catalog.tier
            }
        Text(
            text = listOfNotNull(kindLabel, tierLabel).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = muted,
        )
        catalog.description?.let { Text(it) }
        catalog.repo?.let { repo ->
            val source = catalog.subdir?.let { "$repo/$it" } ?: repo
            Text(
                text = stringResource(R.string.connection_catalog_source, source),
                color = muted,
                modifier = Modifier.testTag("connection_catalog_source"),
            )
        }
        catalog.sha?.let { sha ->
            Text(
                text = stringResource(R.string.connection_catalog_pin, sha.take(SHORT_SHA_LENGTH)),
                color = muted,
                modifier = Modifier.testTag("connection_catalog_pin"),
            )
        }
        if (catalog.platforms.isNotEmpty()) {
            Text(
                text = stringResource(R.string.connection_catalog_platforms, catalog.platforms.joinToString(", ")),
                color = muted,
            )
        }
        CatalogScanLine(catalog)
        if (catalog.requirements.isNotEmpty()) {
            Text(
                text =
                    stringResource(
                        R.string.connection_catalog_requirements,
                        catalog.requirements.joinToString(", "),
                    ),
                color = muted,
                modifier = Modifier.testTag("connection_catalog_requirements"),
            )
        }
        Text(
            text = stringResource(R.string.connection_catalog_target_profile, catalog.targetProfile),
            color = muted,
            modifier = Modifier.testTag("connection_catalog_profile"),
        )
    }
}

@Composable
private fun CatalogScanLine(catalog: ConnectionCatalogInfo) {
    val status = LocalHermesStatusColors.current
    val scan = catalog.scan
    val (label, color) =
        when (scan?.status) {
            CatalogScanStatus.PASSED -> {
                R.string.connection_catalog_scan_passed to status.success
            }

            CatalogScanStatus.WARNINGS -> {
                R.string.connection_catalog_scan_warnings to status.warning
            }

            CatalogScanStatus.FAILED -> {
                R.string.connection_catalog_scan_failed to status.error
            }

            CatalogScanStatus.UNKNOWN, null -> {
                R.string.connection_catalog_scan_unreported to MaterialTheme.colorScheme.onSurfaceVariant
            }
        }
    val summary = scan?.summary
    Text(
        text = stringResource(label) + if (summary != null) " · $summary" else "",
        color = color,
        modifier = Modifier.testTag("connection_catalog_scan"),
    )
}
