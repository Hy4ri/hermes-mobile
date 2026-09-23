package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.theme.CodeDiffAddBg
import com.m57.hermescontrol.theme.CodeDiffAddText
import com.m57.hermescontrol.theme.CodeDiffDeleteBg
import com.m57.hermescontrol.theme.CodeDiffDeleteText
import com.m57.hermescontrol.theme.CodeDiffHunkBg
import com.m57.hermescontrol.theme.CodeDiffHunkText
import com.m57.hermescontrol.theme.CodeTerminalMuted
import com.m57.hermescontrol.theme.CodeTerminalText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DiffLineType {
    FILE_HEADER,
    HUNK_HEADER,
    ADDED,
    DELETED,
    CONTEXT,
}

data class ParsedDiffLine(
    val type: DiffLineType,
    val text: String,
)

data class ParsedDiffResult(
    val filePath: String?,
    val lines: List<ParsedDiffLine>,
    val additionsCount: Int,
    val deletionsCount: Int,
)

/**
 * Parses unified diff or patch text into structured lines and line counts.
 */
fun parseDiffText(
    diffText: String,
    defaultPath: String? = null,
): ParsedDiffResult {
    if (diffText.isBlank()) {
        return ParsedDiffResult(
            filePath = defaultPath,
            lines = emptyList(),
            additionsCount = 0,
            deletionsCount = 0,
        )
    }

    val rawLines = diffText.lines()
    val parsedLines = mutableListOf<ParsedDiffLine>()
    var extractedPath: String? = defaultPath
    var additions = 0
    var deletions = 0

    for (line in rawLines) {
        when {
            line.startsWith("--- ") || line.startsWith("+++ ") -> {
                if (extractedPath == null || extractedPath == defaultPath) {
                    val candidate =
                        line
                            .drop(4)
                            .trim()
                            .removePrefix("a/")
                            .removePrefix("b/")
                            .split("\t")
                            .firstOrNull()
                    if (!candidate.isNullOrBlank() && candidate != "/dev/null") {
                        extractedPath = candidate
                    }
                }
                parsedLines.add(ParsedDiffLine(DiffLineType.FILE_HEADER, line))
            }

            line.startsWith("*** Update File:") ||
                line.startsWith("*** Add File:") ||
                line.startsWith("*** Delete File:") -> {
                val candidate = line.substringAfter(":").trim()
                if (candidate.isNotBlank()) {
                    extractedPath = candidate
                }
                parsedLines.add(ParsedDiffLine(DiffLineType.FILE_HEADER, line))
            }

            line.startsWith("@@ ") || line.startsWith("*** ") -> {
                parsedLines.add(ParsedDiffLine(DiffLineType.HUNK_HEADER, line))
            }

            line.startsWith("+") -> {
                additions++
                parsedLines.add(ParsedDiffLine(DiffLineType.ADDED, line))
            }

            line.startsWith("-") -> {
                deletions++
                parsedLines.add(ParsedDiffLine(DiffLineType.DELETED, line))
            }

            else -> {
                parsedLines.add(ParsedDiffLine(DiffLineType.CONTEXT, line))
            }
        }
    }

    return ParsedDiffResult(
        filePath = extractedPath ?: defaultPath,
        lines = parsedLines,
        additionsCount = additions,
        deletionsCount = deletions,
    )
}

/**
 * Interactive diff view card for file edit & patch tool outputs.
 */
@Composable
fun DiffViewCard(
    diffText: String,
    filePath: String? = null,
    onCopy: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val parsed = remember(diffText, filePath) { parseDiffText(diffText, filePath) }
    var expanded by remember { mutableStateOf(false) }

    val displayLines =
        if (expanded || parsed.lines.size <= 16) {
            parsed.lines
        } else {
            parsed.lines.take(16)
        }

    CodeTerminalCard(
        textToCopy = diffText,
        modifier = modifier,
        testTag = "diff_view_card",
        title = parsed.filePath ?: "diff",
        icon = Icons.AutoMirrored.Filled.InsertDriveFile,
        clipLabel = "diff",
        copyContentDescription = "Copy diff",
        onCopy = onCopy,
        headerActions = {
            if (parsed.additionsCount > 0) {
                Text(
                    text = "+${parsed.additionsCount}",
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = CodeDiffAddText,
                        ),
                )
                Spacer(Modifier.width(6.dp))
            }
            if (parsed.deletionsCount > 0) {
                Text(
                    text = "-${parsed.deletionsCount}",
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = CodeDiffDeleteText,
                        ),
                )
                Spacer(Modifier.width(6.dp))
            }
        },
        totalLines = parsed.lines.size,
        isExpanded = expanded,
        onToggleExpand = { expanded = !expanded },
        expandLabel = "Show full diff (${parsed.lines.size} lines)",
        collapseLabel = "Collapse diff",
    ) {
        val verticalScrollModifier =
            if (expanded) {
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())
            } else {
                Modifier
            }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(verticalScrollModifier)
                    .horizontalScroll(rememberScrollState())
                    .width(IntrinsicSize.Max),
        ) {
            displayLines.forEach { line ->
                val (bgColor, textColor) =
                    when (line.type) {
                        DiffLineType.ADDED -> CodeDiffAddBg to CodeDiffAddText
                        DiffLineType.DELETED -> CodeDiffDeleteBg to CodeDiffDeleteText
                        DiffLineType.HUNK_HEADER -> CodeDiffHunkBg to CodeDiffHunkText
                        DiffLineType.FILE_HEADER -> Color.Transparent to CodeTerminalMuted
                        DiffLineType.CONTEXT -> Color.Transparent to CodeTerminalText
                    }

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(bgColor)
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = line.text,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = textColor,
                            ),
                    )
                }
            }
        }
    }
}

/**
 * Interactive file content view card for read_file & write_file tool outputs.
 * Mirrors [DiffViewCard] with syntax highlighting and file path header.
 */
@Composable
fun FileViewCard(
    content: String,
    filePath: String? = null,
    onCopy: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val lines = remember(content) { content.lines() }
    val displayText =
        remember(content, expanded) {
            if (expanded || lines.size <= 16) {
                content
            } else {
                lines.take(16).joinToString("\n")
            }
        }
    val highlighted by produceState(
        initialValue = remember(displayText) { AnnotatedString(displayText) },
        key1 = displayText,
    ) {
        value =
            withContext(Dispatchers.Default) {
                highlightSyntax(displayText)
            }
    }

    CodeTerminalCard(
        textToCopy = content,
        modifier = modifier,
        testTag = "file_view_card",
        title = filePath?.takeIf { it.isNotBlank() } ?: "file",
        icon = Icons.AutoMirrored.Filled.InsertDriveFile,
        clipLabel = "file",
        copyContentDescription = "Copy file content",
        onCopy = onCopy,
        headerActions = {
            if (lines.isNotEmpty()) {
                Text(
                    text = "${lines.size} lines",
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = CodeTerminalMuted,
                        ),
                )
                Spacer(Modifier.width(6.dp))
            }
        },
        totalLines = lines.size,
        isExpanded = expanded,
        onToggleExpand = { expanded = !expanded },
        expandLabel = "Show full file (${lines.size} lines)",
        collapseLabel = "Collapse file",
    ) {
        val verticalScrollModifier =
            if (expanded) {
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())
            } else {
                Modifier
            }

        SelectionContainer {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(verticalScrollModifier)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = highlighted,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = CodeTerminalText,
                        ),
                    softWrap = false,
                )
            }
        }
    }
}
