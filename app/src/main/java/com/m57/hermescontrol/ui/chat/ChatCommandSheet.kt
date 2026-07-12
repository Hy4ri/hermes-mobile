package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.ws.CommandCatalog

private data class CommandSheetItem(
    val command: String,
    val description: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatCommandSheet(
    catalog: CommandCatalog,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val categorizedNames =
        remember(catalog.categories) {
            catalog.categories.flatMap { category -> category.pairs.mapNotNull(List<String>::firstOrNull) }.toSet()
        }
    val commands =
        remember(catalog.categories, query) {
            catalog.categories
                .flatMap { category -> category.pairs }
                .mapNotNull(::toSheetItem)
                .filter { it.matches(query) }
        }
    val skills =
        remember(catalog.pairs, categorizedNames, query) {
            catalog.pairs
                .filter { pair -> pair.firstOrNull() !in categorizedNames }
                .mapNotNull(::toSheetItem)
                .filter { it.matches(query) }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_command_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.chat_command_sheet_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.chat_command_sheet_search)) },
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                if (skills.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.chat_command_sheet_skills)) }
                    items(skills, key = { "skill:${it.command}" }) { item ->
                        CommandRow(item, isSkill = true) {
                            onSelect(item.command)
                            onDismiss()
                        }
                    }
                    item { HorizontalDivider() }
                }
                if (commands.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.chat_command_sheet_commands)) }
                    items(commands, key = { "command:${it.command}" }) { item ->
                        CommandRow(item, isSkill = false) {
                            onSelect(item.command)
                            onDismiss()
                        }
                    }
                }
                if (skills.isEmpty() && commands.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.chat_command_sheet_empty),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun CommandRow(
    item: CommandSheetItem,
    isSkill: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(item.command, fontWeight = FontWeight.SemiBold)
        },
        supportingContent = {
            Text(item.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Icon(
                imageVector = if (isSkill) Icons.Default.AutoAwesome else Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

private fun toSheetItem(pair: List<String>): CommandSheetItem? {
    val command = pair.firstOrNull()?.takeIf(String::isNotBlank) ?: return null
    return CommandSheetItem(command = command, description = pair.getOrElse(1) { "" })
}

private fun CommandSheetItem.matches(query: String): Boolean =
    query.isBlank() || command.contains(query, ignoreCase = true) || description.contains(query, ignoreCase = true)
