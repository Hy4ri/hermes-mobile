package com.m57.hermescontrol.ui.profiles.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.HubSkill
import com.m57.hermescontrol.data.model.Skill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsStep(
    availableSkills: List<Skill>,
    useDefaultSkills: Boolean,
    onUseDefaultSkillsChange: (Boolean) -> Unit,
    selectedSkills: Set<String>,
    onSelectedSkillsChange: (Set<String>) -> Unit,
    addedHubSkills: List<String>,
    onAddedHubSkillsChange: (List<String>) -> Unit,
    hubSearchResults: List<HubSkill>,
    isSearchingHub: Boolean,
    onSearchHub: (String) -> Unit,
) {
    var hubSearchQuery by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = useDefaultSkills,
                onCheckedChange = onUseDefaultSkillsChange,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = stringResource(R.string.profiles_builder_skills_default),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.profiles_builder_skills_default_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        if (!useDefaultSkills) {
            Text(
                text = stringResource(R.string.profiles_builder_skills_local),
                style = MaterialTheme.typography.titleSmall,
            )
            LazyColumn(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(availableSkills) { skill ->
                    val isChecked = selectedSkills.contains(skill.name)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) {
                                        onSelectedSkillsChange(selectedSkills - skill.name)
                                    } else {
                                        onSelectedSkillsChange(selectedSkills + skill.name)
                                    }
                                }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                if (checked == true) {
                                    onSelectedSkillsChange(selectedSkills + skill.name)
                                } else {
                                    onSelectedSkillsChange(selectedSkills - skill.name)
                                }
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = skill.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            skill.description?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Browse and add Skills from Hermes Hub
            Text(
                text = stringResource(R.string.profiles_builder_skills_hub_title),
                style = MaterialTheme.typography.titleSmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = hubSearchQuery,
                    onValueChange = { hubSearchQuery = it },
                    label = { Text(stringResource(R.string.profiles_builder_skills_search_hub)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { onSearchHub(hubSearchQuery) },
                    enabled = hubSearchQuery.isNotBlank() && !isSearchingHub,
                ) {
                    if (isSearchingHub) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                        )
                    }
                }
            }

            if (addedHubSkills.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.profiles_builder_skills_added),
                    style = MaterialTheme.typography.labelMedium,
                )
                LazyColumn(
                    modifier =
                        Modifier
                            .height(80.dp)
                            .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(addedHubSkills) { skillName ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(4.dp),
                                    ).padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = skillName,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(
                                onClick = { onAddedHubSkillsChange(addedHubSkills - skillName) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (hubSearchResults.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.profiles_builder_skills_search_results),
                    style = MaterialTheme.typography.labelMedium,
                )
                LazyColumn(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(hubSearchResults) { skill ->
                        val isAdded = addedHubSkills.contains(skill.name)
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = skill.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                skill.description?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!skill.source.isNullOrBlank()) {
                                    Text(
                                        text = "Source: ${skill.source}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontStyle = FontStyle.Italic,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (isAdded) {
                                        onAddedHubSkillsChange(addedHubSkills - skill.name)
                                    } else {
                                        onAddedHubSkillsChange(addedHubSkills + skill.name)
                                    }
                                },
                                colors =
                                    if (isAdded) {
                                        ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                        )
                                    } else {
                                        ButtonDefaults.buttonColors()
                                    },
                            ) {
                                if (isAdded) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp),
                                    )
                                } else {
                                    Text("Add")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
