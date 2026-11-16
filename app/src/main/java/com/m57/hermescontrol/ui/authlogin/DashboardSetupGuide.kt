package com.m57.hermescontrol.ui.authlogin

import android.content.ClipData
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import kotlinx.coroutines.launch
import com.m57.hermescontrol.ui.common.ExpressiveIconButton as IconButton

private const val SETUP_GUIDE_URL = "https://github.com/Hy4ri/hermes-mobile/wiki/Connecting-to-Hermes"

// Shell input stays untranslated: the user types it on the Hermes host as-is.
private const val ENV_SNIPPET =
    "HERMES_DASHBOARD_BASIC_AUTH_USERNAME=admin\n" +
        "HERMES_DASHBOARD_BASIC_AUTH_PASSWORD=your-password\n" +
        "HERMES_DASHBOARD_BASIC_AUTH_SECRET=your-secret-key"
private const val SECRET_COMMAND = "openssl rand -base64 32"
private const val START_COMMAND = "hermes dashboard --host 0.0.0.0 --no-open"
private const val URL_EXAMPLE = "http://192.168.1.10:9119"

/** Collapsed by default so returning users see only the sign-in form. */
@Composable
fun DashboardSetupGuide(
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button, onClick = { expanded = !expanded }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.HelpOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.auth_login_setup_guide_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SetupStep(
                    number = 1,
                    text = stringResource(R.string.auth_login_setup_step_secret),
                    code = SECRET_COMMAND,
                )
                SetupStep(
                    number = 2,
                    text = stringResource(R.string.auth_login_setup_step_login),
                    code = ENV_SNIPPET,
                )
                SetupStep(
                    number = 3,
                    text = stringResource(R.string.auth_login_setup_step_start),
                    code = START_COMMAND,
                )
                SetupStep(
                    number = 4,
                    text = stringResource(R.string.auth_login_setup_step_connect, URL_EXAMPLE),
                )
                TextButton(onClick = { uriHandler.openUri(SETUP_GUIDE_URL) }) {
                    Text(stringResource(R.string.auth_login_setup_full_guide))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 6.dp).size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupStep(
    number: Int,
    text: String,
    code: String? = null,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The copy button sits beside the instruction so the code block keeps the full width.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$number.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(StepIndent).alignByBaseline(),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                )
            }
            if (code != null) {
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, code)))
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.content_desc_copy),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (code != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(start = StepIndent).fillMaxWidth(),
            ) {
                // A zero-width space after "=" lets a value wrap there instead of mid-word.
                Text(
                    text = code.replace("=", "=\u200B"),
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Normal,
                            textDirection = TextDirection.Ltr,
                        ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private val StepIndent = 24.dp

@Preview(name = "Light", widthDp = 360, showBackground = true)
@Preview(name = "Dark", widthDp = 360, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Narrow large text", widthDp = 320, fontScale = 1.5f, showBackground = true)
@Composable
private fun DashboardSetupGuidePreview() {
    HermesControlTheme {
        Surface {
            DashboardSetupGuide(
                initiallyExpanded = true,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
