package com.m57.hermescontrol.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.CodeTerminalBg
import com.m57.hermescontrol.theme.CodeTerminalBorder
import com.m57.hermescontrol.theme.CodeTerminalMuted
import com.m57.hermescontrol.theme.CodeTerminalText
import kotlinx.coroutines.delay

/**
 * Shared container card for terminal-styled code, diff, and JSON viewers.
 *
 * Provides the dark terminal surface, consistent header row with leading icon/label,
 * optional status badges/actions, animated copy-to-clipboard button, content body slot,
 * and optional collapsible footer.
 */
@Composable
fun CodeTerminalCard(
    textToCopy: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    title: String? = null,
    icon: ImageVector? = null,
    clipLabel: String? = null,
    copyContentDescription: String = stringResource(R.string.content_desc_copy),
    onCopy: ((String) -> Unit)? = null,
    headerLeading: (@Composable RowScope.() -> Unit)? = null,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    totalLines: Int? = null,
    collapsibleThreshold: Int = 16,
    isExpanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    expandLabel: String = "Show full",
    collapseLabel: String = "Collapse",
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
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
                    if (headerLeading != null) {
                        headerLeading()
                    } else {
                        if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = CodeTerminalMuted,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        if (!title.isNullOrBlank()) {
                            Text(
                                text = title,
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
                        }
                    }

                    headerActions?.invoke(this)
                }

                IconButton(
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText(clipLabel, textToCopy))
                        copied = true
                        onCopy?.invoke(textToCopy)
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription =
                            if (copied) {
                                stringResource(R.string.content_desc_copied)
                            } else {
                                copyContentDescription
                            },
                        tint = CodeTerminalMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            // Body
            content()

            // Expand / Collapse footer button
            if (totalLines != null && totalLines > collapsibleThreshold && onToggleExpand != null) {
                TextButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isExpanded) collapseLabel else expandLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = CodeTerminalMuted,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
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
