package com.m57.hermescontrol.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.HubSkill
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.data.model.PluginInfo
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.Toolset
import com.m57.hermescontrol.data.model.WebhookSubscription

/**
 * Mappers that turn a domain model into the rows shown by [DetailDialog].
 * Keeping these here (instead of inline in each screen) avoids the
 * showDetail + DetailDialog wiring being copied across six screens.
 */

@Composable
fun Skill.toDetailRows(): List<DetailRow> =
    listOf(
        DetailRow(stringResource(R.string.detail_dialog_category), category),
        DetailRow(stringResource(R.string.detail_dialog_source), source),
        DetailRow(stringResource(R.string.detail_dialog_description), description),
    )

@Composable
fun HubSkill.toDetailRows(): List<DetailRow> =
    listOf(
        DetailRow(stringResource(R.string.detail_dialog_category), category),
        DetailRow(stringResource(R.string.detail_dialog_source), source),
        DetailRow(stringResource(R.string.detail_dialog_description), description),
        DetailRow(stringResource(R.string.detail_dialog_tags), tags.orEmpty().joinToString(", ")),
        DetailRow(stringResource(R.string.detail_dialog_trust_level), trustLevel),
    )

@Composable
fun McpServer.toDetailRows(): List<DetailRow> {
    val commandSummary =
        buildString {
            if (command?.isNotBlank() == true) append(command)
            if (!args.isNullOrEmpty()) append(" " + args.joinToString(" "))
        }.trim().ifBlank { null }

    return listOf(
        DetailRow(stringResource(R.string.detail_dialog_transport), transport),
        DetailRow(stringResource(R.string.detail_dialog_status), status, toneForStatus(status)),
        DetailRow(stringResource(R.string.detail_dialog_url), url),
        DetailRow(stringResource(R.string.detail_dialog_command), commandSummary),
        DetailRow(
            stringResource(R.string.detail_dialog_env_vars),
            env?.entries?.joinToString("\n") { "${it.key}=${it.value}" },
        ),
        DetailRow(stringResource(R.string.detail_dialog_error), error),
    )
}

@Composable
fun PluginInfo.toDetailRows(): List<DetailRow> =
    listOf(
        DetailRow(stringResource(R.string.detail_dialog_version), version),
        DetailRow(stringResource(R.string.detail_dialog_source), source),
        DetailRow(
            stringResource(R.string.detail_dialog_status),
            runtimeStatus,
            toneForStatus(runtimeStatus),
        ),
        DetailRow(stringResource(R.string.detail_dialog_description), description),
    )

@Composable
fun Toolset.toDetailRows(): List<DetailRow> =
    listOf(
        DetailRow(stringResource(R.string.detail_dialog_label), label),
        DetailRow(stringResource(R.string.detail_dialog_description), description),
        DetailRow(stringResource(R.string.detail_dialog_tools), tools.orEmpty().joinToString(", ")),
        DetailRow(
            stringResource(R.string.detail_dialog_status),
            if (enabled) {
                stringResource(R.string.detail_dialog_status_enabled)
            } else {
                stringResource(R.string.detail_dialog_status_disabled)
            },
            if (enabled) DetailRowTone.SUCCESS else DetailRowTone.NEUTRAL,
        ),
    )

@Composable
fun WebhookSubscription.toDetailRows(): List<DetailRow> =
    listOf(
        DetailRow(
            stringResource(R.string.detail_dialog_status),
            if (enabled == true) {
                stringResource(R.string.detail_dialog_status_enabled)
            } else {
                stringResource(R.string.detail_dialog_status_disabled)
            },
            if (enabled == true) DetailRowTone.SUCCESS else DetailRowTone.NEUTRAL,
        ),
        DetailRow(stringResource(R.string.detail_dialog_deliver), deliver),
        DetailRow(stringResource(R.string.detail_dialog_description), description),
        DetailRow(stringResource(R.string.detail_dialog_events), events.orEmpty().joinToString(", ")),
        DetailRow(stringResource(R.string.detail_dialog_prompt), prompt),
        DetailRow(stringResource(R.string.detail_dialog_skills), skills.orEmpty().joinToString(", ")),
        DetailRow(stringResource(R.string.detail_dialog_url), url),
        DetailRow(stringResource(R.string.detail_dialog_created_at), created_at),
    )

/**
 * Map a free-form status string to a semantic tone. Recognises the app's
 * common status vocabulary (enabled/active/running/ok → success, etc.).
 */
private fun toneForStatus(status: String?): DetailRowTone {
    if (status.isNullOrBlank()) return DetailRowTone.NONE
    return when (status.lowercase()) {
        "enabled", "active", "running", "ok", "online", "connected" -> DetailRowTone.SUCCESS
        "disabled", "inactive", "offline", "disconnected" -> DetailRowTone.NEUTRAL
        "error", "failed", "unreachable" -> DetailRowTone.ERROR
        "warning", "degraded" -> DetailRowTone.WARNING
        else -> DetailRowTone.NONE
    }
}
