package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
private fun SelfImprovementReviewCard(
    content: String,
    modifier: Modifier = Modifier,
) {
    val cleanText =
        content
            .removePrefix("💾")
            .replace(Regex("^\\s*Self-improvement review:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
    val isSkill =
        cleanText.contains("skill", ignoreCase = true) ||
            cleanText.contains("SKILL.md", ignoreCase = true)
    val icon: ImageVector = if (isSkill) Icons.Filled.Build else Icons.Filled.Psychology
    val title =
        if (isSkill) {
            "Self-Improvement Review • Skill Patched"
        } else {
            "Self-Improvement Review • Memory Updated"
        }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("self_improvement_review_card"),
        shape = RoundedCornerShape(10.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = cleanText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SystemBubble(
    message: ChatMessage,
    onRespondApproval: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (message.content.contains("Self-improvement review:", ignoreCase = true)) {
        SelfImprovementReviewCard(content = message.content, modifier = modifier)
        return
    }

    val approvalInfo = message.approvalInfo
    var confirmAlways by remember(message.id) { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message.content,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        )

        // Approval action buttons — dynamic from backend `choices`
        // (desktop `approval.tsx` parity: once/session/always/deny,
        // smart-denied → once/deny only, allowPermanent=false hides Always).
        if (approvalInfo != null) {
            Spacer(Modifier.height(8.dp))
            val rawChoices = approvalInfo.choices ?: listOf("once", "deny")
            val visibleChoices =
                rawChoices.filter { choice ->
                    when (choice) {
                        "always" -> approvalInfo.allowPermanent != false
                        else -> true
                    }
                }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (choice in visibleChoices) {
                    when (choice) {
                        "deny" -> {
                            FilledTonalButton(
                                onClick = { onRespondApproval("deny") },
                                modifier =
                                    Modifier
                                        .height(36.dp)
                                        .testTag("deny_button"),
                                colors =
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                    ),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Deny")
                            }
                        }

                        "session" -> {
                            FilledTonalButton(
                                onClick = { onRespondApproval("session") },
                                modifier =
                                    Modifier
                                        .height(36.dp)
                                        .testTag("session_button"),
                                colors =
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    ),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Allow session")
                            }
                        }

                        "always" -> {
                            FilledTonalButton(
                                onClick = { confirmAlways = true },
                                modifier =
                                    Modifier
                                        .height(36.dp)
                                        .testTag("always_button"),
                                colors =
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    ),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Always allow")
                            }
                        }

                        else -> {
                            // "once" (+ legacy "approve" → normalized to once in VM)
                            FilledTonalButton(
                                onClick = { onRespondApproval(choice) },
                                modifier =
                                    Modifier
                                        .height(36.dp)
                                        .testTag("approve_button"),
                                colors =
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (choice == "approve") "Approve" else "Run")
                            }
                        }
                    }
                }
            }
            // "Always allow" persists the pattern permanently — confirm first
            // (desktop confirm-modal parity).
            if (confirmAlways) {
                AlertDialog(
                    onDismissRequest = { confirmAlways = false },
                    title = { Text("Always allow this command?") },
                    text = {
                        Text(
                            "This persists the pattern permanently so future " +
                                "matching commands run without asking.",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmAlways = false
                                onRespondApproval("always")
                            },
                        ) {
                            Text("Always allow")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmAlways = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }
        }
    }
}
