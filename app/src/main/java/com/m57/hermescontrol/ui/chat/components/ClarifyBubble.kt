package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.ClarifyUi

// ── ClarifyBubble ─────────────────────────────────────────────────────────

/**
 * Centered dashed-border bubble showing a clarify question (or batch of questions) with
 * selectable option chips, multi-select toggles, open-ended input, and a dismiss button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClarifyBubble(
    clarifyRequest: ClarifyUi,
    onRespondSingle: (String) -> Unit,
    onRespondBatch: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val questions = clarifyRequest.resolvedQuestions
    val isBatch = questions.size > 1

    var selectedChoicesByQid by remember(clarifyRequest) {
        mutableStateOf<Map<String, Set<String>>>(emptyMap())
    }
    var customTextByQid by remember(clarifyRequest) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp)
                .testTag("clarify_bubble"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
            ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            questions.forEachIndexed { index, q ->
                val lockedAnswer = clarifyRequest.lockedAnswers[q.qid]
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }

                if (isBatch) {
                    Text(
                        text = "${index + 1}. ${q.question}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = q.question,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (lockedAnswer != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Answered: $lockedAnswer",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                } else {
                    if (q.multiSelect) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Select all that apply",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (q.choices.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            q.choices.forEach { choice ->
                                val selectedSet = selectedChoicesByQid[q.qid] ?: emptySet()
                                val isSelected = selectedSet.contains(choice)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (q.multiSelect) {
                                            val updated = if (isSelected) selectedSet - choice else selectedSet + choice
                                            selectedChoicesByQid = selectedChoicesByQid + (q.qid to updated)
                                        } else {
                                            if (!isBatch && customTextByQid[q.qid].isNullOrBlank()) {
                                                // Fast 1-tap respond for lone single-select question when no custom text is entered
                                                onRespondSingle(choice)
                                            } else {
                                                val updated = if (isSelected) emptySet() else setOf(choice)
                                                selectedChoicesByQid = selectedChoicesByQid + (q.qid to updated)
                                            }
                                        }
                                    },
                                    label = { Text(choice) },
                                    leadingIcon =
                                        if (isSelected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    val typed = customTextByQid[q.qid].orEmpty()
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { newText ->
                            customTextByQid = customTextByQid + (q.qid to newText)
                        },
                        label = {
                            Text(
                                if (q.choices.isEmpty()) {
                                    stringResource(R.string.message_your_response)
                                } else {
                                    "Other (optional)"
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                val hasAnyInput =
                    questions.any { q ->
                        (selectedChoicesByQid[q.qid]?.isNotEmpty() == true) ||
                            (!customTextByQid[q.qid].isNullOrBlank())
                    }

                FilledTonalButton(
                    onClick = {
                        if (isBatch) {
                            val answers =
                                questions.associate { q ->
                                    val selectedList = selectedChoicesByQid[q.qid]?.toList().orEmpty()
                                    val custom = customTextByQid[q.qid]?.trim().orEmpty()
                                    val finalAns =
                                        when {
                                            selectedList.isNotEmpty() && custom.isNotEmpty() -> {
                                                (selectedList + custom).joinToString(", ")
                                            }

                                            selectedList.isNotEmpty() -> {
                                                selectedList.joinToString(", ")
                                            }

                                            else -> {
                                                custom
                                            }
                                        }
                                    q.qid to finalAns
                                }
                            onRespondBatch(answers)
                        } else {
                            val q = questions.first()
                            val selectedList = selectedChoicesByQid[q.qid]?.toList().orEmpty()
                            val custom = customTextByQid[q.qid]?.trim().orEmpty()
                            val finalAns =
                                when {
                                    selectedList.isNotEmpty() && custom.isNotEmpty() -> {
                                        (selectedList + custom).joinToString(", ")
                                    }

                                    selectedList.isNotEmpty() -> {
                                        selectedList.joinToString(", ")
                                    }

                                    else -> {
                                        custom
                                    }
                                }
                            if (finalAns.isNotBlank()) {
                                onRespondSingle(finalAns)
                            }
                        }
                    },
                    enabled = hasAnyInput,
                ) {
                    Text(if (isBatch) "Submit All" else "Send")
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}

/**
 * Backward-compatible overload for legacy callers and unit tests.
 */
@Composable
fun ClarifyBubble(
    text: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ClarifyBubble(
        clarifyRequest = ClarifyUi(text = text, options = options),
        onRespondSingle = onOptionSelected,
        onRespondBatch = { answers ->
            answers.values.firstOrNull()?.let(onOptionSelected)
        },
        onDismiss = onDismiss,
        modifier = modifier,
    )
}
