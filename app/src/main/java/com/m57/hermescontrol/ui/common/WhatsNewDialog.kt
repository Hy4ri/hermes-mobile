package com.m57.hermescontrol.ui.common

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.Dialog
import com.m57.hermescontrol.data.update.ReleaseNoteGroup
import com.m57.hermescontrol.data.update.ReleaseNoteSection
import com.m57.hermescontrol.data.update.ReleaseNotes
import com.m57.hermescontrol.data.update.parseReleaseNotes
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.settings.components.KO_FI_URL
import com.m57.hermescontrol.ui.common.ExpressiveButton as Button
import com.m57.hermescontrol.ui.common.ExpressiveIconButton as IconButton
import com.m57.hermescontrol.ui.common.ExpressiveOutlinedButton as OutlinedButton

/** Post-update "What's new" dialog: grouped release notes plus a Ko-fi support button. */
@Composable
fun WhatsNewDialog(
    tag: String,
    releaseNotes: String,
    releaseUrl: String,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        WhatsNewCard(tag = tag, releaseNotes = releaseNotes, releaseUrl = releaseUrl, onDismiss = onDismiss)
    }
}

@Composable
private fun WhatsNewCard(
    tag: String,
    releaseNotes: String,
    releaseUrl: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val notes = remember(releaseNotes) { parseReleaseNotes(releaseNotes) }
    val openUrl = { url: String ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        Unit
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.whats_new_title, tag),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                HermesTooltip(stringResource(R.string.action_dismiss)) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_dismiss),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReleaseNotesList(notes)
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (releaseUrl.isNotBlank()) {
                OutlinedButton(
                    onClick = { openUrl(releaseUrl) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.whats_new_full_release_notes))
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Button(
                onClick = { openUrl(KO_FI_URL) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.nav_drawer_support))
            }
        }
    }
}

@Composable
private fun ReleaseNotesList(notes: ReleaseNotes) {
    notes.sections.forEach { section ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            section.heading()?.let { heading ->
                Text(
                    text = heading,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            section.items.forEach { item -> ReleaseNoteItem(item) }
        }
    }
    val footer =
        listOfNotNull(
            notes.internalChanges.takeIf { it > 0 }?.let {
                pluralStringResource(R.plurals.whats_new_internal_changes, it, it)
            },
            notes.contributors.takeIf { it.isNotEmpty() }?.let { authors ->
                stringResource(R.string.whats_new_thanks, authors.joinToString(", ") { "@$it" })
            },
        )
    if (footer.isNotEmpty()) {
        Text(
            text = footer.joinToString("\n"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReleaseNoteSection.heading(): String? =
    when (group) {
        ReleaseNoteGroup.FEATURES -> stringResource(R.string.whats_new_section_features)
        ReleaseNoteGroup.IMPROVEMENTS -> stringResource(R.string.whats_new_section_improvements)
        ReleaseNoteGroup.FIXES -> stringResource(R.string.whats_new_section_fixes)
        null -> title
    }

@Composable
private fun ReleaseNoteItem(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Narrow Arabic large text", widthDp = 320, fontScale = 1.5f, locale = "ar")
@Composable
private fun WhatsNewCardPreview() {
    val rtl = LocalConfiguration.current.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        HermesControlTheme {
            WhatsNewCard(
                tag = "v3.4.0",
                releaseNotes =
                    """
                    ## What's Changed
                    * feat(icon): recolor launcher icon to the Default theme by @Hy4ri in https://github.com/o/r/pull/1827
                    * fix(chat): seal reasoning-only segment at tool.start by @Hy4ri in https://github.com/o/r/pull/1833
                    * fix(chat): retire stale typing tail on idle reconciliation by @Erfan817 in https://github.com/o/r/pull/1834
                    * fix(chat): switch models via session.mutate and drop the /model bubble by @trigor74 in https://github.com/o/r/pull/1840
                    * chore: update gateway contract snapshot by @Hy4ri in https://github.com/o/r/pull/1843

                    **Full Changelog**: https://github.com/o/r/compare/v3.3.0...v3.4.0
                    """.trimIndent(),
                releaseUrl = "https://github.com/o/r/releases/tag/v3.4.0",
                onDismiss = {},
            )
        }
    }
}
