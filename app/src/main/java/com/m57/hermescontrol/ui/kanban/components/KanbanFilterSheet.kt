package com.m57.hermescontrol.ui.kanban.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanFilterSheet(
    assignees: List<String>,
    tenants: List<String>,
    selectedAssignee: String?,
    selectedTenant: String?,
    includeArchived: Boolean,
    groupRunning: Boolean,
    onSelectAssignee: (String?) -> Unit,
    onSelectTenant: (String?) -> Unit,
    onToggleIncludeArchived: (Boolean) -> Unit,
    onToggleGroupRunning: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Filters & View Options",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onClearFilters) {
                    Text("Clear all")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Show Archived Switch
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
            ) {
                Text("Show archived tasks", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = includeArchived,
                    onCheckedChange = onToggleIncludeArchived,
                )
            }

            // Group Running Switch
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
            ) {
                Text("Group running lane by profile", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = groupRunning,
                    onCheckedChange = onToggleGroupRunning,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Assignee Filter
            Text(
                text = "Assignee",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelectAssignee(null) }
                        .padding(vertical = 4.dp),
            ) {
                RadioButton(
                    selected = selectedAssignee == null,
                    onClick = { onSelectAssignee(null) },
                )
                Text("All assignees", style = MaterialTheme.typography.bodyMedium)
            }

            assignees.forEach { assignee ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAssignee(assignee) }
                            .padding(vertical = 4.dp),
                ) {
                    RadioButton(
                        selected = selectedAssignee == assignee,
                        onClick = { onSelectAssignee(assignee) },
                    )
                    Text(assignee, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (tenants.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "Tenant",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelectTenant(null) }
                            .padding(vertical = 4.dp),
                ) {
                    RadioButton(
                        selected = selectedTenant == null,
                        onClick = { onSelectTenant(null) },
                    )
                    Text("All tenants", style = MaterialTheme.typography.bodyMedium)
                }

                tenants.forEach { tenant ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelectTenant(tenant) }
                                .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = selectedTenant == tenant,
                            onClick = { onSelectTenant(tenant) },
                        )
                        Text(tenant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
