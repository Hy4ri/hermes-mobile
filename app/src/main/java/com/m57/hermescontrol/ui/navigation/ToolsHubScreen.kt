package com.m57.hermescontrol.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.DrawerSection
import com.m57.hermescontrol.HistoryScreen
import com.m57.hermescontrol.ScreenDefinition
import com.m57.hermescontrol.ScreenRegistry
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon

@Composable
fun ToolsHubScreen(
    onOpenDrawer: () -> Unit,
    onNavigate: (ScreenDefinition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections =
        DrawerSection.entries.mapNotNull { section ->
            val entries =
                ScreenRegistry.ALL_SCREENS.filter {
                    it.drawerSection == section &&
                        it.key != ChatScreen &&
                        it.key != HistoryScreen &&
                        it.key != SettingsScreen
                }
            section.takeIf { entries.isNotEmpty() }?.let { it to entries }
        }

    HermesScaffold(
        modifier = modifier,
        pinTopBar = true,
        title = {
            Text(
                stringResource(com.m57.hermescontrol.R.string.tools_hub_title),
                fontWeight = FontWeight.SemiBold,
            )
        },
        navigationIcon = NavIcon.Menu(onOpenDrawer),
    ) { _ ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GridView,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Column(modifier = Modifier.padding(start = 14.dp)) {
                            Text(
                                text = stringResource(com.m57.hermescontrol.R.string.tools_hub_manage_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = stringResource(com.m57.hermescontrol.R.string.tools_hub_manage_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            sections.forEach { (section, entries) ->
                item(key = "header_${section.name}") {
                    Text(
                        text = stringResource(section.titleRes).uppercase(),
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(entries, key = { it.key::class.simpleName.orEmpty() }) { entry ->
                    ToolRow(entry = entry, onClick = { onNavigate(entry) })
                }
            }
        }
    }
}

@Composable
private fun ToolRow(
    entry: ScreenDefinition,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = stringResource(entry.labelRes),
                modifier = Modifier.weight(1f).padding(start = 14.dp),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
