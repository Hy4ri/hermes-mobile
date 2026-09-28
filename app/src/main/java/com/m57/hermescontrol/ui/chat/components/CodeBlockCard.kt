package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.theme.CodeTerminalText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Terminal-style card for a fenced code block with syntax highlighting,
 * a language badge, and a copy button.
 */
@Composable
fun CodeBlockCard(
    code: String,
    language: String?,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlighted by produceState(
        initialValue = remember(code) { AnnotatedString(code) },
        key1 = code,
    ) {
        value =
            withContext(Dispatchers.Default) {
                highlightSyntax(code)
            }
    }

    CodeTerminalCard(
        textToCopy = code,
        modifier = modifier,
        testTag = "code_block",
        title = language?.takeIf { it.isNotBlank() }?.uppercase(),
        onCopy = onCopy,
        copyContentDescription = "Copy code",
    ) {
        Text(
            text = highlighted,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = CodeTerminalText,
            softWrap = false,
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
