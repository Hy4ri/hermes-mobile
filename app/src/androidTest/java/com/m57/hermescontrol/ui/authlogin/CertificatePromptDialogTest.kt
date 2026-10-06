package com.m57.hermescontrol.ui.authlogin

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Rendered UI evidence with test-only state; not a KeyChain or TLS handshake test. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class CertificatePromptDialogTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val origin = "https://example.test:8443/".toHttpUrl()

    @Test
    fun initialPrompt_requiresSelectionAndAllowsCancellation() {
        var dismissed = 0
        show(CertificatePromptState(origin = origin), Locale.ENGLISH) { dismissed++ }
        composeTestRule.onNodeWithText("Select Certificate").assertIsEnabled()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        screenshot("mtls-initial-prompt.png")
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun verificationFailure_usesReadableDetailsAndAllowsRetry() {
        val resources = show(failedState(), Locale.ENGLISH)
        composeTestRule
            .onNodeWithText(resources.getString(R.string.mtls_prompt_verification_details))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Select Certificate").assertIsEnabled()
        composeTestRule.onNodeWithText("Save").assertIsEnabled()
        screenshot("mtls-verification-failure-en.png")
    }

    @Test
    fun verificationFailure_ChineseAtLargerFontRemainsReadable() {
        val resources = show(failedState(), Locale.SIMPLIFIED_CHINESE, fontScale = 1.3f)
        composeTestRule
            .onNodeWithText(resources.getString(R.string.mtls_prompt_verification_details))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(resources.getString(R.string.mtls_select)).assertIsEnabled()
        composeTestRule.onNodeWithText(resources.getString(R.string.action_save)).assertIsEnabled()
        screenshot("mtls-verification-failure-zh.png")
    }

    private fun failedState() =
        CertificatePromptState(
            origin = origin,
            alias = "test-client",
            error = R.string.mtls_prompt_verification_details,
        )

    private fun show(
        state: CertificatePromptState,
        locale: Locale,
        fontScale: Float = 1f,
        onDismiss: () -> Unit = {},
    ): android.content.res.Resources {
        val activity = composeTestRule.activity
        val configuration = Configuration(activity.resources.configuration).apply { setLocale(locale) }
        val localized = activity.createConfigurationContext(configuration)
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                HermesControlTheme {
                    Surface {
                        CertificatePromptDialog(state, onSelect = {}, onSave = {}, onDismiss = onDismiss)
                    }
                }
            }
        }
        return localized.resources
    }

    private fun screenshot(name: String) {
        val bitmap = composeTestRule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(composeTestRule.activity.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
