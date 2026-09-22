package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.ChatTimelineState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTimelineSheet(
    state: ChatTimelineState,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    if (!state.isOpen) return

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_timeline_title),
                modifier = Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.chat_timeline_subtitle),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val error = state.windowErrorMessage ?: state.errorMessage
            if (error != null) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (!state.isLoading && state.jumpingRowId == null) {
                        TextButton(onClick = onRetry) {
                            Text(
                                stringResource(
                                    if (state.entries.isEmpty()) {
                                        R.string.chat_timeline_retry
                                    } else {
                                        R.string.chat_timeline_refresh
                                    },
                                ),
                            )
                        }
                    }
                }
            }

            when {
                state.isLoading && state.entries.isEmpty() -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.entries.isEmpty() && state.errorMessage == null -> {
                    Text(
                        text = stringResource(R.string.chat_timeline_empty),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.entries.isNotEmpty() -> {
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 520.dp),
                    ) {
                        items(
                            items = state.entries,
                            key = { it.row_id },
                        ) { entry ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text =
                                            entry.preview.ifBlank {
                                                stringResource(R.string.chat_timeline_prompt_fallback)
                                            },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                trailingContent = {
                                    if (state.jumpingRowId == entry.row_id) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                    }
                                },
                                modifier =
                                    Modifier.clickable(
                                        enabled = state.jumpingRowId == null,
                                        onClick = { onJump(entry.row_id) },
                                    ),
                            )
                            HorizontalDivider()
                        }

                        if (state.hasMore || (state.isLoading && state.entries.isNotEmpty())) {
                            item(key = "timeline-load-more") {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (state.isLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    } else {
                                        TextButton(onClick = onLoadMore) {
                                            Text(stringResource(R.string.chat_timeline_load_more))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatHistoryWindowBanner(
    hasOlder: Boolean,
    hasNewer: Boolean,
    onReturnToLatest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_history_window_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                val rangeMessage =
                    when {
                        hasOlder && hasNewer -> R.string.chat_history_window_more_both
                        hasNewer -> R.string.chat_history_window_more_newer
                        hasOlder -> R.string.chat_history_window_more_older
                        else -> null
                    }
                if (rangeMessage != null) {
                    Text(
                        text = stringResource(rangeMessage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            TextButton(onClick = onReturnToLatest) {
                Text(stringResource(R.string.chat_history_window_latest))
            }
        }
    }
}
