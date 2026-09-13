package com.m57.hermescontrol.ui.skills.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon

@Composable
fun SkillPreviewDialog(
    skillName: String,
    isLoading: Boolean,
    content: String?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
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
                title = { Text(stringResource(R.string.skills_preview_title, skillName)) },
                navigationIcon = NavIcon.Back(onBack = onDismiss),
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

                        content != null -> {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(16.dp)
                                        .verticalScroll(rememberScrollState()),
                            ) {
                                Text(
                                    text = content,
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                )
                            }
                        }

                        else -> {
                            EmptyState(
                                icon = Icons.Filled.Extension,
                                title = stringResource(R.string.skills_preview_empty),
                            )
                        }
                    }
                }
            }
        }
    }
}
