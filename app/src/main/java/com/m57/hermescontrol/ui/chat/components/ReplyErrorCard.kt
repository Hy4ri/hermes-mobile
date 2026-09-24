package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.ReplyFailure
import com.m57.hermescontrol.ui.chat.sanitizeReplyErrorDetails

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReplyErrorCard(
    failure: ReplyFailure,
    onDismiss: () -> Unit,
    onOpenLogs: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    var expanded by rememberSaveable(failure.id) { mutableStateOf(false) }

    val details = remember(failure.details) { sanitizeReplyErrorDetails(failure.details) }
    val colors = LocalHermesStatusColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("reply_error_card"),
        shape = MaterialTheme.shapes.medium,
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
        border = BorderStroke(1.dp, colors.error),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null)
                Text(
                    stringResource(R.string.chat_reply_failed_title),
                    modifier =
                        Modifier
                            .weight(
                                1f,
                            ).padding(start = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_reply_failed_dismiss))
                }
            }
            Text(stringResource(R.string.chat_reply_failed_description), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { expanded = !expanded }) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                Text(stringResource(R.string.chat_reply_failed_details))
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        details,
                        modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Text(stringResource(R.string.chat_reply_failed_share_note), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenLogs) { Text(stringResource(R.string.chat_reply_failed_logs)) }
                OutlinedButton(onClick = { onCopy(details) }) { Text(stringResource(R.string.chat_reply_failed_copy)) }
                OutlinedButton(
                    onClick = { onShare(details) },
                ) { Text(stringResource(R.string.chat_reply_failed_share)) }
            }
        }
    }
}
