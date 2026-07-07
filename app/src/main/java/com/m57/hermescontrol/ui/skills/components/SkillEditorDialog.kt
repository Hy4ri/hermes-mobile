package com.m57.hermescontrol.ui.skills.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon

@Composable
internal fun SkillEditorDialog(
    skillName: String,
    isLoading: Boolean,
    initialContent: String?,
    isSaving: Boolean,
    saveSuccess: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    onClearSaveSuccess: () -> Unit,
) {
    var contentText by remember(initialContent) { mutableStateOf(initialContent.orEmpty()) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val hasChanges =
        remember(initialContent, contentText) {
            val original = initialContent.orEmpty()
            original != contentText
        }

    LaunchedEffect(saveSuccess) {
        if (saveSuccess) {
            onClearSaveSuccess()
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = {
            if (hasChanges) {
                showDiscardConfirm = true
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
        ) {
            HermesScaffold(
                title = { Text(stringResource(R.string.skills_edit_title, skillName)) },
                navigationIcon =
                    NavIcon.Back(
                        onBack = {
                            if (hasChanges) {
                                showDiscardConfirm = true
                            } else {
                                onDismiss()
                            }
                        },
                    ),
                actions = {
                    if (!isLoading) {
                        IconButton(
                            onClick = { onSave(contentText) },
                            enabled = !isSaving,
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.fillMaxSize(0.6f),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Save,
                                    contentDescription = stringResource(R.string.content_desc_save_changes),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize(),
                ) {
                    when {
                        isLoading -> {
                            LoadingState()
                        }

                        else -> {
                            OutlinedTextField(
                                value = contentText,
                                onValueChange = { contentText = it },
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                textStyle =
                                    TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                placeholder = {
                                    Text(stringResource(R.string.skills_content_placeholder))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.skills_discard_title)) },
            text = { Text(stringResource(R.string.skills_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.action_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
