package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.m57.hermescontrol.R

/**
 * Shared tap-to-reveal detail dialog used across list screens (skills, plugins,
 * MCP servers, toolsets, webhooks) to show untruncated metadata for an item
 * whose card description is truncated. See issue #500.
 *
 * @param title        Dialog title (usually the item name).
 * @param rows         Label → value pairs. Rows with a null or blank value are
 *                     skipped so callers can pass every field unconditionally.
 * @param onDismiss    Called when the dialog is dismissed.
 * @param actions     Optional trailing content (action buttons). Rendered inside
 *                     the scroll area below the rows so it never scrolls away from
 *                     the content but stays grouped with the detail.
 */
@Composable
fun DetailDialog(
    title: String,
    rows: List<Pair<String, String?>>,
    onDismiss: () -> Unit,
    actions: @Composable (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
        ) {
            HermesScaffold(
                title = { Text(title) },
                navigationIcon = NavIcon.Back(onBack = onDismiss),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val visibleRows = rows.filter { !it.second.isNullOrBlank() }
                    if (visibleRows.isEmpty()) {
                        Text(
                            text = stringResource(R.string.detail_dialog_no_info),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        visibleRows.forEachIndexed { index, (label, value) ->
                            if (index > 0) {
                                HorizontalDivider()
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                label.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = value ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    actions?.let {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            it()
                        }
                    }
                }
            }
        }
    }
}
