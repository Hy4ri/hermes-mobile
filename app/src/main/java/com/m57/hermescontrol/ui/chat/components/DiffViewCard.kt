package com.m57.hermescontrol.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.theme.CodeDiffAddBg
import com.m57.hermescontrol.theme.CodeDiffAddText
import com.m57.hermescontrol.theme.CodeDiffDeleteBg
import com.m57.hermescontrol.theme.CodeDiffDeleteText
import com.m57.hermescontrol.theme.CodeDiffHunkBg
import com.m57.hermescontrol.theme.CodeDiffHunkText
import com.m57.hermescontrol.theme.CodeTerminalBg
import com.m57.hermescontrol.theme.CodeTerminalBorder
import com.m57.hermescontrol.theme.CodeTerminalMuted
import com.m57.hermescontrol.theme.CodeTerminalText
import kotlinx.coroutines.delay

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
                        line.drop(4)
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
    val context = LocalContext.current
    val parsed = remember(diffText, filePath) { parseDiffText(diffText, filePath) }
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val displayLines =
        if (expanded || parsed.lines.size <= 16) {
            parsed.lines
        } else {
            parsed.lines.take(16)
        }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("diff_view_card"),
        shape = RoundedCornerShape(8.dp),
        color = CodeTerminalBg,
        border = BorderStroke(1.dp, CodeTerminalBorder),
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            // Header bar
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = CodeTerminalMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = parsed.filePath ?: "diff",
                        style =
                            MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = CodeTerminalText,
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))

                    // Addition / Deletion badges
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
                }

                IconButton(
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("diff", diffText))
                        copied = true
                        onCopy(diffText)
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy diff",
                        tint = CodeTerminalMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            // Diff lines body
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

            // Expand / Collapse footer button for diffs > 16 lines
            if (parsed.lines.size > 16) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text =
                                if (expanded) {
                                    "Collapse diff"
                                } else {
                                    "Show full diff (${parsed.lines.size} lines)"
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = CodeTerminalMuted,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = CodeTerminalMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
