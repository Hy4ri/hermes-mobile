package com.m57.hermescontrol.ui.bots.group.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.AlertDialog
import com.m57.hermescontrol.applock.PrivateExposedDropdownMenu
import com.m57.hermescontrol.data.ws.HostedRoomContext
import com.m57.hermescontrol.data.ws.HostedRoomMember
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.bots.group.HostedPendingContextSave
import com.m57.hermescontrol.ui.common.ExpressiveButton as Button
import com.m57.hermescontrol.ui.common.ExpressiveLinearProgressIndicator as LinearProgressIndicator
import com.m57.hermescontrol.ui.common.ExpressiveTextButton as TextButton

private const val MAX_PURPOSE_CHARS = 500
private const val MAX_NOTES_BYTES = 4 * 1024

/** Mount once per editor opening, after a fresh groups.state response. Never replace a failed-save draft. */
@Composable
internal fun HostedRoomContextDialog(
    context: HostedRoomContext?,
    members: List<HostedRoomMember>,
    loading: Boolean,
    saving: Boolean,
    error: String?,
    writable: Boolean,
    pendingContextSave: HostedPendingContextSave? = null,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onSave: (String, String, String?, String?) -> Unit,
) {
    val spacing = LocalSpacing.current
    val errorColor = LocalHermesStatusColors.current.error
    // No draft exists until the fresh response arrives. Background polling does not overwrite local edits.
    val initial = remember(context != null && !loading) { context?.takeUnless { loading } }
    var draft by remember(initial) { mutableStateOf(initial) }
    val current = draft
    val purpose =
        current
            ?.purpose
            .orEmpty()
            .trim()
            .replace(Regex("\\s+"), " ")
    val purposeChars = purpose.codePointCount(0, purpose.length)
    val notesBytes =
        current
            ?.notes
            .orEmpty()
            .trim()
            .toByteArray(Charsets.UTF_8)
            .size
    val validMembers = members.filter { !it.memberId.isNullOrBlank() }.distinctBy { it.memberId }
    val invalidLead = current?.routing == "lead" && validMembers.none { it.memberId == current.leadMemberId }
    val routingChanged =
        current != null && (current.routing != initial?.routing || current.leadMemberId != initial?.leadMemberId)
    val submittedRouting = current?.routing?.takeIf { routingChanged }
    val submittedLeadMemberId =
        when {
            !routingChanged -> null
            submittedRouting == "everyone" -> ""
            else -> current?.leadMemberId
        }
    val exactPendingRetry =
        current != null &&
            isExactHostedContextRetry(
                pendingContextSave,
                error,
                current.purpose,
                current.notes,
                submittedRouting,
                submittedLeadMemberId,
            )
    // A lost ACK can be retried after its lead leaves. Only the exact original payload bypasses roster validation.
    val valid =
        current != null && purposeChars <= MAX_PURPOSE_CHARS && notesBytes <= MAX_NOTES_BYTES &&
            (!invalidLead || exactPendingRetry)
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.hosted_context_settings)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.hosted_context_loading))
                } else if (current != null) {
                    OutlinedTextField(
                        value = current.purpose,
                        onValueChange = { draft = current.copy(purpose = it) },
                        label = { Text(stringResource(R.string.hosted_context_purpose)) },
                        supportingText = {
                            Text(stringResource(R.string.hosted_context_purpose_count, purposeChars, MAX_PURPOSE_CHARS))
                        },
                        isError = purposeChars > MAX_PURPOSE_CHARS,
                        enabled = writable && !saving,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                    OutlinedTextField(
                        value = current.notes,
                        onValueChange = { draft = current.copy(notes = it) },
                        label = { Text(stringResource(R.string.hosted_context_notes)) },
                        supportingText = {
                            Text(stringResource(R.string.hosted_context_notes_count, notesBytes, MAX_NOTES_BYTES))
                        },
                        isError = notesBytes > MAX_NOTES_BYTES,
                        enabled = writable && !saving,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                    )
                    ContextDropdown(
                        label = stringResource(R.string.hosted_context_routing),
                        value =
                            stringResource(
                                if (current.routing == "lead") {
                                    R.string.hosted_context_lead_mode
                                } else {
                                    R.string.hosted_context_everyone
                                },
                            ),
                        enabled = writable && !saving,
                    ) { close ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.hosted_context_everyone)) },
                            onClick = {
                                draft = current.copy(routing = "everyone", leadMemberId = "")
                                close()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.hosted_context_lead_mode)) },
                            enabled = validMembers.isNotEmpty(),
                            onClick = {
                                draft = current.copy(routing = "lead")
                                close()
                            },
                        )
                    }
                    if (current.routing == "lead") {
                        val selectedMember = validMembers.find { it.memberId == current.leadMemberId }
                        ContextDropdown(
                            label = stringResource(R.string.hosted_context_lead_member),
                            value =
                                selectedMember?.contextLabel()
                                    ?: stringResource(R.string.hosted_context_select_member),
                            enabled = writable && !saving && validMembers.isNotEmpty(),
                            isError = invalidLead,
                        ) { close ->
                            validMembers.forEach { member ->
                                DropdownMenuItem(
                                    text = { Text(member.contextLabel()) },
                                    onClick = {
                                        draft = current.copy(leadMemberId = requireNotNull(member.memberId))
                                        close()
                                    },
                                )
                            }
                        }
                        if (invalidLead) {
                            Text(
                                stringResource(
                                    if (current.leadMemberId.isBlank()) {
                                        R.string.hosted_context_select_member
                                    } else {
                                        R.string.hosted_context_lead_unavailable
                                    },
                                ),
                                color = errorColor,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.hosted_context_applies_next),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (error != null || (!loading && current == null)) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Text(
                            stringResource(
                                if (current == null) {
                                    R.string.hosted_context_load_failed
                                } else {
                                    R.string.hosted_context_save_failed
                                },
                            ),
                            color = errorColor,
                        )
                        if (error != null) Text(error, color = errorColor)
                        if (current == null) {
                            TextButton(onClick = onReload, enabled = writable && !loading) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (current != null) {
                        onSave(
                            current.purpose,
                            current.notes,
                            submittedRouting,
                            submittedLeadMemberId,
                        )
                    }
                },
                enabled = writable && !loading && !saving && valid,
            ) {
                Text(
                    stringResource(
                        when {
                            saving -> R.string.hosted_context_saving
                            exactPendingRetry -> R.string.action_retry
                            else -> R.string.action_save
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** An error permits only an exact pending wire payload retry. Omitted routing stays distinct from explicit routing. */
internal fun isExactHostedContextRetry(
    pending: HostedPendingContextSave?,
    error: String?,
    purpose: String,
    notes: String,
    routing: String?,
    leadMemberId: String?,
): Boolean =
    error != null && pending != null && pending.purpose == purpose && pending.notes == notes &&
        pending.routing == routing && pending.leadMemberId == leadMemberId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextDropdown(
    label: String,
    value: String,
    enabled: Boolean,
    isError: Boolean = false,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            isError = isError,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled)
                    .fillMaxWidth(),
        )
        PrivateExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

internal fun HostedRoomMember.contextLabel(): String =
    displayName?.takeIf(String::isNotBlank) ?: handle?.takeIf(String::isNotBlank) ?: memberId.orEmpty()

@Preview(name = "Room settings — narrow", widthDp = 360, heightDp = 720)
@Preview(name = "Room settings — large font", widthDp = 320, heightDp = 640, fontScale = 1.5f, locale = "ar")
@Composable
private fun HostedRoomContextDialogPreview() {
    HermesControlTheme(themePreference = ThemePreference.DARK) {
        HostedRoomContextDialog(
            context = HostedRoomContext("Review the release", "Keep shared decisions here.", "lead", "ada"),
            members = listOf(HostedRoomMember(memberId = "ada", displayName = "Ada")),
            loading = false,
            saving = false,
            error = null,
            writable = true,
            onDismiss = {},
            onReload = {},
            onSave = { _, _, _, _ -> },
        )
    }
}

/** A confirmed but removed lead remains selected and invalid until the user explicitly repairs routing. */
@Preview(name = "Room settings — removed lead", widthDp = 360, heightDp = 720)
@Composable
private fun HostedRoomContextRemovedLeadPreview() {
    HermesControlTheme(themePreference = ThemePreference.DARK) {
        HostedRoomContextDialog(
            context = HostedRoomContext("Review the release", "Keep shared decisions here.", "lead", "removed-member"),
            members =
                listOf(
                    HostedRoomMember(memberId = "ada", displayName = "Ada"),
                    HostedRoomMember(memberId = "linus", displayName = "Linus"),
                ),
            loading = false,
            saving = false,
            error = null,
            writable = true,
            onDismiss = {},
            onReload = {},
            onSave = { _, _, _, _ -> },
        )
    }
}
