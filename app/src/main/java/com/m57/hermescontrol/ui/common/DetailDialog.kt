package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.m57.hermescontrol.theme.LocalHermesStatusColors

/**
 * Semantic tone for a detail row's value text.
 * NONEl uses the default on-surface variant; the rest pull from
 * [LocalHermesStatusColors] so status feedback stays brand-correct
 * regardless of the wallpaper-derived Material palette.
 */
enum class DetailRowTone {
    NONE,
    SUCCESS,
    WARNING,
    ERROR,
    INFO,
    NEUTRAL,
}

/**
 * A single label/value row shown in [DetailDialog].
 *
 * @param label Non-null resource string (already resolved by the mapper).
 * @param value Display value; null/blank rows are skipped at render time.
 * @param tone Semantic tone for the value text (defaults to [DetailRowTone.NONE]).
 */
data class DetailRow(
    val label: String,
    val value: String?,
    val tone: DetailRowTone = DetailRowTone.NONE,
)

/**
 * Reusable tap-to-reveal detail dialog: a title plus a scrollable list of
 * label/value rows. Null/blank rows are skipped automatically. An optional
 * [actions] slot renders after the rows (inside the scroll area) so primary
 * actions stay visible without the user dismissing the dialog.
 */
@Composable
fun DetailDialog(
    title: String,
    rows: List<DetailRow>,
    onDismiss: () -> Unit,
    actions: @Composable (() -> Unit)? = null,
) {
    val statusColors = LocalHermesStatusColors.current

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
        ) {
            HermesScaffold(
                title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                navigationIcon = NavIcon.Back(onDismiss),
                isRefreshing = false,
            ) { padding ->
                val visibleRows = rows.filter { !it.value.isNullOrBlank() }
                Column(
                    modifier =
                        Modifier
                            .padding(padding)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    visibleRows.forEachIndexed { index, (label, value, tone) ->
                        if (index > 0) {
                            HorizontalDivider()
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = value ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color =
                                    when (tone) {
                                        DetailRowTone.SUCCESS -> statusColors.success
                                        DetailRowTone.WARNING -> statusColors.warning
                                        DetailRowTone.ERROR -> statusColors.error
                                        DetailRowTone.INFO -> statusColors.info
                                        DetailRowTone.NEUTRAL -> statusColors.neutral
                                        DetailRowTone.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                    }

                    if (actions != null) {
                        if (visibleRows.isNotEmpty()) {
                            HorizontalDivider()
                        }
                        actions()
                    }
                }
            }
        }
    }
}
