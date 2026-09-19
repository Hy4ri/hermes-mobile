package com.m57.hermescontrol.ui.common

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.data.update.AppUpdateState
import com.m57.hermescontrol.data.update.isNewerVersion
import com.m57.hermescontrol.theme.HermesControlTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdatePrereleaseTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun stableReleaseShowsDialogForHyphenRc() = assertStablePrompt("1.25.0-rc.1")

    @Test
    fun stableReleaseShowsDialogForDotRc() = assertStablePrompt("1.25.rc.1")

    private fun assertStablePrompt(installed: String) {
        val latest = "v1.25"
        assertTrue(isNewerVersion(latest, installed))
        assertFalse(isNewerVersion(latest, "1.25"))
        compose.setContent {
            HermesControlTheme {
                if (isNewerVersion(latest, installed)) {
                    AppUpdateDialog(
                        state = AppUpdateState.UpdateAvailable(latest, "https://example.com/test.apk", 1024L),
                        onDismiss = {},
                        onStartUpdate = {},
                        onCancelDownload = {},
                        onNeverAskAgain = {},
                        onOpenSettings = {},
                    )
                }
            }
        }
        compose.onNodeWithText(latest, substring = true).assertIsDisplayed()
    }
}
