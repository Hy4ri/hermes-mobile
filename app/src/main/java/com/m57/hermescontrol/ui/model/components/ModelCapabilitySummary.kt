package com.m57.hermescontrol.ui.model.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.data.model.ModelCapabilities
import com.m57.hermescontrol.data.model.reasoningSupport

@Composable
fun ModelCapabilitySummary(capabilities: ModelCapabilities?) {
    if (capabilities == null) return
    val labels =
        buildList {
            capabilities.supports_tools?.let { add(if (it) "Tools" else "No tools") }
            capabilities.supports_vision?.let { add(if (it) "Vision" else "No vision") }
            capabilities.reasoningSupport?.let { add(if (it) "Reasoning" else "No reasoning") }
            if (capabilities.reasoningSupport == true && capabilities.can_disable_reasoning == false) {
                add("Reasoning always on")
            }
            capabilities.context_window?.let { add("${it / 1000}K ctx") }
            capabilities.max_output_tokens?.let { add("${it / 1000}K output") }
            capabilities.model_family?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
    if (labels.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = labels.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
