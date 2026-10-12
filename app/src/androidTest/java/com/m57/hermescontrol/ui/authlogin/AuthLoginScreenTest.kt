package com.m57.hermescontrol.ui.authlogin

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.HermesControlTheme
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Exercises the real provider probe and rendered login UI, without contacting an external IdP. */
@RunWith(AndroidJUnit4::class)
class AuthLoginScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val viewModelStore = ViewModelStore()
    private lateinit var viewModel: AuthLoginViewModel

    @Before
    fun setUp() {
        server.start()
        mockkObject(AuthManager)
        every { AuthManager.getBaseUrl() } returns server.url("/").toString()
        every { AuthManager.getSelectedProfileId() } returns "instrumented-test-profile"
        every { AuthManager.getConnectionProfiles() } returns emptyList()
    }

    @After
    fun tearDown() {
        try {
            composeRule.runOnUiThread { viewModelStore.clear() }
            server.shutdown()
        } finally {
            unmockkObject(AuthManager)
        }
    }

    @Test
    fun basicOnly_showsUsernameAndPasswordWithoutToken() {
        show(listOf(BASIC))

        credential(R.string.auth_login_username_label).performScrollTo().assertIsDisplayed()
        credential(R.string.auth_login_password_label).performScrollTo().assertIsDisplayed()
        credential(R.string.auth_login_token_label).assertDoesNotExist()
        assertEquals("basic", viewModel.uiState.value.selectedProvider)
        screenshot("issue-1515-basic.png")
        assertEquals("/api/status", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertEquals("/api/auth/providers", server.takeRequest(5, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun nousOnly_showsBrowserSignInWithoutCredentials() {
        show(listOf(NOUS))

        browserButton("Nous Research").performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertNoCredentials()
        assertEquals("nous", viewModel.uiState.value.selectedProvider)
        screenshot("issue-1515-nous.png")
    }

    @Test
    fun oidcOnly_showsBrowserSignInWithoutCredentials() {
        show(listOf(OIDC))

        browserButton("Self-Hosted OIDC").performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertNoCredentials()
        assertEquals("self-hosted", viewModel.uiState.value.selectedProvider)
        screenshot("issue-1515-oidc.png")
    }

    @Test
    fun mixedProviders_switchingChangesTheFormAndNeverOffersDrain() {
        show(listOf(BASIC, NOUS, OIDC, DRAIN))
        assertEquals(
            listOf("basic", "nous", "self-hosted"),
            viewModel.uiState.value.providers
                .map { it.name },
        )
        composeRule.onNodeWithText("Drain Control (service credential)").assertDoesNotExist()

        composeRule.onNodeWithText("Nous Research").performScrollTo().performClick()
        browserButton("Nous Research").performScrollTo().assertIsDisplayed()
        assertNoCredentials()

        composeRule.onNodeWithText("Self-Hosted OIDC").performScrollTo().performClick()
        browserButton("Self-Hosted OIDC").performScrollTo().assertIsDisplayed()
        assertNoCredentials()

        composeRule.onNodeWithText("Username & Password").performScrollTo().performClick()
        credential(R.string.auth_login_username_label).performScrollTo().assertIsDisplayed()
        credential(R.string.auth_login_password_label).performScrollTo().assertIsDisplayed()
        browserButton("Self-Hosted OIDC").assertDoesNotExist()
    }

    @Test
    fun oldGateway_disablesBrowserSignInWithoutOfferingPasswordFallback() {
        show(listOf(OIDC), nativePkce = false)

        browserButton("Self-Hosted OIDC").performScrollTo().assertIsNotEnabled()
        composeRule
            .onNodeWithText(app.getString(R.string.auth_login_error_browser_unsupported))
            .performScrollTo()
            .assertIsDisplayed()
        assertNoCredentials()
    }

    private fun show(
        providers: List<String>,
        nativePkce: Boolean = true,
    ) {
        val flows = if (nativePkce) "\"cookie\",\"native_pkce\"" else "\"cookie\""
        val providerBody = "{\"providers\":[${providers.joinToString(",")}] }"
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body =
                        when (request.path) {
                            "/api/status" -> """{"auth_required":true,"auth_flows":[$flows]}"""
                            "/api/auth/providers" -> providerBody
                            else -> return MockResponse().setResponseCode(404)
                        }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
        composeRule.runOnUiThread {
            viewModel = AuthLoginViewModel(app)
            viewModelStore.put("auth-login", viewModel)
            viewModel.refreshForSelectedConnection()
            viewModel.probe()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            !viewModel.uiState.value.probing && viewModel.uiState.value.authMode != null
        }
        composeRule.setContent {
            HermesControlTheme {
                AuthLoginScreen(onConnected = {}, onBack = {}, viewModel = viewModel)
            }
        }
    }

    private fun credential(label: Int) = composeRule.onNode(hasText(app.getString(label)) and hasSetTextAction())

    private fun browserButton(displayName: String) =
        composeRule.onNodeWithText(app.getString(R.string.auth_login_action_browser, displayName))

    private fun assertNoCredentials() {
        credential(R.string.auth_login_username_label).assertDoesNotExist()
        credential(R.string.auth_login_password_label).assertDoesNotExist()
        credential(R.string.auth_login_token_label).assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(composeRule.activity.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    companion object {
        private const val BASIC =
            """{"name":"basic","display_name":"Username & Password","supports_password":true}"""
        private const val NOUS = """{"name":"nous","display_name":"Nous Research","supports_password":false}"""
        private const val OIDC =
            """{"name":"self-hosted","display_name":"Self-Hosted OIDC",
                "supports_password":false}"""
        private const val DRAIN =
            """{"name":"drain-secret","display_name":"Drain Control (service credential)",
                "supports_password":false}"""
    }
}
