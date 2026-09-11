package com.m57.hermescontrol.ui.sessions.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.Spacing

@Composable
fun SessionsBulkActionBar(
    visible: Boolean,
    allVisibleSessionsSelected: Boolean,
    selectedCount: Int,
    isDeletingBulk: Boolean,
    spacing: Spacing,
    statusColors: HermesStatusColors,
    onSelectAllToggle: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.md, vertical = spacing.sm),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onSelectAllToggle) {
                        Icon(
                            imageVector =
                                if (allVisibleSessionsSelected) {
                                    Icons.Filled.Close
                                } else {
                                    Icons.Filled.SelectAll
                                },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(spacing.xs))
                        Text(
                            if (allVisibleSessionsSelected) {
                                stringResource(R.string.sessions_action_deselect_all)
                            } else {
                                stringResource(R.string.sessions_action_select_all)
                            },
                        )
                    }

                    Button(
                        onClick = onDeleteSelected,
                        enabled = !isDeletingBulk,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = statusColors.error,
                            ),
                    ) {
                        if (isDeletingBulk) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = statusColors.onError,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(spacing.xs))
                        Text(
                            stringResource(
                                R.string.sessions_action_delete_n,
                                selectedCount,
                            ),
                        )
                    }
                }
            }
        }
    }
}
