package com.m57.hermescontrol.ui.bots

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.ui.common.BotAvatar

@Composable
fun CreateGroupChatDialog(
    availableBots: List<ProfileInfo>,
    onDismiss: () -> Unit,
    onCreateGroup: (groupName: String, selectedBotNames: List<String>) -> Unit,
) {
    var groupName by remember { mutableStateOf("") }
    var selectedBots by remember { mutableStateOf(setOf<String>()) }

    val defaultPlaceholder =
        if (selectedBots.isNotEmpty()) {
            selectedBots.joinToString(", ")
        } else {
            stringResource(R.string.bots_create_group_name_placeholder)
        }

    val effectiveName = groupName.ifBlank { selectedBots.joinToString(", ") }
    val isValid = selectedBots.size >= 2 && effectiveName.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.bots_create_group_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.bots_create_group_name_label)) },
                    placeholder = { Text(defaultPlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.bots_create_group_select_bots),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))

                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                ) {
                    items(availableBots, key = { it.name }) { bot ->
                        val isChecked = selectedBots.contains(bot.name)
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedBots =
                                            if (isChecked) {
                                                selectedBots - bot.name
                                            } else {
                                                selectedBots + bot.name
                                            }
                                    }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selectedBots =
                                        if (checked) {
                                            selectedBots + bot.name
                                        } else {
                                            selectedBots - bot.name
                                        }
                                },
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            BotAvatar(
                                name = bot.name,
                                avatar = bot.botMeta()?.avatar,
                                size = 28.dp,
                                showPresence = false,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = bot.effectiveTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "@${bot.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid) {
                        onCreateGroup(effectiveName.trim(), selectedBots.toList())
                    }
                },
                enabled = isValid,
            ) {
                Text(stringResource(R.string.bots_create_group_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
