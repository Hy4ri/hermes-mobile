package com.m57.hermescontrol.ui.kanban.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.common.SearchBar

@Composable
private fun KanbanLayoutSample() {
    HermesControlTheme {
        Surface {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchBar(
                    query = "",
                    onQueryChange = {},
                    placeholder = "Filter tasks…",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                KanbanBoardTabs(
                    boardNames = listOf("Default", "hermes-mobile", "Personal"),
                    selectedIndex = 0,
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Column(Modifier.width(150.dp)) {
                        KanbanColumnTitle(name = "scheduled", count = 12)
                        KanbanColumnTitle(name = "triage", count = 3)
                    }
                    Card(Modifier.width(56.dp).height(180.dp)) {
                        KanbanCollapsedColumnLabel(
                            name = "scheduled",
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                        )
                    }
                    Card(Modifier.width(56.dp).height(180.dp)) {
                        KanbanCollapsedColumnLabel(name = "running", modifier = Modifier.fillMaxWidth().height(180.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun KanbanPrioritySample() {
    HermesControlTheme {
        Surface {
            Column(Modifier.padding(16.dp)) {
                KanbanPriorityChips(selected = 2, onSelect = {})
            }
        }
    }
}

@Preview(name = "Light", widthDp = 360, heightDp = 360)
@Preview(name = "Dark", widthDp = 360, heightDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Large text narrow", widthDp = 320, heightDp = 420, fontScale = 1.5f)
@Composable
fun KanbanLayoutPartsPreview() = KanbanLayoutSample()

@Preview(name = "Light", widthDp = 360, heightDp = 160)
@Preview(name = "Dark", widthDp = 360, heightDp = 160, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Large text narrow", widthDp = 320, heightDp = 220, fontScale = 1.5f)
@Composable
fun KanbanPriorityChipsPreview() = KanbanPrioritySample()
