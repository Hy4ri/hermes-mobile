package com.m57.hermescontrol.ui.system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.Spacing
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.system.SystemUiState
import com.m57.hermescontrol.ui.system.SystemViewModel

fun LazyListScope.credentialsSection(
    state: SystemUiState,
    spacing: Spacing,
    viewModel: SystemViewModel,
    onRemoveRequest: (Pair<String, Int>) -> Unit,
) {
    item {
        SectionHeader(
            title = stringResource(R.string.system_sec_credentials),
            trailing = {
                val hasCreds = state.credentials.any { it.entries?.isNotEmpty() == true }
                if (hasCreds) {
                    Text(
                        text = "${state.credentials.sumOf { it.entries?.size ?: 0 }} key(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
    item {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
        ) {
            Column(modifier = Modifier.padding(spacing.md)) {
                // Add credential form
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    OutlinedTextField(
                        value = state.credProvider,
                        onValueChange = viewModel::updateCredProvider,
                        label = { Text(stringResource(R.string.system_credentials_provider)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(modifier = Modifier.height(spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    OutlinedTextField(
                        value = state.credKey,
                        onValueChange = viewModel::updateCredKey,
                        label = { Text(stringResource(R.string.system_credentials_api_key)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    OutlinedTextField(
                        value = state.credLabel,
                        onValueChange = viewModel::updateCredLabel,
                        label = { Text(stringResource(R.string.system_credentials_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(modifier = Modifier.height(spacing.sm))
                Button(
                    onClick = { viewModel.addCredential() },
                    enabled = !state.addingCred && state.credKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.addingCred) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(spacing.sm))
                    } else {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                    }
                    Text(stringResource(R.string.system_credentials_add_key), maxLines = 1, softWrap = false)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))

                // Provider list with entries
                if (state.credentials.isEmpty()) {
                    Text(
                        text = stringResource(R.string.system_credentials_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = spacing.sm),
                    )
                }

                state.credentials.forEach { provider ->
                    Text(
                        text = provider.provider ?: "?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = spacing.xs),
                    )
                    provider.entries?.forEach { entry ->
                        CredentialEntryRow(
                            entry = entry,
                            providerName = provider.provider ?: "",
                            onRemove = { onRemoveRequest(Pair(provider.provider ?: "", entry.index ?: 0)) },
                            spacing = spacing,
                        )
                    }
                }
            }
        }
    }
}
