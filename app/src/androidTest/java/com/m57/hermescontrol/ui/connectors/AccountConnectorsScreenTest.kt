package com.m57.hermescontrol.ui.connectors

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.AccountConnectorResult
import com.m57.hermescontrol.data.model.ConnectorCatalogEntry
import com.m57.hermescontrol.data.model.ConnectorError
import com.m57.hermescontrol.data.model.ConnectorListResult
import com.m57.hermescontrol.data.ws.AccountConnectorRepository
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountConnectorsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val repository = mockk<AccountConnectorRepository>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var vm: AccountConnectorsViewModel

    @After fun tearDown() {
        if (::vm.isInitialized) compose.runOnUiThread { vm.viewModelScope.cancel() }
        unmockkAll()
    }

    @Test fun entitlementFailureShowsAccountExplanationInsteadOfError() {
        showScreen(ConnectorListResult.Error(ConnectorError.Unavailable()))
        compose.onNodeWithText(context.getString(R.string.account_connectors_unavailable_title)).assertIsDisplayed()
        compose
            .onNodeWithText(
                context.getString(R.string.account_connectors_unavailable_description),
            ).assertIsDisplayed()
        compose.onNodeWithText("Connectors are not available in this session.").assertDoesNotExist()
        compose.onNodeWithText("Google Drive").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.account_connectors_check_again)).assertIsDisplayed()
    }

    @Test fun softUnavailableCanBeRecheckedAfterPortalEnablesAccess() {
        showScreen(ConnectorListResult.Success(false, emptyList()))
        compose.onNodeWithText(context.getString(R.string.account_connectors_unavailable_title)).assertIsDisplayed()
        compose.onNodeWithText("Google Drive").assertDoesNotExist()
        coEvery { repository.listConnectors() } returns ConnectorListResult.Success(true, emptyList())
        compose.onNodeWithText(context.getString(R.string.account_connectors_check_again)).performClick()
        compose.onNodeWithText(context.getString(R.string.account_connectors_unavailable_title)).assertDoesNotExist()
        compose.onNodeWithText("Google Drive").assertIsDisplayed()
    }

    @Test fun unavailableExplanationAndRecheckRemainReachableWithLargeTextRtlAndDarkTheme() {
        showScreen(ConnectorListResult.Error(ConnectorError.Unavailable()), largeTextRtl = true)
        compose
            .onNodeWithText(
                context.getString(R.string.account_connectors_unavailable_description),
            ).performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText(
                context.getString(R.string.account_connectors_check_again),
            ).performScrollTo()
            .assertIsDisplayed()
    }

    private fun showScreen(
        result: ConnectorListResult,
        largeTextRtl: Boolean = false,
    ) {
        coEvery { repository.listConnectors() } returns result
        coEvery { repository.catalog() } returns
            AccountConnectorResult.Success(listOf(ConnectorCatalogEntry("drive", "Google Drive", "Files", "storage")))
        coEvery { repository.accounts() } returns AccountConnectorResult.Success(emptyList())
        coEvery { repository.policy() } returns AccountConnectorResult.Failure(ConnectorError.UnsupportedBackend())
        val scope = MutableStateFlow<DataScope?>(DataScope("test", "http://test.invalid", "default", 1))
        compose.runOnUiThread {
            vm = AccountConnectorsViewModel(repository, scope, { scope.value }, emptyFlow())
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, if (largeTextRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeTextRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                HermesControlTheme(
                    themePreference = if (largeTextRtl) ThemePreference.DARK else ThemePreference.LIGHT,
                ) {
                    Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                        AccountConnectorsScreen(onBack = {}, vm = vm)
                    }
                }
            }
        }
    }
}
