package com.m57.hermescontrol.ui.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.AnalyticsDailyEntry
import com.m57.hermescontrol.data.model.ModelsAnalyticsModelEntry
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

private val DAY_OPTIONS = listOf(7, 30, 90)

@Composable
fun AnalyticsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: AnalyticsViewModel = viewModel { AnalyticsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (state.usage == null && state.models == null) viewModel.load()
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_analytics)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.load() },
        modifier = modifier,
    ) {
        when {
            state.isLoading && state.usage == null -> LoadingState()

            state.errorMessage != null && state.usage == null ->
                ErrorState(
                    message = state.errorMessage ?: stringResource(R.string.error_unknown),
                    onRetry = { viewModel.load() },
                )

            state.usage == null ->
                EmptyState(
                    title = stringResource(R.string.analytics_empty_title),
                    subtitle = stringResource(R.string.analytics_empty_desc),
                    icon = Icons.Filled.BarChart,
                )

            else ->
                AnalyticsContent(
                    state = state,
                    onDaysSelected = viewModel::setDays,
                )
        }
    }
}

@Composable
private fun AnalyticsContent(
    state: AnalyticsUiState,
    onDaysSelected: (Int) -> Unit,
) {
    val usage = state.usage ?: return
    val models = state.models

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = listContentPadding,
        verticalArrangement = listItemSpacing,
    ) {
        item {
            DayRangeSelector(
                selected = state.days,
                onSelect = onDaysSelected,
            )
        }

        item { TotalsCard(usage.totals) }

        if (usage.daily.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.analytics_daily_cost)) }
            item { DailyCostChart(entries = usage.daily) }
        }

        if (models != null && models.models.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.analytics_per_model)) }
            items(models.models, key = { it.model }) { model ->
                ModelRow(model = model)
            }
        } else if (usage.by_model.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.analytics_per_model)) }
            items(usage.by_model, key = { it.model }) { entry ->
                ModelEntryRow(entry = entry)
            }
        }

        if (usage.skills.top_skills.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.analytics_top_skills)) }
            items(usage.skills.top_skills, key = { it.skill }) { skill ->
                SkillRow(skill = skill)
            }
        }

        if (usage.tools.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.analytics_top_tools)) }
            items(usage.tools, key = { it.tool }) { tool ->
                ToolRow(tool = tool)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayRangeSelector(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DAY_OPTIONS.forEach { days ->
            FilterChip(
                selected = days == selected,
                onClick = { onSelect(days) },
                label = { Text(stringResource(R.string.analytics_days, days)) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun TotalsCard(totals: com.m57.hermescontrol.data.model.AnalyticsTotals) {
    val status = LocalHermesStatusColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = formatCost(totals.total_estimated_cost),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = status.info,
            )
            Text(
                text = stringResource(R.string.analytics_estimated_cost),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Metric(stringResource(R.string.analytics_sessions), totals.total_sessions.toString())
                Metric(stringResource(R.string.analytics_api_calls), totals.total_api_calls.toString())
                Metric(stringResource(R.string.analytics_input_tokens), formatTokens(totals.total_input))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Metric(stringResource(R.string.analytics_output_tokens), formatTokens(totals.total_output))
                Metric(stringResource(R.string.analytics_cache_read), formatTokens(totals.total_cache_read))
                Metric(stringResource(R.string.analytics_reasoning), formatTokens(totals.total_reasoning))
            }
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Compact bar chart of daily estimated cost. Pure Compose (no chart dependency)
 * — each day is a vertical bar scaled to the max daily cost.
 */
@Composable
private fun DailyCostChart(entries: List<AnalyticsDailyEntry>) {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val maxCost = entries.maxOfOrNull { it.estimated_cost } ?: 0.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (maxCost <= 0.0) {
                Text(
                    text = stringResource(R.string.analytics_no_cost_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val barHeight = 96.dp
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(barHeight),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    entries.forEach { entry ->
                        val fraction = if (maxCost > 0.0) (entry.estimated_cost / maxCost).toFloat() else 0f
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(barHeight * fraction.coerceAtLeast(0.02f))
                                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                        .background(primary),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = entries.firstOrNull()?.day ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = entries.lastOrNull()?.day ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: ModelsAnalyticsModelEntry) {
    val status = LocalHermesStatusColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = model.model,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatCost(model.estimated_cost),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = status.info,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    buildString {
                        append(model.provider)
                        append("  •  ")
                        append(formatTokens(model.input_tokens + model.output_tokens))
                        append(" tokens  •  ")
                        append(stringResource(R.string.analytics_sessions).lowercase())
                        append(" ")
                        append(model.sessions)
                        append("  •  ")
                        append(stringResource(R.string.analytics_api_calls).lowercase())
                        append(" ")
                        append(model.api_calls)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelEntryRow(entry: com.m57.hermescontrol.data.model.AnalyticsModelEntry) {
    val status = LocalHermesStatusColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.model,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        buildString {
                            append(formatTokens(entry.input_tokens + entry.output_tokens))
                            append(" tokens  •  ")
                            append(entry.sessions)
                            append(" ")
                            append(stringResource(R.string.analytics_sessions).lowercase())
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatCost(entry.estimated_cost),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = status.info,
            )
        }
    }
}

@Composable
private fun SkillRow(skill: com.m57.hermescontrol.data.model.AnalyticsSkillEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = skill.skill,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        stringResource(
                            R.string.analytics_skill_uses,
                            skill.total_count,
                            skill.percentage.toInt(),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolRow(tool: com.m57.hermescontrol.data.model.AnalyticsToolUsage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.tool,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        stringResource(
                            R.string.analytics_tool_uses,
                            tool.count,
                            tool.percentage.toInt(),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatCost(value: Double): String =
    if (value >= 100.0) {
        "$${"%,.0f".format(value)}"
    } else {
        "$${"%.2f".format(value)}"
    }

private fun formatTokens(value: Long): String =
    when {
        value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
        value >= 1_000 -> "%.1fK".format(value / 1_000.0)
        else -> value.toString()
    }
