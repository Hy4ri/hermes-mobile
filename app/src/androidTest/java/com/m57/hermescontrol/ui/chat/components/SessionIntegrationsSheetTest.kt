package com.m57.hermescontrol.ui.chat.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.model.ConnectorError
import com.m57.hermescontrol.data.model.ConnectorStatus
import com.m57.hermescontrol.ui.chat.ChatConnectorsUiState
import com.m57.hermescontrol.ui.chat.ConnectorActionState
import com.m57.hermescontrol.ui.chat.ConnectorUiItem
import com.m57.hermescontrol.ui.chat.ConnectorsLoadPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class SessionIntegrationsSheetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testLoadingState_displaysLoadingIndicator() {
        composeTestRule.setContent {
            SessionIntegrationsContent(
                uiState =
                    ChatConnectorsUiState(
                        loadPhase = ConnectorsLoadPhase.Loading,
                    ),
                sessionId = "session-12345",
                onDismiss = {},
                onRefresh = {},
                onConnect = { _, _ -> },
                onClearError = {},
            )
        }

        composeTestRule.onNodeWithTag("session_integrations_loading").assertIsDisplayed()
    }

    @Test
    fun testConnectedItem_displaysConnectedStatusAndReconnectAction() {
        var clickedSlug: String? = null
        var clickedReconnect: Boolean? = null

        val item =
            ConnectorUiItem(
                slug = "github",
                name = "GitHub",
                isConnected = true,
                isEnabled = true,
                status = ConnectorStatus.CONNECTED,
                connectionStatus = "connected",
            )

        composeTestRule.setContent {
            SessionIntegrationsContent(
                uiState =
                    ChatConnectorsUiState(
                        loadPhase = ConnectorsLoadPhase.Loaded(available = true),
                        items = listOf(item),
                    ),
                sessionId = "session-12345",
                onDismiss = {},
                onRefresh = {},
                onConnect = { slug, reconnect ->
                    clickedSlug = slug
                    clickedReconnect = reconnect
                },
                onClearError = {},
            )
        }

        composeTestRule.onNodeWithTag("session_integrations_item_github").assertIsDisplayed()
        composeTestRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session_integrations_action_github").assertIsDisplayed()

        composeTestRule.onNodeWithTag("session_integrations_action_github").performClick()
        assertEquals("github", clickedSlug)
        assertEquals(true, clickedReconnect)
    }

    @Test
    fun testDisconnectedItem_displaysConnectActionAndTriggersCallback() {
        var clickedSlug: String? = null
        var clickedReconnect: Boolean? = null

        val item =
            ConnectorUiItem(
                slug = "slack",
                name = "Slack",
                isConnected = false,
                isEnabled = true,
                status = ConnectorStatus.DISCONNECTED,
                connectionStatus = "disconnected",
            )

        composeTestRule.setContent {
            SessionIntegrationsContent(
                uiState =
                    ChatConnectorsUiState(
                        loadPhase = ConnectorsLoadPhase.Loaded(available = true),
                        items = listOf(item),
                    ),
                sessionId = "session-12345",
                onDismiss = {},
                onRefresh = {},
                onConnect = { slug, reconnect ->
                    clickedSlug = slug
                    clickedReconnect = reconnect
                },
                onClearError = {},
            )
        }

        composeTestRule.onNodeWithTag("session_integrations_item_slack").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session_integrations_action_slack").assertIsDisplayed()

        composeTestRule.onNodeWithTag("session_integrations_action_slack").performClick()
        assertEquals("slack", clickedSlug)
        assertEquals(false, clickedReconnect)
    }

    @Test
    fun testConnectingState_displaysSpinnerAndDisablesActions() {
        val item =
            ConnectorUiItem(
                slug = "google",
                name = "Google Drive",
                isConnected = false,
                isEnabled = true,
                status = ConnectorStatus.DISCONNECTED,
                connectionStatus = "disconnected",
            )

        composeTestRule.setContent {
            SessionIntegrationsContent(
                uiState =
                    ChatConnectorsUiState(
                        loadPhase = ConnectorsLoadPhase.Loaded(available = true),
                        items = listOf(item),
                        actionState = ConnectorActionState.Connecting(slug = "google", isReconnect = false),
                    ),
                sessionId = "session-12345",
                onDismiss = {},
                onRefresh = {},
                onConnect = { _, _ -> },
                onClearError = {},
            )
        }

        composeTestRule.onNodeWithTag("session_integrations_spinner_google").assertIsDisplayed()
    }

    @Test
    fun testAwaitingAuthorizationState_displaysBannerAndRefreshStatus() {
        var refreshed = false
        val item =
            ConnectorUiItem(
                slug = "jira",
                name = "Jira",
                isConnected = false,
                isEnabled = true,
                status = ConnectorStatus.INITIATED,
                connectionStatus = "initiated",
            )

        composeTestRule.setContent {
            SessionIntegrationsContent(
                uiState =
                    ChatConnectorsUiState(
                        loadPhase = ConnectorsLoadPhase.Loaded(available = true),
                        items = listOf(item),
                        actionState = ConnectorActionState.AwaitingAuthorization(slug = "jira"),
                    ),
                sessionId = "session-12345",
                onDismiss = {},
                onRefresh = { refreshed = true },
                onConnect = { _, _ -> },
                onClearError = {},
            )
        }

        composeTestRule.onNodeWithTag("session_integrations_awaiting_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session_integrations_refresh_status_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session_integrations_refresh_status_button").performClick()
        assertTrue(refreshed)
    }

    @Test
    fun testUnavailablePhase_displaysErrorMessage() {
        composeTestRule.setContent {
            SessionIntegrationsContent(
                uiState =
                    ChatConnectorsUiState(
                        loadPhase = ConnectorsLoadPhase.Unavailable("Connectors unavailable"),
                        items = emptyList(),
                    ),
                sessionId = "session-12345",
                onDismiss = {},
                onRefresh = {},
                onConnect = { _, _ -> },
                onClearError = {},
            )
        }

        composeTestRule.onNodeWithTag("session_integrations_error").assertIsDisplayed()
    }

    @Test
    fun testErrorPhase_displaysRetryButtonAndTriggersRefresh() {
        var retryTriggered = false

        composeTestRule.setContent {
            SessionIntegrationsContent(
                uiState =
                    ChatConnectorsUiState(
                        loadPhase = ConnectorsLoadPhase.Error(ConnectorError.NetworkError(), canRetry = true),
                        items = emptyList(),
                    ),
                sessionId = "session-12345",
                onDismiss = {},
                onRefresh = { retryTriggered = true },
                onConnect = { _, _ -> },
                onClearError = {},
            )
        }

        composeTestRule.onNodeWithTag("session_integrations_error").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session_integrations_retry_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session_integrations_retry_button").performClick()
        assertTrue(retryTriggered)
    }

    @Test
    fun testEmptyState_displaysNoIntegrationsMessage() {
        composeTestRule.setContent {
            SessionIntegrationsContent(
                uiState =
                    ChatConnectorsUiState(
                        loadPhase = ConnectorsLoadPhase.Loaded(available = true),
                        items = emptyList(),
                    ),
                sessionId = "session-12345",
                onDismiss = {},
                onRefresh = {},
                onConnect = { _, _ -> },
                onClearError = {},
            )
        }

        composeTestRule.onNodeWithTag("session_integrations_empty").assertIsDisplayed()
    }

    @Test
    fun testNoSessionState_displaysNoActiveSession() {
        composeTestRule.setContent {
            SessionIntegrationsContent(
                uiState =
                    ChatConnectorsUiState(
                        loadPhase = ConnectorsLoadPhase.Initial,
                        items = emptyList(),
                    ),
                sessionId = null,
                onDismiss = {},
                onRefresh = {},
                onConnect = { _, _ -> },
                onClearError = {},
            )
        }

        composeTestRule.onNodeWithTag("session_integrations_no_session_state").assertIsDisplayed()
    }

    @Test
    fun testNoStorageButRuntimeSession_usesAuthoritativeRuntimeSessionId() {
        val item =
            ConnectorUiItem(
                slug = "linear",
                name = "Linear",
                isConnected = false,
                isEnabled = true,
                status = ConnectorStatus.DISCONNECTED,
                connectionStatus = "disconnected",
            )

        composeTestRule.setContent {
            SessionIntegrationsContent(
                uiState =
                    ChatConnectorsUiState(
                        loadPhase = ConnectorsLoadPhase.Loaded(available = true),
                        sessionId = "sess-runtime-999",
                        items = listOf(item),
                    ),
                sessionId = null,
                onDismiss = {},
                onRefresh = {},
                onConnect = { _, _ -> },
                onClearError = {},
            )
        }

        composeTestRule.onNodeWithTag("session_integrations_item_linear").assertIsDisplayed()
    }

    @Test
    fun testUnsupportedBackend_displaysErrorMessage() {
        composeTestRule.setContent {
            SessionIntegrationsContent(
                uiState =
                    ChatConnectorsUiState(
                        loadPhase = ConnectorsLoadPhase.UnsupportedBackend("Older server does not support connectors"),
                        items = emptyList(),
                    ),
                sessionId = "session-12345",
                onDismiss = {},
                onRefresh = {},
                onConnect = { _, _ -> },
                onClearError = {},
            )
        }

        composeTestRule.onNodeWithTag("session_integrations_error").assertIsDisplayed()
    }

    @Test
    fun testUnsupportedRuntime_displaysErrorMessage() {
        composeTestRule.setContent {
            SessionIntegrationsContent(
                uiState =
                    ChatConnectorsUiState(
                        loadPhase = ConnectorsLoadPhase.UnsupportedRuntime("Runtime incompatible"),
                        items = emptyList(),
                    ),
                sessionId = "session-12345",
                onDismiss = {},
                onRefresh = {},
                onConnect = { _, _ -> },
                onClearError = {},
            )
        }

        composeTestRule.onNodeWithTag("session_integrations_error").assertIsDisplayed()
    }

    @Test
    fun testNotOwner_displaysErrorMessage() {
        composeTestRule.setContent {
            SessionIntegrationsContent(
                uiState =
                    ChatConnectorsUiState(
                        loadPhase = ConnectorsLoadPhase.NotOwner("Session owned by another user"),
                        items = emptyList(),
                    ),
                sessionId = "session-12345",
                onDismiss = {},
                onRefresh = {},
                onConnect = { _, _ -> },
                onClearError = {},
            )
        }

        composeTestRule.onNodeWithTag("session_integrations_error").assertIsDisplayed()
    }

    @Test
    fun testExpiredAndRevokedItems_displayStatusAndAction() {
        val expiredItem =
            ConnectorUiItem(
                slug = "github",
                name = "GitHub",
                isConnected = false,
                isEnabled = true,
                status = ConnectorStatus.EXPIRED,
                connectionStatus = "expired",
            )
        val revokedItem =
            ConnectorUiItem(
                slug = "jira",
                name = "Jira",
                isConnected = false,
                isEnabled = true,
                status = ConnectorStatus.REVOKED,
                connectionStatus = "revoked",
            )

        composeTestRule.setContent {
            SessionIntegrationsContent(
                uiState =
                    ChatConnectorsUiState(
                        loadPhase = ConnectorsLoadPhase.Loaded(available = true),
                        items = listOf(expiredItem, revokedItem),
                    ),
                sessionId = "session-12345",
                onDismiss = {},
                onRefresh = {},
                onConnect = { _, _ -> },
                onClearError = {},
            )
        }

        composeTestRule.onNodeWithTag("session_integrations_item_github").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session_integrations_action_github").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session_integrations_item_jira").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session_integrations_action_jira").assertIsDisplayed()
    }
}
