package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.CodeTerminalText
import com.m57.hermescontrol.ui.chat.components.CodeTerminalCard
import com.m57.hermescontrol.ui.chat.components.highlightSyntax
import com.m57.hermescontrol.ui.chat.tool.ToolJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ToolRawJsonView(
    rawContent: String,
    modifier: Modifier = Modifier,
) {
    var formatJson by remember { mutableStateOf(true) }
    val clampedRaw = remember(rawContent) { ToolJson.clampForDisplay(rawContent) }

    // Full pretty text (for copy) + clamped copy (for display); null when formatting changes nothing.
    val formatted by produceState<Pair<String, String>?>(initialValue = null, key1 = rawContent) {
        value =
            withContext(Dispatchers.Default) {
                val full = ToolJson.prettyPrintJson(rawContent)
                if (full != rawContent) full to ToolJson.clampForDisplay(full) else null
            }
    }

    val isFormatDifferent = formatted != null
    val displayText = formatted?.takeIf { formatJson }?.second ?: clampedRaw
    val copyText = formatted?.takeIf { formatJson }?.first ?: rawContent

    val highlighted by produceState(
        initialValue = remember(clampedRaw) { AnnotatedString(clampedRaw) },
        key1 = displayText,
    ) {
        value =
            withContext(Dispatchers.Default) {
                highlightSyntax(displayText)
            }
    }

    CodeTerminalCard(
        textToCopy = copyText,
        modifier = modifier,
        testTag = "tool_raw_json",
        title = "JSON",
        copyContentDescription = stringResource(R.string.content_desc_copy),
        headerActions = {
            if (isFormatDifferent) {
                Text(
                    text =
                        if (formatJson) {
                            stringResource(R.string.chat_tool_compact_json)
                        } else {
                            stringResource(R.string.chat_tool_format_json)
                        },
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        ),
                    modifier =
                        Modifier
                            .testTag("tool_json_format_toggle")
                            .clickable(role = Role.Button) { formatJson = !formatJson }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        },
    ) {
        SelectionContainer {
            Text(
                text = highlighted,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = CodeTerminalText,
                softWrap = true,
            )
        }
    }
}
