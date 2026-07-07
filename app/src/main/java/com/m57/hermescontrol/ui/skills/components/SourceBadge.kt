package com.m57.hermescontrol.ui.skills.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SourceBadge(source: String) {
    val (color, label) =
        when (source) {
            "hub" -> MaterialTheme.colorScheme.tertiary to "hub"
            "built-in" -> MaterialTheme.colorScheme.secondary to "built-in"
            "optional" -> MaterialTheme.colorScheme.outline to "opt"
            else -> MaterialTheme.colorScheme.outline to source
        }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
