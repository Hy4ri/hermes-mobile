package com.m57.hermescontrol.ui.model.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.SearchBar

/**
 * Reusable model picker used by both the global model screen and the
 * in-session `/model` hot-swap (issue #589). Selecting a provider/model pair
 * invokes [onSelect]; the consumer decides what to do with it (global
 * `config.yaml` assignment vs. a session-scoped `/model` slash command).
 */
@Composable
fun ModelPickerDialog(
    providers: List<ModelProvider>,
    title: String,
    isLoading: Boolean = false,
    onSelect: (provider: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickerQuery by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (isLoading) {
                    LoadingState(
                        subtitle = "Loading models…",
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (providers.isEmpty()) {
                    Text(
                        text = "No models available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SearchBar(
                        query = pickerQuery,
                        onQueryChange = { pickerQuery = it },
                        placeholder = "Search providers...",
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(modifier = Modifier.height(400.dp)) {
                        items(
                            providers.filter {
                                pickerQuery.isBlank() ||
                                    it.name.contains(pickerQuery, ignoreCase = true) ||
                                    it.slug.contains(pickerQuery, ignoreCase = true)
                            },
                            key = { it.slug },
                        ) { provider ->
                            val models =
                                provider.models.orEmpty().filter {
                                    pickerQuery.isBlank() ||
                                        it.contains(pickerQuery, ignoreCase = true)
                                }

                            if (models.isNotEmpty()) {
                                Text(
                                    text = provider.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )

                                models.forEach { model ->
                                    OutlinedButton(
                                        onClick = { onSelect(provider.slug, model) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        contentPadding =
                                            androidx.compose.foundation.layout
                                                .PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        Text(text = model, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
