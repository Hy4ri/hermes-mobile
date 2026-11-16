package com.m57.hermescontrol.ui.authlogin

import android.content.ClipData
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.AlertDialog
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.common.HermesIconButton
import kotlinx.coroutines.launch
import com.m57.hermescontrol.ui.common.ExpressiveTextButton as TextButton

private const val SETUP_GUIDE_URL = "https://github.com/Hy4ri/hermes-mobile/wiki/Connecting-to-Hermes"

// Shell input stays untranslated: the user types it on the Hermes host as-is.
private const val START_COMMAND = "hermes dashboard --host 0.0.0.0 --no-open"
private const val URL_EXAMPLE = "http://192.168.1.10:9119"

private val StepNumberWidth = 20.dp

/** Help link on the sign-in screen; opens the setup steps in a dialog. */
@Composable
fun DashboardSetupGuideButton(modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            imageVector = Icons.Outlined.HelpOutline,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.auth_login_setup_guide_title))
    }
    if (open) {
        DashboardSetupGuideDialog(onDismiss = { open = false })
    }
}

@Composable
private fun DashboardSetupGuideDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auth_login_setup_guide_title)) },
        text = { DashboardSetupSteps(modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        dismissButton = {
            TextButton(onClick = { uriHandler.openUri(SETUP_GUIDE_URL) }) {
                Text(stringResource(R.string.auth_login_setup_full_guide))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp).size(16.dp),
                )
            }
        },
    )
}

@Composable
private fun DashboardSetupSteps(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SetupStep(
            number = 1,
            text = stringResource(R.string.auth_login_setup_step_start),
            code = START_COMMAND,
        )
        SetupStep(
            number = 2,
            text = stringResource(R.string.auth_login_setup_step_connect, URL_EXAMPLE),
        )
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
                    modifier = Modifier.width(StepNumberWidth).alignByBaseline(),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                )
            }
            if (code != null) {
                HermesIconButton(
                    imageVector = Icons.Filled.ContentCopy,
                    label = stringResource(R.string.content_desc_copy),
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, code)))
                        }
                    },
                )
            }
        }
        if (code != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // A zero-width space after "=" lets a value wrap there instead of mid-word.
                Text(
                    text = code.replace("=", "=\u200B"),
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = 0.sp,
                            textDirection = TextDirection.Ltr,
                        ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// Dialogs do not render in layout previews; this shows the dialog body at the width a
// 360dp phone gives the dialog text slot.
@Preview(name = "Light", widthDp = 264, showBackground = true)
@Preview(name = "Dark", widthDp = 264, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Narrow large text", widthDp = 224, fontScale = 1.5f, showBackground = true)
@Composable
private fun DashboardSetupStepsPreview() {
    HermesControlTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DashboardSetupSteps(modifier = Modifier.padding(vertical = 16.dp))
        }
    }
}

@Preview(name = "Button", widthDp = 360, showBackground = true)
@Composable
private fun DashboardSetupGuideButtonPreview() {
    HermesControlTheme {
        Surface { DashboardSetupGuideButton() }
    }
}
